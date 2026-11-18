package com.bydmate.app.voice

import com.bydmate.app.diagnostics.DiagnosticEmitters

import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.concurrent.thread

class AudioCapture(private val audioManager: AudioManager, private val prefs: SharedPreferences) {

    companion object {
        private const val SAMPLE_RATE = 16000
        // 100 ms of mono PCM16 @ 16 kHz. Reading in small fixed chunks (not minBuf/2 ~ 500 ms) gives
        // the recognizer ~10 partials/sec, which is what makes early-fire feel instant.
        private const val READ_CHUNK = 1600
        // VOICE_RECOGNITION, VOICE_COMMUNICATION, MIC, UNPROCESSED, DEFAULT
        private val SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,    // 6
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // 7
            MediaRecorder.AudioSource.MIC,                  // 1
            MediaRecorder.AudioSource.UNPROCESSED,          // 9
            MediaRecorder.AudioSource.DEFAULT,              // 0
        )
        internal const val DUCK_VOLUME_INDEX = 1
        // Pre-duck media volume survives process death here; restoreStuckDuck() reads it
        // at service start (stuck-quiet media after a crash / APK update mid session).
        internal const val KEY_PRE_DUCK_VOLUME = "pre_duck_volume"
    }

    private val duckLock = Any()
    private val owners = mutableMapOf<Long, Int>()
    private val legacyOwners = mutableListOf<Pair<Int, MediaSuppressionLease>>()
    private var nextOwner = 0L
    private var pendingRestore: Int? = null

    // No caller-side endpointing: end-of-speech is decided by the recognizer's VAD, not
    // an energy/silence heuristic here. The old RMS gate closed the window ~800 ms in if the user
    // hadn't started talking loudly yet, cutting them off mid-phrase. [maxMs] is only a safety
    // backstop so the mic can't stay open forever (e.g. continuous noise with no VAD endpoint).
    // [mark] runs on the capture thread right after each read, with the samples just read, so it
    // describes the moment the audio was captured -- frames can wait in the channel while the
    // consumer blocks (a decode).
    fun <T> captureSession(maxMs: Long = 8000, mark: (ShortArray) -> T): Flow<MicFrame<T>> = callbackFlow {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE) // ~0.5s

        // Mute media before attempting to open the microphone. The lease also
        // protects capture if another owner releases while the microphone is open.
        val suppression = try {
            acquireSuppression(0)
        } catch (t: Throwable) {
            close(t)
            return@callbackFlow
        }

        // If the mic cannot open, restore the volume we just ducked before bailing — otherwise
        // media would stay stuck at the duck level with no listen window to justify it.
        val record = openRecord(minBuf) ?: run {
            DiagnosticEmitters.voiceFailure("listening", reason = "unavailable")
            suppression.close()
            close(IllegalStateException("mic unavailable"))
            return@callbackFlow
        }

        // Fix C — release the already-initialized AudioRecord and abandon focus if
        // requestAudioFocus or startRecording throw (otherwise both resources leak
        // because awaitClose has not been registered yet at this point).
        try {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            record.startRecording()
            DiagnosticEmitters.lifecycle("listening")
        } catch (t: Throwable) {
            DiagnosticEmitters.voiceFailure("listening", t)
            runCatching { record.release() }
            runCatching { audioManager.abandonAudioFocus(null) }
            suppression.close()
            close(t)
            return@callbackFlow
        }

        val worker = thread(name = "voice-capture") {
            val buf = ShortArray(READ_CHUNK)
            val start = System.nanoTime()
            try {
                while (!isClosedForSend) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) buf.copyOf(n).let { pcm -> trySend(MicFrame(pcm, mark(pcm))) }
                    if ((System.nanoTime() - start) / 1_000_000 >= maxMs) break
                }
            } finally {
                close()
            }
        }

        awaitClose {
            DiagnosticEmitters.lifecycle("stopped")
            runCatching { record.stop() }
            runCatching { record.release() }
            // Each cleanup step is independent: a throw in one must not skip the
            // volume restore below (otherwise media stays ducked at 15%).
            runCatching { audioManager.abandonAudioFocus(null) }
            suppression.close()
            worker.interrupt()
        }
    }

    /** Owns one suppression level until closed. Closing more than once is safe. */
    fun acquireSuppression(targetVolume: Int): MediaSuppressionLease = synchronized(duckLock) {
        require(targetVolume >= 0)
        val first = owners.isEmpty()
        val saved = if (first) {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val recovery = prefs.getInt(KEY_PRE_DUCK_VOLUME, -1)
            if (current <= DUCK_VOLUME_INDEX && recovery >= 0) recovery else current
        } else pendingRestore!!
        val effective = minOf(saved, targetVolume, owners.values.minOrNull() ?: targetVolume)
        if (first) {
            pendingRestore = saved
            prefs.edit().putInt(KEY_PRE_DUCK_VOLUME, saved).apply()
        }
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, effective, 0)
        } catch (t: Throwable) {
            if (first) pendingRestore = null
            throw t
        }
        val id = nextOwner++
        owners[id] = targetVolume
        MediaSuppressionLease { releaseSuppression(id) }
    }

    private fun releaseSuppression(id: Long) = synchronized(duckLock) {
        if (owners.remove(id) == null) return@synchronized
        val target = pendingRestore ?: return@synchronized
        val effective = minOf(target, owners.values.minOrNull() ?: target)
        val restored = runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, effective, 0)
        }.isSuccess
        if (owners.isEmpty()) {
            pendingRestore = null
            if (restored) prefs.edit().remove(KEY_PRE_DUCK_VOLUME).apply()
        }
    }

    /** Compatibility wrapper. Each successful call owns one independent registry entry. */
    internal fun duckMusic(): Int? = synchronized(duckLock) {
        val lease = runCatching { acquireSuppression(DUCK_VOLUME_INDEX) }.getOrNull() ?: return null
        val saved = pendingRestore!!
        legacyOwners.add(saved to lease)
        saved
    }

    internal fun restoreMusic(saved: Int?): Unit = synchronized(duckLock) {
        if (saved == null) return
        val index = legacyOwners.indexOfLast { it.first == saved }
        if (index >= 0) legacyOwners.removeAt(index).second.close()
    }

    fun pendingRestoreVolume(): Int? = synchronized(duckLock) { pendingRestore }

    fun applyExplicitVolume(level: Int): Unit = synchronized(duckLock) {
        if (owners.isEmpty()) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
            prefs.edit().remove(KEY_PRE_DUCK_VOLUME).apply()
        } else {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, minOf(level, owners.values.minOrNull()!!), 0)
            pendingRestore = level
            prefs.edit().putInt(KEY_PRE_DUCK_VOLUME, level).apply()
        }
    }

    /** Restore a crash marker only when no live owner exists and the user has not raised volume. */
    fun restoreStuckDuck(): Unit = synchronized(duckLock) {
        if (owners.isNotEmpty()) return
        val saved = prefs.getInt(KEY_PRE_DUCK_VOLUME, -1)
        if (saved < 0) return
        val current = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return
        if (current <= DUCK_VOLUME_INDEX) {
            if (runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }.isFailure) return
        }
        prefs.edit().remove(KEY_PRE_DUCK_VOLUME).apply()
    }

    private fun openRecord(minBuf: Int): AudioRecord? {
        for (src in SOURCES) {
            val r = runCatching {
                AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) return r
            r?.release()
        }
        return null
    }
}

/** One mic read and the caller's [AudioCapture.captureSession] mark taken when it was captured. */
class MicFrame<T>(val pcm: ShortArray, val mark: T)

/** An independently owned media suppression; release is safe from any thread. */
class MediaSuppressionLease internal constructor(private val release: () -> Unit) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
