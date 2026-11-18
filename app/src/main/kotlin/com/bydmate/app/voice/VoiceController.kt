package com.bydmate.app.voice

import com.bydmate.app.diagnostics.DiagnosticEmitters

import android.content.Context
import android.util.Log
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.agent.AgentTrace
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.NavDestinationCheck
import com.bydmate.app.data.automation.NavDestinationRules
import com.bydmate.app.data.automation.NavigateActionMapper
import com.bydmate.app.data.automation.VoiceFireResult
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.agent.ActionConfirmer
import com.bydmate.app.agent.ActionOutcome
import com.bydmate.app.agent.ConfirmResult
import com.bydmate.app.ui.overlay.ListeningOverlay
import com.bydmate.app.util.AppStrings
import com.bydmate.app.util.appLocalizedContext
import com.bydmate.app.voice.openclaw.AssistantActionHandler
import com.bydmate.app.voice.openclaw.AssistantActionResult
import com.bydmate.app.voice.openclaw.NoAssistantActions
import com.bydmate.app.voice.openclaw.PolishActionText
import com.bydmate.app.voice.openclaw.ProposalInput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Singleton
@Suppress("LargeClass") // the whole voice session: capture marks, ASR events, routing, agent turns
class VoiceController @Inject @Suppress("LongParameterList") constructor( // Hilt-injected dependencies
    private val audioCapture: AudioCapture,
    private val actionDispatcher: ActionDispatcher,
    private val earcon: VoiceEarcon,
    private val gate: VoiceGate,
    private val automationEngine: AutomationEngine,
    private val automationResolver: VoiceAutomationResolver,
    private val agentOrchestrator: AgentOrchestrator,
    @ApplicationContext private val context: Context,
    private val ttsEngine: TtsEngine,
    private val journal: VoiceJournal,
    private val continuousAsr: ContinuousAsr,
    private val agentIdentity: () -> AgentIdentity,
    private val appStrings: AppStrings,
    /** The one door a matched saved alias runs through (validates, then the shared executor). */
    private val aliasCoordinator: VoiceAliasCoordinator,
    private val echoFilter: SelfEchoFilter = SelfEchoFilter(),
    private val userPhrases: VoiceUserPhrases = VoiceUserPhrases(),
    /** The profile one session runs under, read once when the session starts. */
    private val sessionProfile: () -> VoiceSessionProfile = { VoiceSessionProfile(VoiceLanguage.DEFAULT) },
    /** The Polish profile's only online path (text-only, separate opt-in). Never used by Russian. */
    private val assistant: VoiceAssistant = NoAssistant,
    /** Validates, confirms and runs what the assistant proposes; its lines are the only ones spoken for a proposal. */
    private val assistantActions: AssistantActionHandler = NoAssistantActions,
    /** The phone book of the offline call command; read only when a call phrase is heard. */
    private val contacts: VoiceContacts = NoVoiceContacts,
    /** Confirms a call whose name matched only loosely (the on-screen overlay in production). */
    private val confirmer: ActionConfirmer = NoCallConfirmer,
) {
    // Process-lifetime scope (@Singleton): intentionally never cancelled.
    private val scope = CoroutineScope(SupervisorJob())

    /** What the floating widget shows. The tracker is the only writer; the controller holds handles. */
    internal val statusTracker = VoiceStatusTracker(scope)
    val status: StateFlow<VoiceStatus> get() = statusTracker.status

    /** The running (or warming) session's widget handle. A handle is valid only while it is the
     *  tracker's current session, so a late write from an older session does nothing. */
    @Volatile private var statusSession: VoiceStatusTracker.Session? = null

    /** The widget handle of one utterance's turn travels in the routing coroutine's context, never
     *  in a shared field: [turn] below is global, a late callback of a cancelled turn would read the
     *  next turn's handle from it. */
    private class StatusTurnElement(@Volatile var turn: VoiceStatusTracker.Turn?) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<StatusTurnElement>
    }

    private suspend fun statusTurn(): VoiceStatusTracker.Turn? = currentCoroutineContext()[StatusTurnElement]?.turn

    private val _state = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    // True only after the open capture produces audio, until that capture is released.
    // The widget uses this to draw its listening indicator.
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val busy = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val captureIdle = MutableStateFlow(true)
    private val speechIdle = MutableStateFlow(true)
    private var listeningIntent = false
    private var explicitCapturePending = false
    private var intentGeneration = 0L
    private var intentCause = 0L
    private val acceptedCount = AtomicInteger(0)
    private data class AcceptedRequest(
        val text: String, val decodeMs: Long, val profile: VoiceSessionProfile,
        val generation: Long, val traceId: Long, val captureClosed: CompletableDeferred<Unit>,
    )
    private val requests = Channel<AcceptedRequest>(Channel.UNLIMITED)
    private var activeStatus: StatusTurnElement? = null
    private var activeRequestText: String = ""
    private class RequestGeneration(val value: Long, val traceId: Long) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RequestGeneration>
    }

    init {
        scope.launch {
            for (request in requests) {
                request.captureClosed.await()
                language = request.profile.language
                sessionTraceId = request.traceId
                val holder = synchronized(lifecycleLock) {
                    val widgetTurn = if (captureIdle.value) {
                        val widget = statusTracker.openSession(language, preparing = true)
                        statusSession = widget
                        widget.openTurn().also { it.processing(request.text) }
                    } else null
                    StatusTurnElement(widgetTurn).also {
                        activeStatus = it
                        activeRequestText = request.text
                    }
                }
                val context = RequestGeneration(request.generation, request.traceId) + holder
                val job = scope.launch(context, start = CoroutineStart.LAZY) {
                    try {
                        routeUtterance(request.text, request.decodeMs)
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        earcon.fail()
                        _state.value = VoiceUiState.NotUnderstood(request.text)
                        statusTurn()?.finish(VoiceStage.FAILED)
                        record(VoiceJournalEntry(transcript = request.text, route = VoiceJournalEntry.Route.REFUSED,
                            detail = withDecodeMs(request.text, request.decodeMs), outcome = VoiceJournalEntry.Outcome.ERROR,
                            reason = t.message, refusal = VoiceRefusal.INTERNAL_ERROR, asrMs = request.decodeMs),
                            "Continuous session utterance failed: ${t.message}")
                        announce("Głos", lines.internalError(t.message), "Błąd")
                    }
                }
                routingJob = job
                job.start()
                job.join()
                synchronized(lifecycleLock) {
                    if (activeStatus === holder) activeStatus = null
                    routingJob = null
                }
                if (acceptedCount.decrementAndGet() == 0) {
                    synchronized(lifecycleLock) {
                        if (!listeningIntent && captureIdle.value && acceptedCount.get() == 0) {
                            statusSession?.close(lingerTerminal = true)
                            statusSession = null
                            runCatching { hideListeningOverlay() }
                            scheduleIdleReset()
                        }
                    }
                    scheduleCaptureIfNeeded()
                }
            }
        }
    }


    /** The language of the running (or just starting) session, captured from [sessionProfile] when
     *  the session starts. Everything the session says, matches and routes follows it. */
    @Volatile private var language: VoiceLanguage = VoiceLanguage.DEFAULT
    private val polish: Boolean get() = language == VoiceLanguage.PL
    private val lines: VoiceSessionText get() = VoiceSessionText(language)

    @Volatile private var sessionJob: Job? = null
    @Volatile private var warmupJob: Job? = null
    @Volatile private var routingJob: Job? = null

    /** The utterance being routed: when routing started (dispatch latency in the journal) and,
     *  once no local resolver claimed it, why it went to the agent. Routing is sequential (one
     *  routingJob at a time), so a single slot is enough. [heardId] is its trace event, the
     *  cause of everything the turn does. */
    private data class Turn(val startedAtMs: Long = 0L, val agentReason: String? = null, val heardId: Long = 0L)
    @Volatile private var turn = Turn()

    // Trace event of the running continuous session, and why it is being stopped.
    @Volatile private var sessionTraceId = 0L
    @Volatile private var stopReason: String? = null

    /** Includes accepted work and playback so automation cannot take over an outstanding reply. */
    fun sessionActive(): Boolean = synchronized(lifecycleLock) { listeningIntent || busy.get() || acceptedCount.get() > 0 || !speechIdle.value }

    internal fun routingJobForTest(): Job? = routingJob
    internal fun warmupJobForTest(): Job? = warmupJob
    // Playback window of the continuous session: while our own TTS is audible and for
    // TTS_ECHO_GRACE_MS after it, the mic hears the agent's voice too, and the car has no working
    // echo cancellation. Frames captured in the window never reach the recognizer: the echo would
    // open a VAD segment, and a driver answering without a pause would be glued into it and lost
    // with it. Stamped two ways:
    // (1) at call time, right after ttsEngine.speak() returns true (announce()/agent answer) --
    // this is the floor, since we always know when we start our own TTS, and it also covers a
    // short utterance that starts AND ends entirely between two mic frames, which would otherwise
    // never be observed. speak() returning false (blank text, or engine not ready -- e.g. voice
    // not downloaded) means no audio was actually enqueued, so no stamp: a playback window for a
    // no-op would swallow the start of the user's next phrase for nothing.
    // (2) in the capture-time frame mark for every frame where ttsEngine.audible() reads
    // true, which extends the stamp across longer utterances (no collect()-based watcher --
    // collecting a StateFlow can conflate a fast true->false->true transition away, silently
    // missing a window; reading .value per frame always reflects that exact instant).
    // inPlaybackWindow() stays true for TTS_ECHO_GRACE_MS after this timestamp.
    // 0 outside a continuous session -- inPlaybackWindow() then always reports false, so the legacy
    // one-shot path (whose mic already closed before it speaks) is never affected.
    // internal (not private): lets tests await the exact moment either stamp fires as a
    // deterministic condition, instead of a fixed sleep -- no public API surface added.
    @Volatile internal var lastSpeakingSeenMs: Long = 0L

    // Test seam: the clock of the playback window.
    internal var clock: () -> Long = System::currentTimeMillis

    // Session auto-close waiting for the reply to end (play_music, the close-after-command toggle,
    // the driver's own request); a name barge-in cancels it, a new phrase cancels all but play_music.
    // One pending close at a time: reason and job live in one holder.
    private class PendingClose(val reason: String, val job: Job)
    @Volatile private var pendingClose: PendingClose? = null

    // After a terminal state (Done/NotUnderstood/Blocked) the voice UI state auto-returns to Idle
    // so it never sticks (the "not recognized" red state used to stay forever). Kept short so it
    // feels instant. resetGen invalidates a pending reset once a newer session owns the state
    // machine; the busy check stops a stale reset from firing mid-session.
    @Volatile private var resetJob: Job? = null
    private val resetGen = AtomicInteger(0)
    // Test seam — overridable so unit tests need not wait the full delay.
    internal var idleResetDelayMs: Long = IDLE_RESET_DELAY_MS

    // Wave D2: the orb dialog block ("You: …" / "Assistant: …") is cleared this long after the spoken
    // answer finishes (or after it is shown, if TTS is off). Overridable so unit tests need not wait
    // the full 6s -- mirrors idleResetDelayMs above.
    internal var dialogClearDelayMs: Long = DIALOG_CLEAR_MS

    // Pending orb-dialog clear scheduled after a terminal answer; cancelled by a new utterance so the
    // block never collapses mid-conversation. Only ever touched from scheduleClear/cancelScheduledClear.
    @Volatile private var clearJob: Job? = null

    // Test seams — real impl shows/updates/hides the persistent "Listening"/"Thinking" pill for the
    // whole continuous-session lifetime (Wave B indication).
    internal var showListeningOverlay: suspend (String) -> Unit = { text -> ListeningOverlay.show(context, text) }
    internal var updateListeningOverlay: (String) -> Unit = { text -> ListeningOverlay.update(text) }
    internal var hideListeningOverlay: () -> Unit = { ListeningOverlay.hide() }

    // Test seams — the running orb dialog block (Wave D2): the recognized user phrase ("You: …"),
    // the agent/command answer ("Assistant: …"), and clearing both. All three are no-op-safe when the
    // orb is not shown (they only mutate state the dialog window observes). Mirror the overlay seams.
    internal var showHeardHook: (String) -> Unit = { text -> ListeningOverlay.showHeard(text) }
    internal var showAnswerHook: (String) -> Unit = { text -> ListeningOverlay.showAnswer(text) }
    internal var clearDialogHook: () -> Unit = { ListeningOverlay.clearDialog() }

    /** Reserves playback before enqueueing so a capture cannot start between enqueue and drain. */
    private suspend fun speakOwned(text: String, sentences: List<String> = emptyList()): Boolean {
        while (true) {
            captureIdle.first { it }
            speechIdle.first { it }
            val reserved = synchronized(lifecycleLock) {
                if (captureIdle.value && speechIdle.value) { speechIdle.value = false; true } else false
            }
            if (reserved) break
        }
        val lease = runCatching { audioCapture.acquireSuppression(AudioCapture.DUCK_VOLUME_INDEX) }.getOrNull()
        val status = statusSession
        val speakingObserver = scope.launch { ttsEngine.speaking.collect { status?.setSpeaking(it) } }
        try {
            return speakQueued(text, sentences) ?: speakDirect(text)
        } catch (t: Throwable) {
            runCatching { ttsEngine.stop() }
            if (t is CancellationException) throw t
            Log.w(TAG, "Reply playback failed; the settled request outcome is unchanged", t)
            return false
        } finally {
            speakingObserver.cancel()
            status?.setSpeaking(false)
            runCatching { lease?.close() }
            val resumeExplicit = synchronized(lifecycleLock) {
                speechIdle.value = true
                listeningIntent && explicitCapturePending
            }
            if (resumeExplicit) scheduleCaptureIfNeeded(explicit = true)
        }
    }

    /** Null means no queued speech was accepted, so the plain engine path may be tried. */
    internal suspend fun speakQueued(text: String, sentences: List<String>): Boolean? {
        val queue = runCatching { ttsEngine.startQueue(language) }.getOrNull() ?: return null
        var queued = false
        var rejected = false
        for (sentence in sentences.ifEmpty { listOf(text) }) {
            if (!runCatching { queue.enqueue(sentence) }.getOrDefault(false)) {
                rejected = true
                break
            }
            queued = true
        }
        val finished = runCatching { queue.finish() }.isSuccess
        if (rejected || !finished) {
            runCatching { ttsEngine.stop() }
            return if (queued) false else null
        }
        if (!queued) return null
        noteOwnSpeech(text)
        if (withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) { queue.awaitPlayed(); true } == null) {
            ttsEngine.stop()
            return false
        }
        return true
    }

    private suspend fun speakDirect(text: String): Boolean {
        if (!runCatching { ttsEngine.speak(text, language) }.getOrDefault(false)) return false
        noteOwnSpeech(text)
        val completed = withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) {
            val started = withTimeoutOrNull(SPEAK_START_GRACE_MS) { ttsEngine.speaking.first { it }; true }
            if (started != true) return@withTimeoutOrNull false
            ttsEngine.speaking.first { !it }
            true
        }
        if (completed != true) ttsEngine.stop()
        return completed == true
    }

    internal fun tryBeginAutomationSpeech(): Boolean = synchronized(lifecycleLock) {
        if (listeningIntent || busy.get()) return@synchronized false
        if (acceptedCount.get() > 0 || !speechIdle.value) return@synchronized false
        speechIdle.value = false
        true
    }

    internal fun endAutomationSpeech() {
        synchronized(lifecycleLock) { speechIdle.value = true }
        scheduleCaptureIfNeeded(explicit = true)
    }

    /** Speak a short phrase for every session outcome (not only agent answers).
     *  Overlay text stays detailed; the spoken phrase is terse for the road. */
    private suspend fun announce(title: String, overlay: String, spoken: String, done: Boolean = false) {
        if (!currentCoroutineContext().isActive) return
        var didSpeak = false
        if (gate.ttsEnabled()) {
            // Stamp at the moment we ourselves start TTS, not only from the mic filter's
            // per-frame read of ttsEngine.speaking: a short utterance that starts AND ends
            // entirely between two mic frames (see lastSpeakingSeenMs above) would otherwise
            // never get stamped at all, since speaking never reads true on any frame. Only
            // stamp when speak() actually enqueued playback -- see lastSpeakingSeenMs above.
            val identity = agentIdentity()
            val phrase = identity.persona.spokenPhrase(spoken, gender = identity.gender)
            if (speakOwned(phrase)) {
                didSpeak = true
            }
        }
        // Orb dialog: mirror the detailed overlay text into the "Assistant: …" row, then arm the clear.
        // Placed here so every announce() terminal (command outcomes + agent Disabled/Error) feeds the
        // orb; the agent Answer branch, which does not call announce(), does the same two calls itself.
        showAnswerHook(overlay)
        scheduleClear(overlay, didSpeak)
        if (done) closeAfterCommand(overlay, didSpeak)
    }

    /** Everything the agent hands to TTS: the echo filter's memory and the playback-window stamp
     *  (see [lastSpeakingSeenMs]). */
    private fun noteOwnSpeech(text: String) {
        echoFilter.noteSpoken(text)
        lastSpeakingSeenMs = clock()
    }

    private fun listeningText(): String =
        if (polish) PolishVoiceReplies.session(PolishVoiceReplies.Session.LISTENING) else appStrings.get(R.string.voice_listening)

    private fun thinkingText(): String =
        if (polish) PolishVoiceReplies.session(PolishVoiceReplies.Session.THINKING) else appStrings.get(R.string.voice_thinking)

    private fun speedUnknownText(): String =
        PolishVoiceReplies.problem(PolishVoiceReplies.Problem.SPEED_UNKNOWN)

    /** Records one journal entry + matching logcat lines for a terminal voice-session outcome.
     *  The Chinese command string (if any) belongs only in [logMsg], never in the entry — its
     *  fields stay user-displayable for the debug journal screen. An entry with [asrMs] belongs
     *  to a routed utterance and gets its dispatch latency stamped here. */
    private fun record(entry: VoiceJournalEntry, logMsg: String) {
        val startedAt = turn.startedAtMs
        val e = if (entry.asrMs != null && startedAt > 0L) entry.copy(dispatchMs = entry.timestampMs - startedAt) else entry
        journal.add(e)
        Log.i(TAG, logLine(e))
        Log.i(TAG, logDetail(logMsg))
        Trace.event(TraceArea.VOICE, "result", "route" to e.route.code, "outcome" to e.outcome, "reason" to e.refusal,
            by = if (e.asrMs != null) turn.heardId else null)
    }

    /** Toggle listening intent without cancelling accepted requests or their replies. */
    fun onPttPressed() {
        if (!gate.isEnabled()) return
        val ptt = Trace.event(TraceArea.USER, "ptt", "listening" to _listening.value)
        val generation = synchronized(lifecycleLock) {
            intentCause = ptt
            intentGeneration++
            listeningIntent = !listeningIntent
            explicitCapturePending = listeningIntent
            if (!listeningIntent) {
                warmupJob?.cancel()
                sessionJob?.cancel()
            }
            intentGeneration
        }
        if (synchronized(lifecycleLock) { listeningIntent }) scheduleCaptureIfNeeded(explicit = true, generation = generation)
    }

    /** A newer explicit toggle invalidates every pending warm-up and delayed capture start. */
    private fun scheduleCaptureIfNeeded(explicit: Boolean = false, generation: Long = synchronized(lifecycleLock) { intentGeneration }) {
        scope.launch {
            captureIdle.first { it }
            speechIdle.first { it }
            ttsEngine.speaking.first { !it }
            synchronized(lifecycleLock) {
                if (!canStartCapture(explicit, generation)) return@synchronized
                val profile = sessionProfile()
                when {
                    !continuousAsr.isReady(profile) -> reportMissingModel(profile)
                    continuousAsr.requiresWarmBeforeListening(profile) && !continuousAsr.isWarm(profile) ->
                        warmBeforeCapture(profile, generation)
                    else -> startContinuousSession(intentCause, profile, generation)
                }
            }
        }
    }

    /** Called with lifecycleLock held. */
    private fun canStartCapture(explicit: Boolean, generation: Long): Boolean {
        if (!listeningIntent || generation != intentGeneration) return false
        if (!captureIdle.value || !speechIdle.value || warmupJob?.isActive == true) return false
        return explicit || acceptedCount.get() == 0
    }

    private fun reportMissingModel(profile: VoiceSessionProfile) {
        listeningIntent = false
        explicitCapturePending = false
        language = profile.language
        val msg = if (polish) PolishVoiceReplies.problem(PolishVoiceReplies.Problem.MODEL_MISSING)
            else appStrings.get(R.string.voice_error_model_missing)
        _state.value = VoiceUiState.NotUnderstood("")
        statusTracker.showFailure(profile.language)
        record(VoiceJournalEntry(transcript = "", route = VoiceJournalEntry.Route.REFUSED, detail = "",
            outcome = VoiceJournalEntry.Outcome.ERROR, reason = msg, refusal = VoiceRefusal.MODEL_MISSING),
            "GigaAM model not ready")
        scheduleIdleReset()
        scope.launch { announce("Głos", msg, msg) }
    }

    private fun warmBeforeCapture(profile: VoiceSessionProfile, generation: Long) {
        val preparing = statusTracker.openSession(profile.language, preparing = true)
        statusSession = preparing
        warmupJob = scope.launch(Dispatchers.IO) {
            try {
                try {
                    continuousAsr.warmUp(profile)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(TAG, "ASR warm-up failed; capture will report a failure if it cannot start", t)
                }
                synchronized(lifecycleLock) {
                    if (canFinishWarmup(generation)) startContinuousSession(intentCause, profile, generation)
                }
            } finally {
                synchronized(lifecycleLock) {
                    warmupJob = null
                    if (sessionJob == null) closePreparing(preparing)
                }
                if (synchronized(lifecycleLock) { listeningIntent && generation != intentGeneration }) {
                    scheduleCaptureIfNeeded(explicit = true)
                }
            }
        }
    }

    private fun canFinishWarmup(generation: Long): Boolean {
        if (!listeningIntent || intentGeneration != generation) return false
        return captureIdle.value && speechIdle.value
    }

    private fun closePreparing(preparing: VoiceStatusTracker.Session) {
        preparing.close(lingerTerminal = false)
        if (statusSession === preparing) statusSession = null
    }

    /** One capture window ends after an accepted utterance or its silence timeout.
     *  Accepted work outlives this window; current listening intent controls later capture. */
    private fun startContinuousSession(cause: Long, profile: VoiceSessionProfile, generation: Long): Boolean {
        if (!busy.compareAndSet(false, true)) {
            Trace.event(TraceArea.VOICE, "ptt-ignored", "reason" to "busy", by = cause)
            return false
        }
        // The profile was captured at the PTT edge. Do not read the active profile again after
        // an asynchronous warm-up: a settings switch must either reject this stale start in the
        // profile-aware ASR or keep every session concern on this one profile.
        if (acceptedCount.get() == 0) language = profile.language
        if (!continuousAsr.isReady(profile)) {
            busy.set(false)
            return false
        }
        explicitCapturePending = false
        DiagnosticEmitters.lifecycle("preparing")
        val traceId = Trace.event(TraceArea.VOICE, "session-start", by = cause)
        sessionTraceId = traceId
        stopReason = null
        lastSpeakingSeenMs = 0L
        captureIdle.value = false
        // LISTENING means that capture has produced a frame, not merely that preparation started.
        val widget = statusTracker.openSession(profile.language, preparing = true)
        statusSession = widget
        val earlyDuck = runCatching { audioCapture.acquireSuppression(0) }.getOrNull()
        // Warm the model and online-voice connections while the driver is still speaking: a cold
        // turn otherwise pays DNS + TLS on both hosts inside the reply latency.
        runCatching { if (gate.ttsEnabled()) ttsEngine.prewarmNetwork() }
        // The Russian agent's provider connection is warmed only for a Russian session: a Polish
        // session never talks to it.
        val closed = CompletableDeferred<Unit>()
        val job = scope.launch(start = CoroutineStart.LAZY) { ContinuousSession(widget, earlyDuck, traceId, profile, generation, closed).run() }
        sessionJob = job
        job.invokeOnCompletion {
            synchronized(lifecycleLock) {
                if (!closed.isCompleted) {
                    runCatching { earlyDuck?.close() }
                    if (statusSession === widget) statusSession = null
                    widget.close(lingerTerminal = false)
                    _listening.value = false
                    sessionJob = null
                    busy.set(false)
                    captureIdle.value = true
                    closed.complete(Unit)
                }
            }
        }
        job.start()
        return true
    }

    /** State local to one capture/ASR collection; frame state is used only by capture. */
    private inner class ContinuousSession(
        private val widget: VoiceStatusTracker.Session,
        private val earlyDuck: MediaSuppressionLease?,
        private val traceId: Long,
        private val profile: VoiceSessionProfile,
        private val generation: Long,
        private val closed: CompletableDeferred<Unit>,
    ) {
        private var lastEventMs = 0L
        private var wasAudible = false   // capture thread only
        private var micGated = false     // capture thread only
        private val echoTail = EchoTailMeter() // capture thread only
        // Silence is measured only within this capture window.
        private var silentMs = 0L
        private var lastTickSilentMs = 0L

        suspend fun run() {
            runCatching { showListeningOverlay(thinkingText()) }
            lastEventMs = System.currentTimeMillis()
            try {
                runCatching {
                    // Wave P: no session cap; silence auto-stop below is the only auto-exit.
                    // The mark runs on the capture thread when the frame is read, not when it is
                    // processed: frames pile up in the capture channel while GigaAM decodes, and a
                    // frame the agent's voice was captured in must stay gated however late it comes.
                    val pcm = audioCapture.captureSession(maxMs = Long.MAX_VALUE) { samples ->
                        gateFrame(samples)
                    }
                        .filter { frame ->
                            !frame.mark
                        }
                        .map { it.pcm }
                    continuousAsr.transcribe(profile, pcm).collect { ev ->
                        onEvent(ev)
                    }
                }.onFailure { failure ->
                    when (failure) {
                        is StopSession -> synchronized(lifecycleLock) {
                            if (intentGeneration == generation) { listeningIntent = false; explicitCapturePending = false; intentGeneration++ }
                            stopReason = "silence"
                        }
                        is StopAccepted -> Unit
                        is CancellationException -> throw failure
                        else -> failed(failure)
                    }
                }
            } finally {
                finish()
            }
        }

        private fun gateFrame(samples: ShortArray): Boolean {
            synchronized(lifecycleLock) {
                if (!_listening.value && ownsCapture()) {
                    _listening.value = true
                    _state.value = VoiceUiState.Listening
                    widget.listening()
                    earcon.ok()
                    runCatching { updateListeningOverlay(listeningText()) }
                }
            }
            // Read the real, physical playback signal each frame (see
            // lastSpeakingSeenMs above for why not a collect()-based watcher):
            // audible(), not the logical speaking flag, which is deliberately held
            // true across a whole streamed reply (including silent tool rounds) so
            // this gate would otherwise deafen the mic while the agent "thinks".
            val audible = ttsEngine.audible()
            val now = clock()
            if (audible) lastSpeakingSeenMs = now
            else if (wasAudible) {
                // Transition from audible to silent: notify echo filter
                echoFilter.onPlaybackEnd()
            }
            if (audible != wasAudible) Trace.event(TraceArea.VOICE, if (audible) "tts-start" else "tts-end")
            // The widget's `speaking` is sound out of the speaker: the frame's own audible() read,
            // not queued speech and not the logical flag (held true across a silent tool round).
            // No collector and no extra read, so the mic gate sees exactly what it always did.
            if (audible != wasAudible) widget.setSpeaking(audible)
            wasAudible = audible
            val gated = inPlaybackWindow(now, lastSpeakingSeenMs + TTS_ECHO_GRACE_MS, audible)
            if (gated != micGated) {
                micGated = gated
                Log.i(TAG, if (gated) "mic gated: playback" else "mic open: playback ended +${now - lastSpeakingSeenMs}ms")
                if (gated) Trace.event(TraceArea.VOICE, "mic-gated")
                else Trace.event(TraceArea.VOICE, "mic-open", "after_ms" to now - lastSpeakingSeenMs)
            }
            echoTail.onFrame(now, audible, samples)?.let {
                Log.i(TAG, it)
                Trace.event(TraceArea.VOICE, "echo-tail", "result" to it.removePrefix("echo tail: "))
            }
            return gated
        }

        /** Called with lifecycleLock held. */
        private fun ownsCapture(): Boolean =
            generation == intentGeneration && listeningIntent && !closed.isCompleted

        private suspend fun onEvent(ev: ContinuousAsrEvent) {
            if (synchronized(lifecycleLock) { !ownsCapture() || !_listening.value }) return
            when (ev) {
                is ContinuousAsrEvent.SpeechStart -> {
                    lastEventMs = System.currentTimeMillis()
                    lastTickSilentMs = 0L
                    silentMs = 0L
                    _state.value = VoiceUiState.Listening
                }
                is ContinuousAsrEvent.SilenceTick -> {
                    lastEventMs = System.currentTimeMillis()
                    val tickMs = if (ev.silentMs >= lastTickSilentMs) ev.silentMs - lastTickSilentMs else ev.silentMs
                    lastTickSilentMs = ev.silentMs
                    silentMs += tickMs
                    if (silentMs >= SILENCE_AUTOSTOP_MS) throw StopSession
                }
                is ContinuousAsrEvent.Utterance -> {
                    onUtterance(ev)
                }
            }
        }

        private suspend fun onUtterance(ev: ContinuousAsrEvent.Utterance) {
            val decodeMs = System.currentTimeMillis() - lastEventMs
            synchronized(lifecycleLock) {
                if (generation != intentGeneration || !listeningIntent || closed.isCompleted) return
                stopReason = "accepted"
                acceptedCount.incrementAndGet()
                requests.trySend(AcceptedRequest(ev.text, decodeMs, profile, generation, traceId, closed))
            }
            throw StopAccepted
        }

        private suspend fun failed(t: Throwable) {
            DiagnosticEmitters.voiceFailure("failed", t)
            stopReason = "error:${t.javaClass.simpleName}"
            statusSession?.openTurn()?.finish(VoiceStage.FAILED)
            Log.w(TAG, "Continuous session failed: ${t.message}")
            record(VoiceJournalEntry(transcript = "", route = VoiceJournalEntry.Route.REFUSED, detail = "",
                outcome = VoiceJournalEntry.Outcome.ERROR, reason = t.message, refusal = VoiceRefusal.ASR_FAILED),
                "Continuous session failed: ${t.message}")
            synchronized(lifecycleLock) {
                if (intentGeneration == generation) { listeningIntent = false; explicitCapturePending = false; intentGeneration++ }
            }
            scope.launch {
                closed.await()
                announce("Głos", lines.internalError(t.message), "Błąd")
            }
        }

        private fun finish() {
            synchronized(lifecycleLock) {
                val ending = widget
                if (statusSession === ending) statusSession = null
                ending.close(lingerTerminal = false)
                runCatching { earlyDuck?.close() }
                _listening.value = false
                sessionJob = null
                busy.set(false)
                if (acceptedCount.get() > 0) runCatching { updateListeningOverlay(thinkingText()) }
                captureIdle.value = true
                val holder = activeStatus
                if (holder != null && acceptedCount.get() > 0) {
                    val pending = statusTracker.openSession(language, preparing = true)
                    statusSession = pending
                    holder.turn = pending.openTurn().also {
                        it.processing(activeRequestText)
                        it.waitingAssistant()
                    }
                }
                closed.complete(Unit)
                DiagnosticEmitters.lifecycle("stopped")
                Trace.event(TraceArea.VOICE, "session-stop", "reason" to (stopReason ?: "cancel"), by = traceId)
                if (!listeningIntent && acceptedCount.get() == 0) {
                    _state.value = VoiceUiState.Idle
                    earcon.off()
                    runCatching { hideListeningOverlay() }
                }
            }
        }
    }

    /** Close only the capture owned by the current listening intent. */
    private fun stopContinuousSession(reason: String) {
        synchronized(lifecycleLock) {
            stopReason = reason
            listeningIntent = false
            explicitCapturePending = false
            intentGeneration++
            warmupJob?.cancel()
            sessionJob?.cancel()
        }
    }

    /** Routes one continuous-session utterance through the exact same NLU-resolve -> apply /
     *  agentFallback path the legacy final-transcript routing uses below (VoiceJournal writes,
     *  noteAction, announce — all unchanged). decodeMs is also logged for RTF measurement on
     *  the car, and threaded into the VoiceJournal detail for each utterance. */
    private suspend fun routeUtterance(transcript: String, decodeMs: Long) {
        // Orb dialog: show "You: <phrase>" (clearing any prior answer) and cancel a pending clear so a
        // fresh turn keeps the block visible. The pill has already flipped to "Thinking" at the call site.
        turn = Turn(startedAtMs = System.currentTimeMillis())
        showHeardHook(transcript)
        val command = AgentNameMatcher.stripLeadingName(transcript, agentIdentity().name)
        cancelScheduledClear()
        Log.i(TAG, "Continuous session utterance: decodeMs=$decodeMs")

        // Echo filter: only check if the transcript had no agent name prefix (if it did have the name,
        // stripLeadingName would have returned something different). Name-gated barge-in always passes.
        // A silent drop: no earcon, no state change, no spoken "Nie rozumiem" -- speaking that phrase
        // would itself be noteSpoken'd and risk being echo-caught again, looping the agent's own voice.
        if (transcript == command && echoFilter.isEcho(transcript)) {
            heard(decodeMs, "echo")
            record(VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.REFUSED,
                detail = withDecodeMs(transcript, decodeMs), outcome = VoiceJournalEntry.Outcome.NOT_UNDERSTOOD,
                reason = "Echo własnej wypowiedzi", refusal = VoiceRefusal.ECHO, asrMs = decodeMs),
                "Echo filtered: transcript=\"$transcript\"")
            return
        }
        // The driver goes on talking: a pending close stays off. After the echo check, or our own
        // voice heard back would cancel it; the play_music close is left as it always was.
        pendingClose?.takeIf { it.job.isActive && it.reason != REASON_PLAY_MUSIC }?.let {
            it.job.cancel()
            Log.i(TAG, "${it.reason} auto-close cancelled by a new phrase")
        }
        // After the echo drop and the busy drop, which are silent: only a phrase that is routed shows.
        statusTurn()?.processing(transcript)

        // An unanswered clarifying question from the agent outranks every local resolver: the
        // phrase is the ANSWER ("kierowcy", "zamknij wszystkie okna" to "co wykonać?") and must reach
        // ask() verbatim. A question the agent asked after acting is not a clarification, see
        // AgentOrchestrator.expectsFollowUp.
        // Only the Russian agent has a clarifying-question window. A Polish phrase is always
        // resolved locally first: the assistant never gets to claim a command or a question.
        // Voice sessions no longer enter the retired agent follow-up mode. Manual chat and
        // saved agent_query automations retain their own AgentOrchestrator paths.
        val followUp = false
        val res = if (followUp) Resolution.None(VoiceRefusal.AGENT_FOLLOWUP_WINDOW) else resolve(command)
        heard(decodeMs, res.route, (res as? Resolution.None)?.reason)
        _state.value = VoiceUiState.Thinking
        if (res is Resolution.None) {
            turn = turn.copy(agentReason = res.reason)
            agentFallback(command, decodeMs)
        } else {
            apply(res, command, decodeMs)
        }
    }

    /** Trace event of the phrase: where it went (never what was said); the cause of the turn. */
    private suspend fun heard(decodeMs: Long, route: String, why: String? = null) {
        val id = Trace.event(TraceArea.VOICE, "heard", "route" to route, "why" to why, "asr_ms" to decodeMs,
            by = currentCoroutineContext()[RequestGeneration]?.traceId ?: sessionTraceId)
        turn = turn.copy(heardId = id)
    }

    /** Appends the continuous-session decode latency to a journal detail string; a no-op
     *  (returns [text] unchanged) for the legacy one-shot path, which has no per-utterance
     *  decode timing. */
    private fun withDecodeMs(text: String, decodeMs: Long?): String =
        if (decodeMs != null) "$text decodeMs=$decodeMs" else text

    /** Side-effect-free resolution of a transcript to an actionable command. Polish negated,
     *  quoted and partial requests are rejected before saved phrases. Remaining precedence
     *  (the Russian agent follow-up window, checked by the caller, outranks these):
     *  1. an automation phrase equal to the whole utterance, word for word after normalization;
     *  2. the user's own saved alias (any kind), equal to the whole utterance; one this build cannot
     *     run is refused locally and never reaches the assistant;
     *  3. the built-in NluParser on the full utterance;
     *  4. the agent (Resolution.None, with the parser's reason).
     *  A user-made phrase goes first so the user can take over a phrase the parser gets wrong;
     *  it never fires from inside a longer utterance, so «otwórz okno» never swallows
     *  «otwórz okno do połowy», «nie otwieraj okna» or «otwórz okno trochę». */
    private suspend fun resolve(text: String): Resolution {
        // These Polish safety verdicts cannot be overridden by saved phrases. Other verdicts
        // still allow an exact user override before the built-in grammar is applied.
        if (polish) {
            val verdict = NluParser.classify(text, VoiceLanguage.PL)
            if (verdict is NluVerdict.Refused &&
                verdict.reason in setOf(NluRefusal.NEGATED, NluRefusal.QUOTED, NluRefusal.PARTIAL)
            ) return Resolution.Reject(verdict.reason)
        }
        // Saved custom phrases are matched exactly as typed (the default normalization, never the
        // Polish grammar's): no translation, no extra fillers, so nothing is broadened.
        automationResolver.match(text)?.let { return Resolution.Auto(it) }
        userPhrases.matchAlias(text)?.let { alias ->
            return if (alias.executable) Resolution.Alias(alias) else Resolution.Reject(NluRefusal.UNSUPPORTED)
        }
        return parse(text)
    }

    private fun parse(text: String): Resolution = when (val o = NluOutcome.of(NluParser.classify(text, language))) {
        is NluOutcome.Refused -> Resolution.None(o.reason)
        // Looks like a request to the car and nothing runs: answered here, never sent anywhere. The one
        // exception is UNSUPPORTED on the Polish profile with the assistant opted in: it goes to the
        // assistant, whose proposals are validated and confirmed before anything runs. Negated, quoted,
        // ambiguous, partial and incomplete-navigation refusals never leave the device.
        is NluOutcome.Rejected ->
            if (o.reason == NluRefusal.UNSUPPORTED && polish && assistant.isEnabled()) Resolution.None(o.reason)
            else Resolution.Reject(o.reason)
        is NluOutcome.Understood -> resolutionOf(o.result)
    }

    private fun resolutionOf(r: ParseResult): Resolution = when (r) {
        is ParseResult.Command -> Resolution.Cmd(r.commands, VoiceCommandLabels.of(r.commands))
        is ParseResult.RelativeTemp -> Resolution.RelTemp(r.delta)
        is ParseResult.Volume -> Resolution.Vol(r.payload)
        ParseResult.Music -> Resolution.Music
        ParseResult.NavigateHome -> Resolution.Navigate(NAVIGATE_HOME)
        ParseResult.NavigateWork -> Resolution.Navigate(NAVIGATE_WORK)
        is ParseResult.NavigateDestination -> when (val checked = NavDestinationRules.check(r.destination)) {
            is NavDestinationCheck.Valid -> Resolution.NavigateDestination(
                NavigateActionMapper.toActionDef(checked, go = true),
            )
            NavDestinationCheck.Invalid -> Resolution.Reject(NluRefusal.UNSUPPORTED)
        }
        is ParseResult.CallContact -> Resolution.Call(r.name)
        // A value the car does not report goes to the agent, never a made-up answer; so does
        // every question while the snapshot is not live: it is never cleared on transport
        // loss, and the range, kept elsewhere, would be just as old.
        is ParseResult.Ask -> askResolution(r.question)
        ParseResult.Unrecognized -> Resolution.None(VoiceRefusal.UNRECOGNIZED)
    }

    private fun askResolution(question: VehicleQuestion): Resolution =
        question.takeIf { gate.snapshotAgeMs()?.let { it <= ASK_MAX_SNAPSHOT_AGE_MS } == true }
            ?.answer(gate.vehicleSnapshot(), gate.rangeKm())
            ?.let { Resolution.Answer(question, it) }
            ?: Resolution.Unavailable(question, PolishVoiceReplies.unavailable(question))

    /** Execute a resolved command and set the terminal voice UI state. decodeMs is null for the
     *  legacy one-shot path (no per-utterance decode timing there). */
    private suspend fun apply(res: Resolution, transcript: String, decodeMs: Long? = null) {
        when (res) {
            is Resolution.Cmd -> execute(res.commands, transcript, decodeMs, res.label)
            is Resolution.Alias -> runAlias(res.alias, transcript, decodeMs)
            is Resolution.RelTemp -> dispatchRelativeTemp(res.delta, transcript, decodeMs)
            is Resolution.Vol -> dispatchVolume(res.payload, transcript, decodeMs)
            Resolution.Music -> dispatchMusic(transcript, decodeMs)
            is Resolution.Navigate -> dispatchNavigate(res.shortcut, transcript, decodeMs)
            is Resolution.NavigateDestination -> dispatchNavigateDestination(res.action, transcript, decodeMs)
            is Resolution.Call -> dispatchCall(res.name, transcript, decodeMs)
            is Resolution.Answer -> answer(res, transcript, decodeMs)
            is Resolution.Auto -> fireAutomation(res.match, transcript, decodeMs)
            is Resolution.Reject -> rejectLocally(res.reason, transcript, decodeMs)
            is Resolution.Unavailable -> unavailable(res, transcript, decodeMs)
            is Resolution.None -> agentFallback(transcript, decodeMs)
        }
    }

    /** Schedule the voice UI state's fall-back to Idle after a terminal state. Cancels any prior pending
     *  reset; the generation token + busy check guarantee a newer session's state is never
     *  clobbered (a reset only fires if its generation is still current and no session is active). */
    private fun scheduleIdleReset() {
        val gen = resetGen.incrementAndGet()
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(idleResetDelayMs)
            if (resetGen.get() == gen && !sessionActive()) {
                _state.value = VoiceUiState.Idle
            }
        }
    }

    /** Arms the orb-dialog clear after a terminal answer: waits out the spoken answer (immediate if
     *  TTS is off/idle, since speaking is already false), then hides the dialog block after a dwell.
     *  Cancels any previously armed clear so only the latest answer's timer runs.
     *
     *  A driver who only hears the answer needs the fixed dwell after it is spoken; a driver who
     *  has to READ it needs time proportional to its length, so an unspoken long answer stays up
     *  until it can be read (bounded by [DIALOG_READ_MAX_MS] — the orb must not sit there forever). */
    private fun scheduleClear(text: String, spoken: Boolean) {
        clearJob?.cancel()
        clearJob = scope.launch {
            ttsEngine.speaking.first { !it }   // wait out the spoken answer (immediate if TTS off)
            delay(readingDwellMs(text, spoken))
            clearDialogHook()
        }
    }

    /** How long the dialog block stays after the answer is on screen. */
    private fun readingDwellMs(text: String, spoken: Boolean): Long =
        if (spoken) dialogClearDelayMs
        else (text.length * DIALOG_READ_MS_PER_CHAR).coerceIn(dialogClearDelayMs, DIALOG_READ_MAX_MS)

    /** Cancels a pending orb-dialog clear so a new utterance keeps the block on screen. */
    private fun cancelScheduledClear() {
        clearJob?.cancel()
    }

    /** Close-after-command toggle: a done command or a final agent answer closes the session once
     *  the reply is heard, or, when nothing was said aloud, once the text had time to be read.
     *  The gate read is guarded: a failed settings read keeps the session open, as before. */
    private suspend fun closeAfterCommand(text: String, spoken: Boolean, reply: TtsEngine.SpeechQueue? = null) {
        if (!runCatching { gate.closeAfterCommand() }.getOrDefault(false)) return
        scheduleSessionClose("auto-close", spoken, readingDwellMs(text, spoken = false), reply)
    }

    private suspend fun scheduleSessionClose(
        reason: String,
        waitForSpeech: Boolean,
        dwellMs: Long = 0L,
        reply: TtsEngine.SpeechQueue? = null,
    ) {
        val generation = currentCoroutineContext()[RequestGeneration]?.value ?: return
        pendingClose?.job?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (reply != null && waitForSpeech) withTimeoutOrNull(PLAYBACK_TIMEOUT_MS) { reply.awaitPlayed() }
            if (!waitForSpeech) delay(dwellMs)
            synchronized(lifecycleLock) {
                if (intentGeneration == generation) stopContinuousSession(reason)
            }
        }
        pendingClose = PendingClose(reason, job)
        job.start()
    }

    private suspend fun execute(
        commands: List<String>,
        transcript: String,
        decodeMs: Long? = null,
        label: String = VoiceCommandLabels.of(commands),
    ) {
        val snapshot = gate.vehicleSnapshot()
        val cmdLog = commands.joinToString("+")
        // Fail CLOSED on unknown speed for window- and sunroof-open commands (both speed-gated
        // predicates after the T12 split): ActionDispatcher.getBlockReason() returns null
        // (no block) when data == null, so without this guard a voice "open the windows"/"open the sunroof"
        // at unknown speed would dispatch unchecked. Sunshade needs no speed and is not held.
        // Automations keep the fail-soft data==null semantics untouched.
        if (snapshot == null && commands.any {
                ActionDispatcher.isWindowOpenCommand(it) || ActionDispatcher.isSunroofOpenCommand(it)
            }) {
            earcon.fail()
            val reason = speedUnknownText()
            _state.value = VoiceUiState.Blocked(reason)
            statusTurn()?.finish(VoiceStage.REFUSED)
            record(nluEntry(transcript, decodeMs, label, VoiceJournalEntry.Outcome.BLOCKED)
                .copy(reason = reason, refusal = VoiceRefusal.gate("speed_unknown")), "NLU blocked (speed unknown): cmd=$cmdLog transcript=\"$transcript\"")
            announce("Głos", lines.refused(transcript, reason), "Nie udało się")
            return
        }
        // The live snapshot is passed through so ActionDispatcher's safety gates
        // (>80 km/h window-open block, frunk standstill block) apply to voice
        // commands exactly as they do to automations (voice-agent spec invariant).
        // DispatchResult.reason holds the error string (not .message — real field name is `reason`).
        // Plural seats resolve to several commands: dispatch in order, stop at the first
        // failure so the announce never claims success for a half-done utterance (#98).
        var failReason: String? = null
        statusTurn()?.running()
        for (command in commands) {
            val result = actionDispatcher.dispatch(
                ActionDef(command = command, displayName = command, kind = "param"),
                data = snapshot
            )
            if (!result.success) {
                failReason = result.reason ?: transcript
                break
            }
        }
        if (failReason == null) {
            earcon.ok()
            _state.value = VoiceUiState.Done(transcript)
            statusTurn()?.finish(VoiceStage.DONE)
            record(nluEntry(transcript, decodeMs, label, VoiceJournalEntry.Outcome.OK),
                "NLU dispatched: cmd=$cmdLog transcript=\"$transcript\"")
            announce("Głos", lines.done(transcript), "Gotowe", done = true)
            // Fire-and-forget: the note must never make the voice announce path wait on the
            // agent's mutex, which may be held by a concurrent ask().
        } else {
            earcon.fail()
            _state.value = VoiceUiState.Blocked(failReason)
            statusTurn()?.finish(VoiceStage.REFUSED)
            record(nluEntry(transcript, decodeMs, label, VoiceJournalEntry.Outcome.BLOCKED)
                .copy(reason = failReason, refusal = VoiceRefusal.DISPATCH_FAILED), "NLU blocked: cmd=$cmdLog transcript=\"$transcript\" reason=$failReason")
            announce("Głos", lines.refused(transcript, failReason), "Nie udało się")
        }
    }

    /** Runs a matched saved alias through the coordinator (validation, then the shared executor with the
     *  alias origin) and reports the outcome. Only constant or trusted localized text reaches the user
     *  or the journal: never an enum name, an error code or exception text. */
    // Cancellation propagates; unexpected alias failures become a fixed local code without exception data.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun runAlias(alias: VoiceAlias, transcript: String, decodeMs: Long?) {
        val label = aliasLabel(alias)
        suspend fun stopped(outcome: VoiceJournalEntry.Outcome, reason: String, refusal: String) {
            earcon.fail()
            _state.value = VoiceUiState.Blocked(reason)
            statusTurn()?.finish(VoiceStage.REFUSED)
            record(nluEntry(transcript, decodeMs, label, outcome).copy(reason = reason, refusal = refusal),
                "NLU alias stopped: cmd=$label outcome=$outcome transcript=\"$transcript\"")
            announce("Głos", lines.refused(transcript, reason), "Nie udało się")
        }
        statusTurn()?.running()
        val outcome = try {
            when (val run = aliasCoordinator.run(alias)) {
                is AliasRunResult.Executed -> run.outcome
                is AliasRunResult.Rejected, AliasRunResult.NotExecutable ->
                    return rejectLocally(NluRefusal.UNSUPPORTED, transcript, decodeMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionOutcome.Failed("alias_run_error")
        }
        when (outcome) {
            ActionOutcome.Done -> aliasDone(alias, transcript, decodeMs, label)
            is ActionOutcome.Failed -> stopped(VoiceJournalEntry.Outcome.ERROR,
                PolishActionText.FAILED, VoiceRefusal.DISPATCH_FAILED)
            is ActionOutcome.Blocked -> stopped(VoiceJournalEntry.Outcome.BLOCKED,
                PolishActionText.blocked(outcome.text), VoiceRefusal.gate("alias_blocked"))
            ActionOutcome.Declined -> stopped(VoiceJournalEntry.Outcome.BLOCKED,
                PolishActionText.DECLINED, VoiceRefusal.gate("confirm_declined"))
            ActionOutcome.TimedOut -> stopped(VoiceJournalEntry.Outcome.BLOCKED,
                PolishActionText.TIMED_OUT, VoiceRefusal.gate("confirm_timed_out"))
            ActionOutcome.ConfirmUnavailable -> stopped(VoiceJournalEntry.Outcome.BLOCKED,
                PolishActionText.NO_OVERLAY, VoiceRefusal.gate("confirm_unavailable"))
            ActionOutcome.Cancelled -> stopped(VoiceJournalEntry.Outcome.BLOCKED,
                PolishActionText.REJECTED, VoiceRefusal.gate("cancelled"))
        }
    }

    private suspend fun aliasDone(alias: VoiceAlias, transcript: String, decodeMs: Long?, label: String) {
        earcon.ok()
        _state.value = VoiceUiState.Done(transcript)
        statusTurn()?.finish(VoiceStage.DONE)
        record(nluEntry(transcript, decodeMs, label, VoiceJournalEntry.Outcome.OK),
            "NLU alias executed: cmd=$label transcript=\"$transcript\"")
        announce("Głos", lines.done(transcript), "Gotowe", done = true)
        if (alias.target is AliasTarget.NavigateQuery || alias.target is AliasTarget.NavigateShortcut) {
            scheduleSessionClose("navigate", waitForSpeech = true)
        }
    }

    /** Journal command of a saved alias: the built-in command id, or a constant for a destination. */
    private fun aliasLabel(alias: VoiceAlias): String = when (val t = alias.target) {
        is AliasTarget.Vehicle -> "phrase:${t.commandId}"
        is AliasTarget.NavigateQuery, is AliasTarget.NavigateShortcut -> "phrase:navigate"
        is AliasTarget.Unavailable -> "phrase:unavailable"
    }

    /** Relative temperature: read the AC setpoint, add [delta] degrees, clamp 16..33, then
     *  dispatch as an absolute set. Only a fresh snapshot (the age limit of a dictionary question)
     *  with a setpoint inside 16..33 counts: a missing, stale or out-of-range one refuses locally. */
    private suspend fun dispatchRelativeTemp(delta: Int, transcript: String, decodeMs: Long? = null) {
        val fresh = gate.snapshotAgeMs()?.let { it <= ASK_MAX_SNAPSHOT_AGE_MS } == true
        val acTemp = gate.vehicleSnapshot()?.acTemp?.takeIf { fresh && it in AC_TEMP_RANGE }
        if (acTemp == null) {
            earcon.fail()
            val reason = lines.acTempUnknown()
            _state.value = VoiceUiState.Blocked(reason)
            statusTurn()?.finish(VoiceStage.REFUSED)
            record(nluEntry(transcript, decodeMs, "ac_temp_step=$delta", VoiceJournalEntry.Outcome.BLOCKED)
                .copy(reason = reason, refusal = VoiceRefusal.gate("ac_temp_unknown")), "NLU blocked (acTemp unknown or stale): transcript=\"$transcript\"")
            announce("Głos", lines.refused(transcript, reason), "Nie udało się")
            return
        }
        val target = (acTemp + delta).coerceIn(AC_TEMP_RANGE)
        execute(listOf("设置温度$target"), transcript, decodeMs)
    }

    /** Media volume is not speed-gated (not a window op). Dispatch as a
     *  media_volume action; the dispatcher's AudioManager applies it. */
    private suspend fun dispatchVolume(payload: String, transcript: String, decodeMs: Long? = null) {
        dispatchMedia(ActionDef(command = "media_volume", displayName = transcript, kind = "media_volume", payload = payload),
            "media_volume=$payload", transcript, decodeMs)
    }

    /** Yandex Music's personal mix: the very action the agent's play_music dispatches with no
     *  query, and the same session close after the reply, or the orb would duck the music. */
    private suspend fun dispatchMusic(transcript: String, decodeMs: Long? = null) {
        val action = ActionDef(command = "", displayName = "Muzyka", kind = "yandex_music",
            payload = JSONObject().put("mode", "mybeat").toString())
        if (dispatchMedia(action, "yandex_music=mybeat", transcript, decodeMs)) {
            scheduleSessionClose(REASON_PLAY_MUSIC, waitForSpeech = true)
        }
    }

    /** Saved Home or Work in the selected navigation app: the same `navigate` shortcut action an
     *  automation or the agent runs, started at once ("go"). */
    private suspend fun dispatchNavigate(shortcut: String, transcript: String, decodeMs: Long?) {
        val action = ActionDef(command = "", displayName = transcript, kind = "navigate",
            payload = JSONObject().put("shortcut", shortcut).put("go", true).toString())
        if (dispatchMedia(action, "navigate=$shortcut", transcript, decodeMs)) {
            scheduleSessionClose("navigate", waitForSpeech = true)
        }
    }

    private suspend fun dispatchNavigateDestination(action: ActionDef, transcript: String, decodeMs: Long?) {
        if (dispatchMedia(action, "navigate", transcript, decodeMs)) {
            scheduleSessionClose("navigate", waitForSpeech = true)
        }
    }

    /**
     * "zadzwoń do <name>": the spoken name is matched against the phone book on the device
     * ([ContactNameMatcher]). A close, unique match is dialed at once; a loose one is said and
     * shown for confirmation first. Nothing goes to the assistant. The phone number is never
     * logged, journaled, shown or said: only the contact's name.
     */
    // Cancellation propagates; an unreadable phone book becomes a fixed local reply without exception data.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun dispatchCall(spokenName: String, transcript: String, decodeMs: Long?) {
        suspend fun stopped(reason: String, refusal: String) {
            earcon.fail()
            _state.value = VoiceUiState.Blocked(reason)
            statusTurn()?.finish(VoiceStage.REFUSED)
            record(nluEntry(transcript, decodeMs, CALL_LABEL, VoiceJournalEntry.Outcome.BLOCKED)
                .copy(reason = reason, refusal = refusal), "NLU call stopped: refusal=$refusal transcript=\"$transcript\"")
            announce("Głos", lines.refused(transcript, reason), reason)
        }
        if (!runCatching { contacts.hasPermission() }.getOrDefault(false)) {
            return stopped(CALL_NO_PERMISSION, VoiceRefusal.gate("contacts_permission"))
        }
        statusTurn()?.running()
        val book = try {
            contacts.all()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return stopped(CALL_UNREADABLE, VoiceRefusal.gate("contacts_unreadable"))
        }
        when (val match = ContactNameMatcher.match(spokenName, book)) {
            ContactMatch.NotFound ->
                stopped(callNotFound(spokenName), VoiceRefusal.gate("contact_not_found"))
            is ContactMatch.Direct -> placeCall(match.contact, transcript, decodeMs)
            is ContactMatch.Confirm -> {
                val question = callQuestion(match.contact.name)
                Log.i(TAG, "NLU call confirming: transcript=\"$transcript\"")
                statusTurn()?.finish(VoiceStage.CONFIRM_ON_SCREEN)
                announce("Głos", question, question)
                when (confirmer.confirm(CALL_CONFIRM_TITLE, question)) {
                    ConfirmResult.CONFIRMED -> placeCall(match.contact, transcript, decodeMs)
                    ConfirmResult.DECLINED ->
                        stopped(PolishActionText.DECLINED, VoiceRefusal.gate("confirm_declined"))
                    ConfirmResult.TIMED_OUT ->
                        stopped(PolishActionText.TIMED_OUT, VoiceRefusal.gate("confirm_timed_out"))
                    ConfirmResult.UNAVAILABLE ->
                        stopped(PolishActionText.NO_OVERLAY, VoiceRefusal.gate("confirm_unavailable"))
                }
            }
        }
    }

    /** Dials [contact] through the dispatcher's `call` action (dial screen, then the BT dial key), and
     *  closes the session after the reply so the microphone is not open during the call. */
    private suspend fun placeCall(contact: VoiceContact, transcript: String, decodeMs: Long?) {
        statusTurn()?.running()
        val action = ActionDef(command = "", displayName = contact.name, kind = "call",
            payload = JSONObject().put("phone", contact.phone).put("autoDial", true).toString())
        val result = actionDispatcher.dispatch(action, data = gate.vehicleSnapshot())
        if (result.success) {
            val text = callingText(contact.name)
            earcon.ok()
            _state.value = VoiceUiState.Done(transcript)
            statusTurn()?.finish(VoiceStage.DONE)
            record(nluEntry(transcript, decodeMs, CALL_LABEL, VoiceJournalEntry.Outcome.OK).copy(answer = text),
                "NLU dispatched: cmd=call transcript=\"$transcript\"")
            announce("Głos", text, text, done = true)
            scheduleSessionClose(CALL_LABEL, waitForSpeech = true)
        } else {
            val reason = result.reason ?: transcript
            earcon.fail()
            _state.value = VoiceUiState.Blocked(reason)
            statusTurn()?.finish(VoiceStage.REFUSED)
            record(nluEntry(transcript, decodeMs, CALL_LABEL, VoiceJournalEntry.Outcome.BLOCKED)
                .copy(reason = reason, refusal = VoiceRefusal.DISPATCH_FAILED),
                "NLU blocked: cmd=call transcript=\"$transcript\" reason=$reason")
            announce("Głos", lines.refused(transcript, reason), "Nie udało się")
        }
    }

    /** Runs a dictionary local [action] (never speed-gated) and reports it like any command;
     *  [label] is its journal command. True when it ran. */
    private suspend fun dispatchMedia(action: ActionDef, label: String, transcript: String, decodeMs: Long?): Boolean {
        statusTurn()?.running()
        val result = actionDispatcher.dispatch(action, data = gate.vehicleSnapshot())
        if (result.success) {
            earcon.ok()
            _state.value = VoiceUiState.Done(transcript)
            statusTurn()?.finish(VoiceStage.DONE)
            record(nluEntry(transcript, decodeMs, label, VoiceJournalEntry.Outcome.OK),
                "NLU dispatched: cmd=${action.kind} payload=${action.payload} transcript=\"$transcript\"")
            announce("Głos", lines.done(transcript), "Gotowe", done = true)
        } else {
            val reason = result.reason ?: transcript
            earcon.fail()
            _state.value = VoiceUiState.Blocked(reason)
            statusTurn()?.finish(VoiceStage.REFUSED)
            record(nluEntry(transcript, decodeMs, label, VoiceJournalEntry.Outcome.BLOCKED)
                .copy(reason = reason, refusal = VoiceRefusal.DISPATCH_FAILED), "NLU blocked: cmd=${action.kind} payload=${action.payload} transcript=\"$transcript\" reason=$reason")
            announce("Głos", lines.refused(transcript, reason), "Nie udało się")
        }
        return result.success
    }

    /** A dictionary question answered from the car's readings: said and shown like any other
     *  outcome, journaled as a built-in command with the answer, so a log tells it from the agent. */
    private suspend fun answer(res: Resolution.Answer, transcript: String, decodeMs: Long? = null) {
        earcon.ok()
        _state.value = VoiceUiState.Done(transcript)
        statusTurn()?.finish(VoiceStage.ANSWERED)
        record(nluEntry(transcript, decodeMs, res.label, VoiceJournalEntry.Outcome.OK).copy(answer = res.text),
            "NLU answered: cmd=${res.label} answer=\"${res.text}\" transcript=\"$transcript\"")
        announce("Głos", res.text, res.text, done = true)
    }

    /** A request to the car the grammar refused (negated, quoted, ambiguous, partial, unsupported).
     *  It is answered here and never leaves the device: no assistant, no agent, no dispatch. */
    private suspend fun rejectLocally(reason: String, transcript: String, decodeMs: Long?) {
        val text = PolishVoiceReplies.problem(PolishVoiceReplies.Problem.UNSUPPORTED)
        earcon.fail()
        _state.value = VoiceUiState.NotUnderstood(transcript)
        statusTurn()?.finish(VoiceStage.NOT_UNDERSTOOD)
        record(VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.REFUSED,
            detail = withDecodeMs(transcript, decodeMs), outcome = VoiceJournalEntry.Outcome.NOT_UNDERSTOOD,
            reason = text, refusal = reason, asrMs = decodeMs),
            "NLU refused locally: reason=$reason transcript=\"$transcript\"")
        announce("Głos", text, text)
    }

    /** A dictionary question whose value the car does not report (Polish): said as unavailable. */
    private suspend fun unavailable(res: Resolution.Unavailable, transcript: String, decodeMs: Long?) {
        earcon.fail()
        _state.value = VoiceUiState.Blocked(res.text)
        statusTurn()?.finish(VoiceStage.REFUSED)
        record(nluEntry(transcript, decodeMs, "ask:${res.question.id}", VoiceJournalEntry.Outcome.BLOCKED)
            .copy(reason = res.text, refusal = VoiceRefusal.VALUE_UNKNOWN),
            "NLU question unavailable: cmd=ask:${res.question.id} transcript=\"$transcript\"")
        announce("Głos", res.text, res.text, done = true)
    }

    private suspend fun fireAutomation(match: VoiceAutomationMatch, transcript: String, decodeMs: Long? = null) {
        val ruleId = match.ruleId
        fun entry(outcome: VoiceJournalEntry.Outcome, reason: String? = null, refusal: String? = null) =
            VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.AUTOMATION,
                detail = withDecodeMs(transcript, decodeMs), outcome = outcome, reason = reason,
                command = match.ruleName, refusal = refusal, asrMs = decodeMs)
        statusTurn()?.running()
        when (val r = automationEngine.fireVoiceRule(ruleId, gate.vehicleSnapshot())) {
            is VoiceFireResult.Fired ->
                if (r.success) {
                    // Fired(true) means the rule's actions were handed off to the engine's own
                    // scope, not that they already finished (they may still contain a delay);
                    // say "Wykonuję", not "Wykonano".
                    earcon.ok(); _state.value = VoiceUiState.Done(transcript)
                    statusTurn()?.finish(VoiceStage.STARTED) // handed off, never "done"
                    record(entry(VoiceJournalEntry.Outcome.OK),
                        "NLU automation fired: ruleId=$ruleId transcript=\"$transcript\"")
                    announce("Głos", lines.running(transcript), "Wykonuję", done = true)
                } else {
                    earcon.fail(); _state.value = VoiceUiState.Blocked(transcript)
                    statusTurn()?.finish(VoiceStage.REFUSED)
                    record(entry(VoiceJournalEntry.Outcome.BLOCKED, transcript, VoiceRefusal.DISPATCH_FAILED),
                        "NLU automation fire failed: ruleId=$ruleId transcript=\"$transcript\"")
                    announce("Głos", lines.refused(transcript, transcript), "Nie udało się")
                }
            VoiceFireResult.Confirming -> {
                earcon.ok(); _state.value = VoiceUiState.Done(transcript)
                statusTurn()?.finish(VoiceStage.CONFIRM_ON_SCREEN) // the rule runs after the confirmation
                record(entry(VoiceJournalEntry.Outcome.OK),
                    "NLU automation confirming: ruleId=$ruleId transcript=\"$transcript\"")
                announce("Głos", lines.done(transcript), "Gotowe", done = true)
            }
            VoiceFireResult.ParkRequired -> {
                // Same app-locale resolution as BlockReason.toText — the application
                // context follows the head unit's system locale, not the app language (#162).
                val reason = context.appLocalizedContext().getString(R.string.voice_block_park_required)
                earcon.fail(); _state.value = VoiceUiState.Blocked(reason)
                statusTurn()?.finish(VoiceStage.REFUSED)
                record(entry(VoiceJournalEntry.Outcome.BLOCKED, reason, VoiceRefusal.gate("park_required")),
                    "NLU automation blocked (park required): ruleId=$ruleId transcript=\"$transcript\"")
                announce("Głos", lines.refused(transcript, reason), "Nie udało się")
            }
            VoiceFireResult.SpeedUnknown -> {
                val reason = speedUnknownText()
                earcon.fail(); _state.value = VoiceUiState.Blocked(reason)
                statusTurn()?.finish(VoiceStage.REFUSED)
                record(entry(VoiceJournalEntry.Outcome.BLOCKED, reason, VoiceRefusal.gate("speed_unknown")),
                    "NLU automation blocked (speed unknown): ruleId=$ruleId transcript=\"$transcript\"")
                announce("Głos", lines.refused(transcript, reason), "Nie udało się")
            }
            VoiceFireResult.NotFound -> {
                earcon.fail(); _state.value = VoiceUiState.NotUnderstood(transcript)
                statusTurn()?.finish(VoiceStage.NOT_UNDERSTOOD)
                record(entry(VoiceJournalEntry.Outcome.NOT_UNDERSTOOD, refusal = VoiceRefusal.RULE_NOT_FOUND),
                    "NLU automation rule not found: ruleId=$ruleId transcript=\"$transcript\"")
                if (transcript.isNotBlank()) {
                    announce("Głos", lines.notUnderstood(transcript), "Nie rozumiem")
                }
            }
        }
    }

    /** LLM-agent fallback for FINAL transcripts no local resolver matched. A disabled or
     *  unconfigured-off agent degrades to the pre-agent NotUnderstood behaviour. The agent's
     *  own tools re-check every dispatcher safety gate — nothing here bypasses them. */
    private suspend fun agentFallback(transcript: String, decodeMs: Long? = null) {
        // Why no local resolver took the phrase: the agent entries below carry it.
        val why = turn.agentReason ?: VoiceRefusal.UNRECOGNIZED
        if (transcript.isBlank()) {
            earcon.fail()
            _state.value = VoiceUiState.NotUnderstood(transcript)
            statusTurn()?.finish(VoiceStage.NOT_UNDERSTOOD)
            record(VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.REFUSED,
                detail = withDecodeMs(transcript, decodeMs), outcome = VoiceJournalEntry.Outcome.NOT_UNDERSTOOD,
                refusal = VoiceRefusal.ASR_EMPTY, asrMs = decodeMs), "Agent skipped: blank transcript")
            announce("Głos", lines.genericNotUnderstood, "Nie rozumiem")
            return
        }
        if (polish) return assistantFallback(transcript, decodeMs, why)
        val streamed = mutableListOf<String>()
        var r: AgentResult? = null
        coroutineScope {
            lateinit var askJob: Job
            askJob = launch(Trace.causedBy(turn.heardId), start = CoroutineStart.LAZY) {
                r = agentOrchestrator.ask(transcript, onFiller = null) { sentence ->
                    if (!askJob.isCancelled) {
                        streamed += sentence
                        showAnswerHook(streamed.joinToString(" "))
                    }
                }
            }
            statusTurn()?.waitingAssistant()
            askJob.start()
            askJob.join()
        }
        captureIdle.first { it }
        val result = r ?: run {
        // Barge-in: the streamed half-sentence in the "Asystent: …" row belongs to an answer
            // that will never finish. Drop it so the driver is not left reading a stub while
            // the orb is already listening again.
            runCatching { clearDialogHook() }
            record(VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.AGENT,
                detail = withDecodeMs(transcript, decodeMs), outcome = VoiceJournalEntry.Outcome.ERROR,
                refusal = VoiceRefusal.BARGE_IN, asrMs = decodeMs), "Agent ask cancelled by name barge-in")
            return
        }
        when (result) {
            is AgentResult.Answer -> {
                earcon.ok()
                _state.value = VoiceUiState.AgentAnswer(result.text)
                statusTurn()?.finish(VoiceStage.ANSWERED)
                // Tool outcomes make the turn auditable from the journal screen: the
                // driver sees WHICH tools ran and whether each succeeded (no emoji — DiLink).
                val toolsNote = if (result.tools.isEmpty()) "" else
                    " [narzędzia: " + result.tools.joinToString(", ") { "${it.name}:${if (it.ok) "ok" else "err"}" } + "]"
                record(VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.AGENT,
                    detail = withDecodeMs(result.text + toolsNote, decodeMs), outcome = VoiceJournalEntry.Outcome.OK,
                    tools = result.tools, answer = result.text, refusal = why, asrMs = decodeMs),
                    "Agent answered: transcript=\"$transcript\" tools=${result.tools.size}")
                val didSpeak = gate.ttsEnabled() && speakOwned(result.text, streamed)
                // Orb dialog: this branch does not go through announce(), so feed the orb here.
                showAnswerHook(result.text)
                scheduleClear(result.text, didSpeak)
                // Wave P: a successful play_music closes the whole session after the reply -- the orb's
                // presence ducks the very music the agent just started. Other successful answers
                // close only when the close-after-command toggle is on.
                val reply: TtsEngine.SpeechQueue? = null
                when {
                    result.tools.any { it.name == "play_music" && it.ok } ->
                        scheduleSessionClose(REASON_PLAY_MUSIC, waitForSpeech = true, reply = reply)
                    result.tools.any { it.name == "end_conversation" && it.ok } ->
                        scheduleSessionClose("user-stop", waitForSpeech = didSpeak,
                            dwellMs = readingDwellMs(result.text, spoken = false), reply = reply)
                    !result.text.trimEnd().endsWith('?') && result.tools.all { it.ok } ->
                        closeAfterCommand(result.text, didSpeak, reply)
                }
            }
            AgentResult.Disabled -> {
                earcon.fail(); _state.value = VoiceUiState.NotUnderstood(transcript)
                statusTurn()?.finish(VoiceStage.NOT_UNDERSTOOD)
                record(VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.REFUSED,
                    detail = withDecodeMs(transcript, decodeMs), outcome = VoiceJournalEntry.Outcome.NOT_UNDERSTOOD,
                    refusal = why, asrMs = decodeMs), "Agent disabled: transcript=\"$transcript\"")
                announce("Głos", lines.notUnderstood(transcript), "Nie rozumiem")
            }
            is AgentResult.Error -> {
                earcon.fail(); _state.value = VoiceUiState.Blocked(result.message)
                statusTurn()?.finish(VoiceStage.FAILED)
                record(VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.AGENT,
                    detail = withDecodeMs(result.message, decodeMs), outcome = VoiceJournalEntry.Outcome.ERROR,
                    reason = result.message, refusal = why, asrMs = decodeMs),
                    "Agent error: ${result.message} transcript=\"$transcript\"")
                announce("Głos", lines.refused(transcript, result.message), "Nie udało się")
            }
        }
    }

    /**
     * The Polish counterpart of the agent fallback, reached only for harmless unknown speech (every
     * car-like request was refused earlier). With the assistant off nothing is sent anywhere.
     * With it on, the text goes to the one text-only assistant; any failure is said once and is
     * never retried or handed to another provider.
     */
    private suspend fun assistantFallback(transcript: String, decodeMs: Long?, why: String) {
        if (!assistant.isEnabled()) {
            val text = PolishVoiceReplies.problem(PolishVoiceReplies.Problem.AI_DISABLED)
            earcon.fail(); _state.value = VoiceUiState.NotUnderstood(transcript)
            statusTurn()?.finish(VoiceStage.NOT_UNDERSTOOD)
            record(VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.REFUSED,
                detail = withDecodeMs(transcript, decodeMs), outcome = VoiceJournalEntry.Outcome.NOT_UNDERSTOOD,
                reason = text, refusal = VoiceRefusal.AI_DISABLED, asrMs = decodeMs),
                "Assistant off: nothing sent, transcript=\"$transcript\"")
                announce("Głos", text, text)
            return
        }
        val turnResult = awaitAssistantReply(transcript)
        captureIdle.first { it }
        val result = turnResult.reply
        val action = turnResult.action
        // A proposal the handler settled is journalled even when the turn was cancelled meanwhile: a dispatch
        // already made is never undone, so its true outcome must be on record. Speech is skipped if cancelled.
        if (action != null) {
            proposalResult(action, transcript, decodeMs, why, silent = turnResult.cancelled)
            return
        }
        // A proposal without a handler result was cancelled before dispatch.
        val noReply = result == null || result is AssistantReply.Aborted
        if (noReply || result is AssistantReply.Proposal) {
            runCatching { clearDialogHook() }
            val refusal = VoiceRefusal.BARGE_IN
            record(VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.AGENT,
                detail = withDecodeMs(transcript, decodeMs), outcome = VoiceJournalEntry.Outcome.ERROR,
                refusal = refusal, asrMs = decodeMs), "Assistant turn cancelled: reason=$refusal")
            return
        }
        when (result) {
            is AssistantReply.Answer -> assistantAnswer(result, transcript, decodeMs, why)
            is AssistantReply.Problem -> {
                val spoken = PolishVoiceReplies.problem(result.problem)
                earcon.fail(); _state.value = VoiceUiState.Blocked(spoken)
                statusTurn()?.finish(VoiceStage.FAILED)
                record(VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.AGENT,
                    detail = withDecodeMs(result.detail, decodeMs), outcome = VoiceJournalEntry.Outcome.ERROR,
                    reason = result.detail, refusal = VoiceRefusal.ASSISTANT_FAILED, command = ASSISTANT_LABEL,
                    asrMs = decodeMs), "Assistant failed: problem=${result.problem} uncertain=${result.uncertain}")
                announce("Głos", spoken, spoken)
            }
            is AssistantReply.Proposal, AssistantReply.Aborted -> Unit // handled above
        }
    }

    private suspend fun assistantAnswer(result: AssistantReply.Answer, transcript: String, decodeMs: Long?, why: String) {
        _state.value = VoiceUiState.AgentAnswer(result.text)
        statusTurn()?.finish(VoiceStage.ANSWERED)
        record(VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.AGENT,
            detail = withDecodeMs(result.text, decodeMs), outcome = VoiceJournalEntry.Outcome.OK,
            answer = result.text, refusal = why, command = ASSISTANT_LABEL, asrMs = decodeMs),
            "Assistant answered: transcript=\"$transcript\"")
        var didSpeak = false
        if (gate.ttsEnabled() && speakOwned(result.text)) {
            didSpeak = true
        } else {
            earcon.ok()
        }
        showAnswerHook(result.text)
        scheduleClear(result.text, didSpeak)
        if (!result.text.trimEnd().endsWith('?')) closeAfterCommand(result.text, didSpeak)
    }

    /**
     * What the driver hears and the journal records for a proposal. Every line comes from the handler
     * ([PolishActionText] and the registry); the proposal's own prose is never spoken, shown or journalled.
     * [silent] is a turn cancelled after its dispatch started: the true outcome is journalled, nothing is said.
     */
    private suspend fun proposalResult(
        action: AssistantActionResult,
        transcript: String,
        decodeMs: Long?,
        why: String,
        silent: Boolean,
    ) {
        fun entry(line: String, outcome: VoiceJournalEntry.Outcome, label: String) =
            VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.AGENT,
                detail = withDecodeMs(line, decodeMs), outcome = outcome, answer = line, refusal = why,
                command = label, asrMs = decodeMs)
        when (action) {
            is AssistantActionResult.Spoken -> {
                val ok = action.outcome == VoiceJournalEntry.Outcome.OK
                record(entry(action.line, action.outcome, action.label), "Assistant action: outcome=${action.outcome} label=${action.label}")
                if (silent) {
                    runCatching { clearDialogHook() }
                    return
                }
                if (ok) { earcon.ok(); _state.value = VoiceUiState.Done(action.line) }
                else { earcon.fail(); _state.value = VoiceUiState.Blocked(action.line) }
                statusTurn()?.finish(if (ok) VoiceStage.DONE else VoiceStage.REFUSED)
                announce("Głos", action.line, action.line, done = true)
            }
            is AssistantActionResult.AliasPresented -> {
                record(entry(ALIAS_PROPOSED_LINE, VoiceJournalEntry.Outcome.OK, ALIAS_LABEL), "Assistant alias proposal presented")
                if (silent) return
                earcon.ok(); _state.value = VoiceUiState.Done(ALIAS_PROPOSED_LINE)
                statusTurn()?.finish(VoiceStage.DONE)
                announce("Głos", ALIAS_PROPOSED_LINE, ALIAS_PROPOSED_LINE, done = true)
            }
            // A repeated turn: nothing happened now, so there is nothing to say or record.
            AssistantActionResult.Silent -> statusTurn()?.finish(VoiceStage.NOT_UNDERSTOOD)
        }
    }

    /** One assistant turn: the reply, the handler's result for a proposal (null until it settles) and whether
     *  the ask job was cancelled (barge-in, local priority or hard stop) by the time it ended. */
    private class AssistantTurn(val reply: AssistantReply?, val action: AssistantActionResult?, val cancelled: Boolean)

    private suspend fun awaitAssistantReply(transcript: String): AssistantTurn {
        var reply: AssistantReply? = null
        var action: AssistantActionResult? = null
        var cancelled = false
        coroutineScope {
            val askJob = launch(Trace.causedBy(turn.heardId), start = CoroutineStart.LAZY) {
                val asked = assistant.ask(transcript)
                reply = asked
                // Inside the cancellable job: a barge-in cancels a pending confirmation through the handler, and a
                // dispatch that already started finishes and still returns its result here.
                if (asked is AssistantReply.Proposal) {
                    action = assistantActions.handle(
                        ProposalInput(asked.turnKey, asked.envelope, asked.recovered, asked.startedAtMs),
                    )
                }
            }
            statusTurn()?.waitingAssistant()
            askJob.start()
            askJob.join()
            cancelled = askJob.isCancelled
        }
        return AssistantTurn(reply, action, cancelled)
    }

    /** A resolved, actionable command. Decoupled from ParseResult/automation so a transcript can be
     *  resolved once (side-effect-free) and applied later — the basis of early-fire vs final routing. */
    private sealed interface Resolution {
        /** [label] is the readable command for the journal ("phrase:<id>" for a user phrase). */
        data class Cmd(val commands: List<String>, val label: String) : Resolution
        /** An executable saved alias; it runs only through [VoiceAliasCoordinator.run], never a local dispatch. */
        data class Alias(val alias: VoiceAlias) : Resolution
        data class RelTemp(val delta: Int) : Resolution
        data class Vol(val payload: String) : Resolution
        /** [shortcut] is the navigate payload's `shortcut`: [NAVIGATE_HOME] or [NAVIGATE_WORK]. */
        data class Navigate(val shortcut: String) : Resolution
        data class NavigateDestination(val action: ActionDef) : Resolution
        /** A phone call; [name] is the raw spoken name, matched against the phone book when applied. */
        data class Call(val name: String) : Resolution
        data object Music : Resolution
        /** A dictionary question with the value known: [text] is said, nothing is dispatched. */
        data class Answer(val question: VehicleQuestion, val text: String) : Resolution {
            val label: String get() = "ask:${question.id}"
        }
        data class Auto(val match: VoiceAutomationMatch) : Resolution
        /** A request to the car the grammar refuses ([reason] is an [NluRefusal] code). Local only. */
        data class Reject(val reason: String) : Resolution
        /** A dictionary question whose value the car does not report, answered locally ([text]). */
        data class Unavailable(val question: VehicleQuestion, val text: String) : Resolution
        /** Nothing local claimed the phrase: it goes to the agent; [reason] is a VoiceRefusal code. */
        data class None(val reason: String) : Resolution

        /** The route code of the trace. */
        val route: String get() = when (this) {
            is Cmd -> if (label.startsWith("phrase:")) "phrase" else "nlu"
            is Alias -> "phrase"
            is RelTemp, is Vol, Music, is Navigate, is NavigateDestination, is Call, is Answer -> "nlu"
            is Auto -> "automation"
            is Reject, is Unavailable -> "nlu"
            is None -> "agent"
        }
    }

    /** Journal entry of a built-in command (parser or user phrase) outcome. */
    private fun nluEntry(transcript: String, decodeMs: Long?, command: String, outcome: VoiceJournalEntry.Outcome) =
        VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.NLU,
            detail = withDecodeMs(transcript, decodeMs), outcome = outcome, command = command, asrMs = decodeMs)

    companion object {
        private const val TAG = "VoiceController"

        private const val CALL_LABEL = "call"
        private const val CALL_CONFIRM_TITLE = "Połączenie"
        private const val CALL_NO_PERMISSION = "Brak dostępu do kontaktów. Włącz go w ustawieniach."
        private const val CALL_UNREADABLE = "Nie udało się odczytać kontaktów."
        private fun callNotFound(name: String) = "Nie znalazłem kontaktu $name."
        private fun callQuestion(name: String) = "Zadzwonić do $name?"
        private fun callingText(name: String) = "Dzwonię: $name"

        private const val NAVIGATE_HOME = "home"
        private const val NAVIGATE_WORK = "work"

        // The AC setpoint the car accepts; a reading outside it is not a setpoint to step from.
        private val AC_TEMP_RANGE = 16..33

        // Journal "command" of a turn the Polish assistant answered or failed.
        private const val ASSISTANT_LABEL = "openclaw"
        private const val ALIAS_LABEL = "openclaw:alias"
        private const val ALIAS_PROPOSED_LINE = "Asystent proponuje zapamiętać frazę."

        // Longest value one logcat summary field may carry (an automation name, an utterance).
        private const val LOG_FIELD_CHARS = 200

        /** The one-line logcat summary of a journal entry: every value flattened to one line,
         *  quotes escaped and capped, so a name with a newline or a quote cannot break it. */
        internal fun logLine(e: VoiceJournalEntry): String {
            fun v(s: String?) = s?.let { AgentTrace.clip(VoiceJournalDump.oneLine(it), LOG_FIELD_CHARS) } ?: "-"
            return "heard=\"${v(e.transcript)}\" route=${e.route.code} cmd=${v(e.command)} reason=${v(e.refusal)}"
        }

        /** The free-form detail line next to [logLine]: it may quote the transcript or an
         *  automation name, so it is flattened and capped the same way. */
        internal fun logDetail(msg: String): String =
            AgentTrace.clip(VoiceJournalDump.oneLine(msg), LOG_DETAIL_CHARS)

        private const val LOG_DETAIL_CHARS = 500

        /** An utterance heard while another one is routed: its transcript is capped like any detail. */
        internal fun busyDropLine(transcript: String): String = logDetail("Utterance dropped while busy: $transcript")

        // Dwell on a terminal state before auto-returning to Idle. Short on purpose —
        // long enough to read "not recognized", short enough to feel instant.
        private const val IDLE_RESET_DELAY_MS = 1200L

        // Wave D2: dwell before the orb dialog block ("You: …"/"Assistant: …") is cleared, measured from
        // when the spoken answer finishes (or from when it is shown, if TTS is off).
        private const val DIALOG_CLEAR_MS = 6_000L

        // Reading dwell for an answer nobody spoke aloud: rough Russian reading speed, ~1000
        // characters per minute, with a hard cap so the block never squats on the screen.
        private const val DIALOG_READ_MS_PER_CHAR = 60L
        private const val DIALOG_READ_MAX_MS = 30_000L

        // Oldest vehicle snapshot a dictionary question is answered from; an older one goes to the agent.
        private const val ASK_MAX_SNAPSHOT_AGE_MS = 30_000L

        // Continuous session (Wave B): silence auto-stop. Wave P removed the hard session cap --
        // long conversations must never be cut off; silence is the only automatic exit.
        private const val SILENCE_AUTOSTOP_MS = 30_000L

        // Wave P play_music auto-close: how long to wait for the reply's playback to actually
        // begin (speaking=false -> true) before giving up and closing anyway. Covers offline
        // synth latency and the online 2 s per-sentence timeout with margin.
        private const val SPEAK_START_GRACE_MS = 5_000L
        private const val PLAYBACK_TIMEOUT_MS = 120_000L

        // Session-stop reason of the play_music close; a new phrase never cancels that one.
        private const val REASON_PLAY_MUSIC = "play_music"

        // Playback window grace period after our own audio was last audible (or enqueued): the
        // tail of the agent's voice can still reach the mic (see lastSpeakingSeenMs). Not measured
        // yet (500 ms before 28.09); the "echo tail:" line logs the real tail after every reply.
        private const val TTS_ECHO_GRACE_MS = 200L

        /** Pure so it is unit-testable without a real clock/session: whether the mic currently
         *  hears our own playback -- either TTS is audible right now, or we're still inside the
         *  post-speech grace window. */
        internal fun inPlaybackWindow(nowMs: Long, windowEndMs: Long, audible: Boolean): Boolean =
            audible || nowMs < windowEndMs
    }
}

/** Control-flow signal to auto-stop the continuous session on prolonged silence. A
 *  CancellationException so it tears down the collect chain (mic, VAD, recognizer) cleanly. */
private object StopSession : CancellationException("voice-session-silence-timeout")
private object StopAccepted : CancellationException("voice-utterance-accepted")
