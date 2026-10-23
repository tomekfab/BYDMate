package com.bydmate.app.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Lets a file owner replace or delete assets safely: [block] runs only after every session
 *  that uses [language]'s native objects has ended and those objects are closed, and nothing
 *  can load them again until [block] returns. Without this, deleting a model under a live
 *  recognizer crashes the process from native code. */
interface NativeAssetCoordinator {
    suspend fun <T> withAssetsReleased(language: VoiceLanguage, block: suspend () -> T): T
}

/** The continuous-ASR entry point that follows the active voice profile.
 *
 *  Each session captures the profile when it starts and runs on that profile's engine until
 *  it ends; the profile cannot change under it. A switch ([activate]) and an asset release
 *  ([withAssetsReleased]) both end the sessions that use the affected native objects, wait
 *  for them to finish, and only then close those objects. Sessions that end this way complete
 *  normally (as if the audio stopped), so [ContinuousAsrEvent]s and the session loop's
 *  handling of a finished flow stay exactly as they are. */
@Suppress("TooManyFunctions") // One lifecycle owner deliberately implements both ASR and asset-release contracts.
internal class ProfileAsrEngine(
    private val store: VoiceProfileStore,
    private val engines: Map<VoiceLanguage, ReleasableAsr>,
) : ContinuousAsr, NativeAssetCoordinator {

    private class Session(val language: VoiceLanguage, val engine: ReleasableAsr) {
        val stop = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
    }

    // Guards sessions, blocked and every warm-up. A warm-up holds it while the model loads, so
    // blocking a language waits for an in-flight load instead of racing it.
    private val lock = Any()
    private val sessions = mutableListOf<Session>()
    @Volatile private var blocked: Set<VoiceLanguage> = emptySet()

    // Held from "sessions stopped" to "profile flipped" so a session starting inside that window
    // waits and then captures the new profile.
    private val switchGate = Mutex()
    private val lifecycleMutex = Mutex()

    private fun active(): ReleasableAsr? =
        store.activeLanguage().let { language -> engines[language]?.takeIf { language !in blocked } }

    /** The explicit-session path never substitutes the current profile for [profile]. */
    private fun captured(profile: VoiceSessionProfile): ReleasableAsr? =
        engines[profile.language]?.takeIf { store.activeLanguage() == profile.language && profile.language !in blocked }

    override fun isReady(): Boolean = active()?.isReady() == true
    override fun isWarm(): Boolean = active()?.isWarm() == true
    override fun requiresWarmBeforeListening(): Boolean = active()?.requiresWarmBeforeListening() == true

    override fun isReady(profile: VoiceSessionProfile): Boolean = captured(profile)?.isReady() == true
    override fun isWarm(profile: VoiceSessionProfile): Boolean = captured(profile)?.isWarm() == true
    override fun requiresWarmBeforeListening(profile: VoiceSessionProfile): Boolean =
        captured(profile)?.requiresWarmBeforeListening() == true

    override fun warmUp() {
        synchronized(lock) { active()?.warmUp() }
    }

    override fun warmUp(profile: VoiceSessionProfile) {
        synchronized(lock) { captured(profile)?.warmUp() }
    }

    override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = transcribeCaptured(null, pcm)

    override fun transcribe(profile: VoiceSessionProfile, pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> =
        transcribeCaptured(profile, pcm)

    private fun transcribeCaptured(profile: VoiceSessionProfile?, pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = channelFlow {
        val session = switchGate.withLock { register(profile) } ?: return@channelFlow
        val inner = launch { session.engine.transcribe(pcm).collect { send(it) } }
        val watcher = launch {
            session.stop.await()
            inner.cancel()
        }
        try {
            inner.join()
        } finally {
            // The engine's own finally (VAD close) must be done before anyone waits on `done`.
            withContext(NonCancellable) {
                watcher.cancel()
                inner.cancelAndJoin()
                synchronized(lock) { sessions -= session }
                session.done.complete(Unit)
            }
        }
    }.buffer(Channel.RENDEZVOUS)

    private fun register(profile: VoiceSessionProfile?): Session? = synchronized(lock) {
        val language = profile?.language ?: store.captureSession().language
        if (profile != null && store.activeLanguage() != language) return null
        val engine = engines[language]?.takeIf { language !in blocked } ?: return null
        Session(language, engine).also { sessions += it }
    }

    /** Ends every session of [language] (all sessions when null) and waits until they finished. */
    private suspend fun stopSessions(language: VoiceLanguage?) {
        val targets = synchronized(lock) { sessions.filter { language == null || it.language == language } }
        targets.forEach { it.stop.complete(Unit) }
        targets.forEach { it.done.await() }
    }

    /** Switches the profile after verifying [readiness]. Only a verified switch touches the
     *  running session: it is ended, the other profiles' natives are closed to free memory,
     *  then the stored profile flips; a failed verification interrupts nothing. */
    suspend fun activate(target: VoiceLanguage, readiness: ProfileReadiness): ActivationResult = lifecycleMutex.withLock {
        var gateHeld = false
        try {
            store.activate(target, readiness) {
                switchGate.lock()
                gateHeld = true
                stopSessions(null)
                withContext(Dispatchers.IO) {
                    engines.filterKeys { it != target }.values.forEach { it.releaseNative() }
                }
            }
        } finally {
            if (gateHeld) switchGate.unlock()
        }
    }

    override suspend fun <T> withAssetsReleased(language: VoiceLanguage, block: suspend () -> T): T =
        lifecycleMutex.withLock {
            // Wait for an in-flight warm-up, then refuse new loads of this language.
            withContext(Dispatchers.IO) { synchronized(lock) { blocked = blocked + language } }
            try {
                stopSessions(language)
                withContext(Dispatchers.IO) { engines[language]?.releaseNative() }
                block()
            } finally {
                synchronized(lock) { blocked = blocked - language }
            }
        }
}
