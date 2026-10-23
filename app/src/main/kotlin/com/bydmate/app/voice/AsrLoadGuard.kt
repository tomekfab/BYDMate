package com.bydmate.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/** Breaks the native-crash loop around sherpa-onnx ASR model loads. Loading a corrupt
 *  .onnx aborts the whole process from JNI (uncaught Ort::Exception -> std::terminate ->
 *  SIGABRT), which no Kotlin try/catch can intercept — the on-disk counter below is the
 *  only surviving witness. A per-artifact counter is bumped synchronously BEFORE every
 *  native load and cleared right after it returns; a load that kills the process leaves
 *  the bump behind. After [TRIP_THRESHOLD] interrupted loads of the same artifact the
 *  guard trips: the ASR engine refuses further loads and TrackingService quarantines
 *  the model files so the user can re-download (field defect: Sea Lion 07, power-cut
 *  corrupted the GigaAM v3 model and every service start crash-looped, 2026-07-16).
 *
 *  Voice profiles do not share evidence. The unscoped calls are the original (Russian) keys
 *  and behave exactly as before. The scoped calls (see [scopeFor]) keep a recognizer and a TTS
 *  counter per language and model, so a corrupt Polish model never blocks Russian and a
 *  Russian reset never clears Polish evidence. The silero VAD is one file for every profile,
 *  so its counter is shared: a corrupt VAD stops all of them. */
class AsrLoadGuard(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.getSharedPreferences("asr_load_guard", Context.MODE_PRIVATE))

    /** A null scope retains the original Russian counters; VAD is always shared. */
    fun isTripped(scope: String? = null): Boolean =
        listOf(ARTIFACT_RECOGNIZER, ARTIFACT_VAD, ARTIFACT_TTS).any { tripped(keyFor(it, scope)) }

    /** commit(), not apply(): the bump must be on flash before the native call can abort. */
    @SuppressLint("ApplySharedPref")
    fun noteLoadBegin(artifact: String, scope: String? = null) = bump(keyFor(artifact, scope))

    @SuppressLint("ApplySharedPref")
    fun noteLoadSuccess(artifact: String, scope: String? = null) {
        prefs.edit().putInt(keyFor(artifact, scope), 0).commit()
    }

    /** Scoped resets retain the shared VAD evidence; unscoped resets clear the original keys. */
    @SuppressLint("ApplySharedPref")
    fun reset(scope: String? = null) {
        val artifacts = if (scope == null) listOf(ARTIFACT_RECOGNIZER, ARTIFACT_VAD, ARTIFACT_TTS)
            else listOf(ARTIFACT_RECOGNIZER, ARTIFACT_TTS)
        prefs.edit().apply { artifacts.forEach { remove(keyFor(it, scope)) } }.commit()
    }

    // --- TTS voices -------------------------------------------------------------------------------
    // One native TTS model per voice. A Russian voice keeps the original shared key byte for byte
    // (TrackingService quarantines Russian voices on it); any other language gets its own counter
    // named by language and model, so a crashing Gosia can never make TrackingService delete a
    // Russian voice, and a Russian reset can never hide Gosia's evidence. [ttsScope] null = Russian.

    fun isTtsTripped(ttsScope: String?): Boolean =
        tripped(if (ttsScope == null) ARTIFACT_TTS else keyFor(ARTIFACT_TTS, ttsScope))

    @SuppressLint("ApplySharedPref")
    fun noteTtsLoadBegin(ttsScope: String?) =
        bump(if (ttsScope == null) ARTIFACT_TTS else keyFor(ARTIFACT_TTS, ttsScope))

    @SuppressLint("ApplySharedPref")
    fun noteTtsLoadSuccess(ttsScope: String?) {
        prefs.edit().putInt(if (ttsScope == null) ARTIFACT_TTS else keyFor(ARTIFACT_TTS, ttsScope), 0).commit()
    }

    /** Whether the shared silero VAD counter alone is tripped (a corrupt VAD stops every profile). */
    fun isVadTripped(): Boolean = tripped(ARTIFACT_VAD)

    private fun tripped(key: String) = prefs.getInt(key, 0) >= TRIP_THRESHOLD

    @SuppressLint("ApplySharedPref")
    private fun bump(key: String) {
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).commit()
    }

    companion object {
        const val ARTIFACT_RECOGNIZER = "interrupted_recognizer_loads"
        const val ARTIFACT_VAD = "interrupted_vad_loads"
        const val ARTIFACT_TTS = "interrupted_tts_loads"

        /** Scope for one language's model, e.g. `pl:parakeet-tdt-0.6b-v3-int8`. */
        fun scopeFor(language: VoiceLanguage, modelId: String): String = "${language.id}:$modelId"

        /** The TTS guard scope of [voice]: null for a Russian voice (the original shared key),
         *  language and model for any other. */
        fun ttsScopeOf(voice: TtsVoice): String? =
            scopeFor(voice.language, voice.modelDirId)

        /** 2, not 1: a single interrupted load can be an unrelated mid-load process kill
         *  (LMK, force-stop); quarantining the 226 MiB model on one such event would be a
         *  false positive costing the user a full re-download. */
        internal const val TRIP_THRESHOLD = 2
    }
}

private fun keyFor(artifact: String, scope: String?) =
    if (scope == null || artifact == AsrLoadGuard.ARTIFACT_VAD) artifact else "$scope/$artifact"
