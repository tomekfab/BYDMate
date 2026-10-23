package com.bydmate.app.voice

import android.util.Log
import com.bydmate.app.voice.openclaw.OpenClawAssistant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Switches the running recognizer to another profile after the readiness checks; see
 *  [ProfileAsrEngine.activate]. A public seam so settings code need not see the internal engine. */
fun interface ProfileSwitcher {
    suspend fun activate(target: VoiceLanguage, readiness: ProfileReadiness): ActivationResult
}

/** What the settings screen shows about voice languages and their models. */
data class VoiceLanguageState(
    val active: VoiceLanguage = VoiceLanguage.DEFAULT,
    /** The language the user asked for that is not active yet. */
    val pending: VoiceLanguage? = null,
    /** Every Polish recognizer file exists with its pinned size and the shared VAD is present.
     *  Activation still hashes the files. */
    val asrReady: Boolean = false,
    /** -1 = idle, 0..100 = downloading. */
    val asrProgress: Int = -1,
    val asrPhase: PolishModelManager.Phase? = null,
    val asrFailed: Boolean = false,
    val asrRequiredBytes: Long? = null,
    val asrAvailableBytes: Long? = null,
    /** The crash guard stopped loading the recognizer: delete and download it again. */
    val asrBlocked: Boolean = false,
    /** Gosia is installed and every file matches its pinned SHA-256. */
    val voiceReady: Boolean = false,
    val voiceProgress: Int = -1,
    val voiceFailed: Boolean = false,
    val voiceBlocked: Boolean = false,
    val activating: Boolean = false,
    /** Polish lines for why the last activation did not happen; empty when there is no problem. */
    val activationProblems: List<String> = emptyList(),
)

/**
 * Setup and switching of the voice profiles: downloads, verified readiness, the transactional
 * activation, safe deletion and the crash-guard housekeeping. The settings screen and
 * TrackingService call it; it owns no UI and no vehicle access.
 *
 * Rules it enforces:
 *  - A profile becomes active only after full hash verification of its recognizer, the shared VAD
 *    and its voice ([PolishModelManager.readiness]); `isReady()` (sizes only) never gates it.
 *  - Deleting Polish ASR files keeps the shared VAD whenever it is still needed.
 *  - Native objects are released through [ProfileAsrEngine] before any file goes away.
 *  - Audio of the assistant test and of Polish speech is local Gosia only.
 */
@Singleton
class VoiceLanguageSetup @Inject constructor(
    private val store: VoiceProfileStore,
    private val asrAssets: SetupAsrAssets,
    private val voiceAssets: SetupVoiceAssets,
    private val switcher: ProfileSwitcher,
    private val assistant: OpenClawAssistant,
) {
    private val polish get() = asrAssets.polish
    private val coordinator get() = asrAssets.coordinator
    private val asrGuard get() = asrAssets.guard
    private val tts get() = voiceAssets.models
    private val ttsEngine get() = voiceAssets.engine
    private val polishTtsEngine get() = voiceAssets.polishEngine
    private val ttsGuard get() = voiceAssets.guard
    private val gosia = TtsVoiceCatalog.defaultFor()

    private val _state = MutableStateFlow(VoiceLanguageState(active = store.activeLanguage(), pending = store.pendingLanguage))
    val state: StateFlow<VoiceLanguageState> = _state.asStateFlow()

    private val polishAsrScope get() = polish.guardScope
    private val gosiaScope = AsrLoadGuard.scopeFor(gosia.language, gosia.modelDirId)

    /** Re-reads everything from disk. Hashes Gosia, so it runs off the main thread. */
    suspend fun refresh() {
        val voiceReady = withContext(Dispatchers.IO) { tts.isReady(gosia) && tts.isPolishVoiceVerified() }
        val asrReady = withContext(Dispatchers.IO) { polish.isReady() }
        _state.update {
            it.copy(
                active = store.activeLanguage(),
                pending = store.pendingLanguage,
                asrReady = asrReady,
                voiceReady = voiceReady,
                asrBlocked = asrGuard.isTripped(polishAsrScope),
                voiceBlocked = ttsGuard.isTtsTripped(gosiaScope),
            )
        }
    }

    // --- downloads -------------------------------------------------------------------------------

    suspend fun downloadAsr(): Result<Unit> {
        if (_state.value.asrProgress >= 0) return Result.success(Unit)
        _state.update {
            it.copy(
                asrProgress = 0, asrPhase = PolishModelManager.Phase.ASR, asrFailed = false,
                asrRequiredBytes = null, asrAvailableBytes = null,
            )
        }
        val result = try {
            polish.download { phase, pct ->
                // A late callback after a cancel must not bring the progress bar back.
                _state.update { if (it.asrProgress < 0) it else it.copy(asrProgress = pct, asrPhase = phase) }
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(asrProgress = -1, asrPhase = null) }
            throw e
        }
        val shortfall = result.exceptionOrNull() as? PolishModelManager.InsufficientStorageException
        _state.update {
            it.copy(
                asrProgress = -1, asrPhase = null, asrReady = polish.isReady(),
                asrFailed = result.isFailure,
                asrRequiredBytes = shortfall?.requiredBytes, asrAvailableBytes = shortfall?.availableBytes,
            )
        }
        return result
    }

    suspend fun downloadVoice(): Result<Unit> {
        if (_state.value.voiceProgress >= 0) return Result.success(Unit)
        _state.update { it.copy(voiceProgress = 0, voiceFailed = false) }
        val result = try {
            tts.download(gosia) { pct ->
                _state.update { if (it.voiceProgress < 0) it else it.copy(voiceProgress = pct) }
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(voiceProgress = -1) }
            throw e
        }
        val verified = result.isSuccess && withContext(Dispatchers.IO) { tts.isPolishVoiceVerified() }
        if (verified) {
            ttsGuard.reset(gosiaScope)
            ttsEngine.reload()
            polishTtsEngine.reload()
        }
        _state.update {
            it.copy(
                voiceProgress = -1, voiceReady = verified, voiceFailed = result.isFailure,
                voiceBlocked = ttsGuard.isTtsTripped(gosiaScope),
            )
        }
        return result
    }

    // --- deletion --------------------------------------------------------------------------------

    /** Removes the Polish recognizer. Any active session ends and the selected profile becomes
     *  not ready until its verified assets are installed again. */
    suspend fun deleteAsr() {
        polish.delete() // closes native objects through the coordinator first
        asrGuard.reset(polishAsrScope)
        refresh()
    }

    suspend fun deleteVoice() {
        voiceAssets.delete(gosia)
        refresh()
    }

    // --- activation ------------------------------------------------------------------------------

    /**
     * Makes Polish the active profile if, and only if, the recognizer, the shared VAD and Gosia
     * all verify by hash. Otherwise nothing changes and the Polish
     * problems are in [VoiceLanguageState.activationProblems].
     */
    suspend fun activatePolish(): ActivationResult =
        activation.activate(VoiceLanguage.PL, polish.readiness(tts = { tts.isPolishVoiceVerified() }))

    private val activation = ProfileActivation()
    private inner class ProfileActivation {
        suspend fun activate(target: VoiceLanguage, readiness: ProfileReadiness): ActivationResult {
            _state.update { it.copy(activating = true, activationProblems = emptyList()) }
            val result = try {
                prepareDictionary(target) ?: switcher.activate(target, readiness)
            } catch (e: CancellationException) {
                _state.update { it.copy(activating = false) }
                throw e
            }
            if (result is ActivationResult.Activated) {
                ttsEngine.reload() // the selected voice follows the profile
                // A voice-language change starts a new conversation; pairing is kept.
                runCatching { assistant.resetConversation() }
            }
            Log.i(TAG, "activation target=${target.id} result=${result::class.simpleName}")
            refresh()
            _state.update {
                it.copy(
                    activating = false,
                    activationProblems = (result as? ActivationResult.NotReady)?.problems?.let(::polishProblems).orEmpty(),
                )
            }
            return result
        }

        /** Polish command coverage is part of the profile contract. Build its dictionary before
         * the profile switch commits, on a worker dispatcher; a failure leaves the old profile
         * active and is shown as an activation problem. Russian keeps its existing startup preload. */
        @Suppress("TooGenericExceptionCaught") // Any non-cancellation dictionary failure must reject publication.
        private suspend fun prepareDictionary(target: VoiceLanguage): ActivationResult.NotReady? {
            if (target != VoiceLanguage.PL) return null
            return try {
                if (withContext(Dispatchers.Default) { NluParser.warmUp(target) }) null
                else ActivationResult.NotReady(target, listOf("dictionary: preparation failed (dictionary unavailable)"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ActivationResult.NotReady(target, listOf("dictionary: preparation failed (${e.message})"))
            }
        }
    }

    // --- crash-guard housekeeping ------------------------------------------------------------------

    /**
     * Startup: a Polish file that crashed the process twice is provably unloadable. Delete it so
     * the user can download it again; the Russian files and the other profile are never touched.
     */
    suspend fun quarantineTrippedPolishAssets() {
        if (asrGuard.isTripped(polishAsrScope)) {
            Log.w(TAG, "Polish ASR load guard tripped: deleting the Polish recognizer")
            deleteAsr()
        }
        if (ttsGuard.isTtsTripped(gosiaScope)) {
            Log.w(TAG, "Gosia load guard tripped: deleting the voice")
            deleteVoice()
        }
    }

    private companion object {
        const val TAG = "VoiceLanguageSetup"

        /** One line per asset group: a recognizer with four missing files is one problem, not four. */
        fun polishProblems(problems: List<String>): List<String> {
            val asr = problems.filter { it.startsWith("asr:") }
            val first = asr.firstOrNull()?.let(::polishProblem)
            val grouped = if (first == null) emptyList()
            else listOf(if (asr.size > 1) "$first, razem ${asr.size} problemów" else first)
            return grouped + problems.filterNot { it.startsWith("asr:") }.map(::polishProblem)
        }

        /** Polish line for one problem of [ProfileReadiness]; the technical detail stays after the colon. */
        fun polishProblem(problem: String): String {
            val detail = problem.substringAfter(": ", problem)
            return when {
                problem.startsWith("asr:") -> "Model rozpoznawania mowy: brak lub uszkodzony ($detail)"
                problem.startsWith("vad:") -> "Model wykrywania mowy (VAD): brak"
                problem.startsWith("tts:") -> "Głos Gosia: brak lub uszkodzony"
                else -> "Nie udało się przełączyć języka ($detail)"
            }
        }
    }
}

class SetupAsrAssets @Inject constructor(
    val polish: PolishModelManager,
    val coordinator: NativeAssetCoordinator,
    val guard: AsrLoadGuard,
)

class SetupVoiceAssets @Inject constructor(
    val models: TtsModelManager,
    val engine: TtsEngine,
    @Named("polishTts") val polishEngine: TtsEngine,
    @Named("ttsLoadGuard") val guard: AsrLoadGuard,
) {
    suspend fun delete(voice: TtsVoice) {
        engine.stop()
        polishEngine.stop()
        engine.reload()
        polishEngine.reload()
        models.delete(voice.modelDirId)
        guard.reset(AsrLoadGuard.scopeFor(voice.language, voice.modelDirId))
    }
}
