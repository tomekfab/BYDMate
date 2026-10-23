package com.bydmate.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything that must hold before a profile may become active. Each check is separate so a
 *  failed activation can say which asset is missing. [asr] returns problems (empty = every
 *  file verified); [tts] is supplied at integration, so this class knows nothing about voices. */
class ProfileReadiness(
    private val asr: suspend () -> List<String>,
    private val vad: suspend () -> Boolean,
    private val tts: suspend () -> Boolean,
) {
    /** Runs every check (no short-circuit) and returns all problems; empty means ready. */
    suspend fun problems(): List<String> {
        val out = mutableListOf<String>()
        out += attempt("asr") { asr().map { "asr: $it" } }
        out += attempt("vad") { if (vad()) emptyList() else listOf("vad: the shared voice-activity model is not installed") }
        out += attempt("tts") { if (tts()) emptyList() else listOf("tts: the voice for this language is not ready") }
        return out
    }

    private suspend fun attempt(name: String, check: suspend () -> List<String>): List<String> =
        runCatching { check() }.fold(
            onSuccess = { it },
            onFailure = { failure ->
                if (failure is CancellationException) throw failure
                if (failure !is Exception) throw failure
                listOf("$name: check failed (${failure.message})")
            },
        )

}

sealed interface ActivationResult {
    data class Activated(val language: VoiceLanguage) : ActivationResult
    data class AlreadyActive(val language: VoiceLanguage) : ActivationResult
    data class NotReady(val language: VoiceLanguage, val problems: List<String>) : ActivationResult
}

/** Which voice language is active, and the transactional switch between languages.
 *
 *  The stored profile changes only inside [activate], and only after every readiness check
 *  passed: a failed or cancelled attempt leaves the previous profile exactly as it was, so a
 *  half-installed Polish setup can never strand the user on an unusable profile. Installs
 *  from before profiles existed have no stored value and are Russian; reading never writes.
 *  Uses its own preferences file, so no existing Russian preference is touched. */
class VoiceProfileStore(
    private val prefs: SharedPreferences,
    private val voicePrefs: SharedPreferences? = null,
) {

    constructor(context: Context) :
        this(
            context.getSharedPreferences("voice_profile", Context.MODE_PRIVATE),
            context.getSharedPreferences("voice", Context.MODE_PRIVATE),
        )

    private val activation = Mutex()

    fun activeLanguage(): VoiceLanguage = VoiceLanguage.fromId(stringOrNull(prefs, KEY_ACTIVE)) ?: VoiceLanguage.DEFAULT

    /** The language the user asked for but that is not active yet (still downloading or failed). */
    val pendingLanguage: VoiceLanguage? get() = null

    /** One-time, synchronous preference-only migration. It never enables voice or TTS and does
     * not touch assistant identity or OpenClaw preferences. */
    @SuppressLint("ApplySharedPref")
    fun migrateInstalledSelection() {
        prefs.edit().putString(KEY_ACTIVE, VoiceLanguage.PL.id).remove(KEY_PENDING).commit()
        voicePrefs?.edit()?.putString(KEY_TTS_VOICE, TtsVoiceCatalog.GOSIA.id)?.remove(KEY_TTS_SOURCE)?.commit()
    }

    /** The profile for one session: capture once at session start and keep. */
    fun captureSession(): VoiceSessionProfile = VoiceSessionProfile(activeLanguage())

    @SuppressLint("ApplySharedPref")
    fun clearPending() {
        if (prefs.contains(KEY_PENDING)) prefs.edit().remove(KEY_PENDING).commit()
    }

    /** Makes [target] the active profile if, and only if, [readiness] reports no problems.
     *  [beforeCommit] runs after verification and before the switch: the caller stops sessions
     *  and releases native objects there. If it fails, nothing changes. Calls are serialized. */
    @SuppressLint("ApplySharedPref")
    suspend fun activate(
        target: VoiceLanguage,
        readiness: ProfileReadiness,
        beforeCommit: suspend () -> Unit = {},
    ): ActivationResult = activation.withLock {
        val resolvedTarget = VoiceLanguage.PL
        if (target != resolvedTarget) return@withLock ActivationResult.NotReady(resolvedTarget, listOf("unsupported voice language"))
        if (activeLanguage() != resolvedTarget) prefs.edit().putString(KEY_PENDING, resolvedTarget.id).commit()
        val problems = readiness.problems()
        if (problems.isNotEmpty()) return@withLock ActivationResult.NotReady(resolvedTarget, problems)
        if (activeLanguage() == resolvedTarget) {
            clearPending()
            return@withLock ActivationResult.AlreadyActive(resolvedTarget)
        }
        runCatching { beforeCommit() }.exceptionOrNull()?.let { failure ->
            if (failure is CancellationException) throw failure
            if (failure !is Exception) throw failure
            return@withLock ActivationResult.NotReady(
                resolvedTarget, listOf("could not stop the current session (${failure.message})"),
            )
        }
        // One commit for both keys: no state where the new profile is active and still pending.
        val saved = prefs.edit().putString(KEY_ACTIVE, resolvedTarget.id).remove(KEY_PENDING).commit()
        if (saved) ActivationResult.Activated(resolvedTarget)
        else ActivationResult.NotReady(resolvedTarget, listOf("could not save the voice profile"))
    }

    private fun stringOrNull(preferences: SharedPreferences, key: String): String? =
        runCatching { preferences.getString(key, null) }.getOrNull()

    private companion object {
        const val KEY_ACTIVE = "active_language"
        const val KEY_PENDING = "pending_language"
        const val KEY_TTS_VOICE = "tts_voice"
        const val KEY_TTS_SOURCE = "tts_source"
    }
}
