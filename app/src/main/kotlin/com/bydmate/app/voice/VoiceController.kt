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
import kotlin.coroutines.EmptyCoroutineContext

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
    private class StatusTurnElement(val turn: VoiceStatusTracker.Turn) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<StatusTurnElement>
    }

    private suspend fun statusTurn(): VoiceStatusTracker.Turn? = currentCoroutineContext()[StatusTurnElement]?.turn

    private val _state = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    // Continuous session indicator (Wave B), consumed by the listening-widget UI. Distinct from
    // [state], which flashes each utterance's terminal outcome and resets independently.
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val busy = AtomicBoolean(false)

    /** The language of the running (or just starting) session, captured from [sessionProfile] when
     *  the session starts. Everything the session says, matches and routes follows it. */
    @Volatile private var language: VoiceLanguage = VoiceLanguage.DEFAULT
    private val polish: Boolean get() = language == VoiceLanguage.PL
    private val lines: VoiceSessionText get() = VoiceSessionText(language)

    // Why the running ask is being cancelled, so its journal entry says so (name barge-in when null).
    @Volatile private var askCancelReason: String? = null

    @Volatile private var sessionJob: Job? = null
    // Android 10 head units: the press is waiting for the ASR warm-up (startContinuousSessionWhenWarm).
    @Volatile private var warmupJob: Job? = null
    @Volatile private var routingJob: Job? = null
    @Volatile private var cancellableAskJob: Job? = null
    // Busy drops are Log.i-only by contract (no journal/earcon/state change), so tests have no
    // observable signal that the sequential collect consumed a dropped utterance; this counter
    // is that signal. @Volatile: written by the collect coroutine, read from the test thread.
    @Volatile private var droppedWhileBusy = 0

    /** The utterance being routed: when routing started (dispatch latency in the journal) and,
     *  once no local resolver claimed it, why it went to the agent. Routing is sequential (one
     *  routingJob at a time), so a single slot is enough. [heardId] is its trace event, the
     *  cause of everything the turn does. */
    private data class Turn(val startedAtMs: Long = 0L, val agentReason: String? = null, val heardId: Long = 0L)
    @Volatile private var turn = Turn()

    // Trace event of the running continuous session, and why it is being stopped.
    @Volatile private var sessionTraceId = 0L
    @Volatile private var stopReason: String? = null

    /**
     * Returns true when any voice session is active: the continuous GigaAM session (sets both
     * _listening and busy for its whole duration), or the brief window the "model not ready"
     * branch of onPttPressed() holds busy while it journals and announces the failure.
     * Used by VoiceAutomationActions to gate speak/agent_query actions.
     */
    fun sessionActive(): Boolean = listening.value || busy.get()

    /** Test seams, same rationale as [lastSpeakingSeenMs]: deterministic await conditions
     *  instead of fixed sleeps, no public API surface added. */
    internal fun routingJobForTest(): Job? = routingJob
    internal fun droppedWhileBusyForTest(): Int = droppedWhileBusy
    internal fun warmupJobForTest(): Job? = warmupJob
    // Hard-stop contract: orb press (stopContinuousSession) cancels routingJob immediately.
    // A vehicle-write unit that has already started runs to completion regardless — VehicleApiImpl
    // wraps composite (window fan-out, fridge preset) and seat (switch+level) write sequences in
    // withContext(NonCancellable), so a cancellation mid-sequence can never leave the car
    // half-commanded. processingUtterance is retained as a flag for the barge-in / busy-drop path.
    @Volatile private var processingUtterance = false
    private val stopRequested = AtomicBoolean(false)
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

    /** Speak a short phrase for every session outcome (not only agent answers).
     *  Overlay text stays detailed; the spoken phrase is terse for the road. */
    private suspend fun announce(title: String, overlay: String, spoken: String, done: Boolean = false) {
        // Hard stop gate: never start a new announcement after the orb went off — the callers'
        // runCatching wrappers swallow the cancellation that would otherwise stop this path.
        // coroutineContext.isActive is the per-turn mark: a cancelled routing turn stays
        // cancelled even after a following session has reset the global stopRequested flag.
        if (stopRequested.get() || !currentCoroutineContext().isActive) return
        var didSpeak = false
        if (gate.ttsEnabled()) {
            // Stamp at the moment we ourselves start TTS, not only from the mic filter's
            // per-frame read of ttsEngine.speaking: a short utterance that starts AND ends
            // entirely between two mic frames (see lastSpeakingSeenMs above) would otherwise
            // never get stamped at all, since speaking never reads true on any frame. Only
            // stamp when speak() actually enqueued playback -- see lastSpeakingSeenMs above.
            val identity = agentIdentity()
            val phrase = identity.persona.spokenPhrase(spoken, gender = identity.gender)
            if (runCatching { ttsEngine.speak(phrase, language) }.getOrDefault(false)) {
                noteOwnSpeech(phrase)
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

    /** PTT toggle (Wave B): no session running -> start one (continuous GigaAM session when the
     *  model is ready, whatever the app language: voice is always Russian), else report the
     *  missing model without starting a session (#87); a continuous session already listening
     *  -> stop it immediately (barge-in stops TTS too). */
    fun onPttPressed() {
        val ptt = Trace.event(TraceArea.USER, "ptt", "listening" to _listening.value)
        if (!gate.isEnabled()) {
            Trace.event(TraceArea.VOICE, "ptt-ignored", "reason" to "voice-off", by = ptt)
            return
        }
        // Android 10 head units: the warm-up job starts the session off the main thread. Until it
        // is done a press is dropped: the session may already be listening while its job is not
        // assigned yet, and a stop would miss it.
        if (warmupJob?.isActive == true) {
            Trace.event(TraceArea.VOICE, "ptt-ignored", "reason" to "warming", by = ptt)
            return
        }
        if (_listening.value) {
            stopContinuousSession("ptt")
            return
        }
        val profile = sessionProfile()
        language = profile.language
        if (continuousAsr.isReady(profile)) {
            if (continuousAsr.requiresWarmBeforeListening(profile) && !continuousAsr.isWarm(profile)) {
                startContinuousSessionWhenWarm(ptt, profile)
            } else {
                startContinuousSession(ptt, profile)
            }
        } else {
            // GigaAM model missing: preserve the degraded UX the legacy path produced —
            // overlay + journal ERROR.
            if (!busy.compareAndSet(false, true)) return
            // A prior continuous-session hard stop (stopContinuousSession()) leaves stopRequested
            // set; only startContinuousSession() used to clear it. Without this reset, announce()
            // below would silently suppress this branch's overlay+speech forever for a user who
            // can never start a continuous session again to reset the flag (I-1).
            stopRequested.set(false)
            val msg = if (polish) PolishVoiceReplies.problem(PolishVoiceReplies.Problem.MODEL_MISSING)
            else appStrings.get(R.string.voice_error_model_missing)
            _state.value = VoiceUiState.NotUnderstood("")
            statusTracker.showFailure(language)
            record(
                VoiceJournalEntry(transcript = "", route = VoiceJournalEntry.Route.REFUSED, detail = "",
                    outcome = VoiceJournalEntry.Outcome.ERROR, reason = msg, refusal = VoiceRefusal.MODEL_MISSING),
                "GigaAM model not ready"
            )
            busy.set(false)
            scheduleIdleReset()
            scope.launch { announce("Głos", msg, msg) }
        }
    }

    /** Android 10 head units (see [ContinuousAsr.requiresWarmBeforeListening]): the start cue and
     *  the mic wait until the recognizer and the VAD are built, or the driver's first words go
     *  to a mic that is not open yet. The pill answers the press at once in its "Myślę" look.
     *  A warm-up that could not finish still starts the session: its own build then either
     *  works or reports the failure the usual way. */
    private fun startContinuousSessionWhenWarm(cause: Long, profile: VoiceSessionProfile) {
        // Before the launch: the job may finish (and decline to start) before this thread goes on.
        val preparing = statusTracker.openSession(language, preparing = true)
        statusSession = preparing
        warmupJob = scope.launch(Dispatchers.IO) {
            runCatching { showListeningOverlay(thinkingText()) }
            val startedMs = System.currentTimeMillis()
            runCatching { continuousAsr.warmUp(profile) }
            val waitedMs = System.currentTimeMillis() - startedMs
            val warm = continuousAsr.isWarm(profile)
            Log.i(TAG, "warm-up before listening: ${waitedMs}ms warm=$warm")
            Trace.event(TraceArea.VOICE, "warm-wait", "ms" to waitedMs, "warm" to warm, by = cause)
            if (gate.isEnabled()) {
                runCatching { updateListeningOverlay(listeningText()) }
                // Declined (busy: the previous session is still releasing): nothing will ever
                // leave PREPARING, so close it. Not keyed on sessionJob, which is nulled before busy.
                if (!startContinuousSession(cause, profile)) closePreparing(preparing)
            } else {
                runCatching { hideListeningOverlay() }
                closePreparing(preparing)
            }
        }
    }

    private fun closePreparing(preparing: VoiceStatusTracker.Session) {
        preparing.close(lingerTerminal = false)
        if (statusSession === preparing) statusSession = null
    }

    /** Continuous PTT-toggled session (Wave B): one long-lived mic capture feeds VAD-segmented
     *  utterances into the shared NLU/agent router (routeUtterance), so a follow-up question from
     *  the agent keeps listening for free — the loop just collects again. Auto-stops after
     *  SILENCE_AUTOSTOP_MS of continuous silence (Wave P: no session cap). */
    private fun startContinuousSession(cause: Long, profile: VoiceSessionProfile): Boolean {
        if (!busy.compareAndSet(false, true)) {
            Trace.event(TraceArea.VOICE, "ptt-ignored", "reason" to "busy", by = cause)
            return false
        }
        // The profile was captured at the PTT edge. Do not read the active profile again after
        // an asynchronous warm-up: a settings switch must either reject this stale start in the
        // profile-aware ASR or keep every session concern on this one profile.
        language = profile.language
        if (!continuousAsr.isReady(profile)) {
            busy.set(false)
            return false
        }
        DiagnosticEmitters.lifecycle("preparing")
        val traceId = Trace.event(TraceArea.VOICE, "session-start", by = cause)
        sessionTraceId = traceId
        stopReason = null
        // Barge-in: kill any ongoing TTS so it neither talks over the user nor bleeds into capture.
        runCatching { ttsEngine.stop() }
        lastSpeakingSeenMs = 0L
        processingUtterance = false
        routingJob = null
        cancellableAskJob = null
        stopRequested.set(false)
        _state.value = VoiceUiState.Listening
        _listening.value = true
        // The warm-up path hands over its PREPARING session: the tracker returns that same handle.
        val widget = statusTracker.openSession(language, preparing = false)
        statusSession = widget
        earcon.ok()
        // Duck the music the instant the orb appears -- captureSession's own duck fires only after
        // the GigaAM recognizer is constructed (~1.3 s, field defect APK 337). duckMusic() is
        // idempotent (volume already at the duck target returns null), so the inner call becomes
        // a no-op and this early saved volume is the one restored at session teardown.
        val earlyDuck = runCatching { audioCapture.duckMusic() }.getOrNull()
        // Warm the model and online-voice connections while the driver is still speaking: a cold
        // turn otherwise pays DNS + TLS on both hosts inside the reply latency.
        runCatching { if (gate.ttsEnabled()) ttsEngine.prewarmNetwork() }
        // The Russian agent's provider connection is warmed only for a Russian session: a Polish
        // session never talks to it.
        sessionJob = scope.launch { ContinuousSession(widget, earlyDuck, traceId, profile).run() }
        return true
    }

    /** State local to one capture/ASR collection; frame state is used only by capture. */
    private inner class ContinuousSession(
        private val widget: VoiceStatusTracker.Session,
        private val earlyDuck: Int?,
        private val traceId: Long,
        private val profile: VoiceSessionProfile,
    ) {
        private var session: Job? = null
        private lateinit var sessionScope: CoroutineScope
        private var lastEventMs = 0L
        private var wasAudible = false   // capture thread only
        private var micGated = false     // capture thread only
        private val echoTail = EchoTailMeter() // capture thread only
        // Silence for the auto-stop: the agent's turn does not count (routing here, playback
        // never reaches the recognizer at all), and the countdown starts over once the turn
        // is over, so neither a long thinking phase nor a long answer eats the driver's
        // waiting time after it. Derived from GigaAM's cumulative SilenceTick, which restarts
        // at every SpeechStart.
        private var silentMs = 0L
        private var lastTickSilentMs = 0L
        private var inAgentTurn = false

        suspend fun run() {
            sessionScope = currentCoroutineContext().let { CoroutineScope(it) }
            session = currentCoroutineContext()[Job]
            runCatching { showListeningOverlay(listeningText()) }
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
                            // Once a stop has been requested (see stopContinuousSession()) no further
                            // frames are forwarded to the recognizer, even while an in-flight utterance
                            // started before the stop is still being routed.
                            if (stopRequested.get()) return@filter false
                            !frame.mark
                        }
                        .map { it.pcm }
                    continuousAsr.transcribe(profile, pcm).collect { ev ->
                        onEvent(ev)
                    }
                }.onFailure { failure ->
                    when (failure) {
                        is StopSession -> stopReason = "silence"
                        is CancellationException -> throw failure
                        else -> failed(failure)
                    }
                }
            } finally {
                finish()
            }
        }

        private fun gateFrame(samples: ShortArray): Boolean {
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

        private suspend fun onEvent(ev: ContinuousAsrEvent) {
            when (ev) {
                is ContinuousAsrEvent.SpeechStart -> {
                    lastEventMs = System.currentTimeMillis()
                    lastTickSilentMs = 0L
                    silentMs = 0L
                    // The live VAD now detects speech while a routing child is in
                    // flight; clobbering Thinking here would violate the busy-drop
                    // contract (no state change while an utterance is being routed).
                    if (!processingUtterance) _state.value = VoiceUiState.Listening
                }
                is ContinuousAsrEvent.SilenceTick -> {
                    lastEventMs = System.currentTimeMillis()
                    val tickMs = if (ev.silentMs >= lastTickSilentMs) ev.silentMs - lastTickSilentMs else ev.silentMs
                    lastTickSilentMs = ev.silentMs
                    // Never auto-stop while the agent is still working on the answer.
                    val agentTurn = processingUtterance
                    if (agentTurn) {
                        inAgentTurn = true
                    } else {
                        if (inAgentTurn) {
                            inAgentTurn = false
                            silentMs = 0L
                            Log.i(TAG, "Silence auto-stop countdown restarted: the agent's turn is over")
                        }
                        silentMs += tickMs
                    }
                    if (silentMs >= SILENCE_AUTOSTOP_MS && !agentTurn) throw StopSession
                }
                is ContinuousAsrEvent.Utterance -> {
                    onUtterance(ev)
                }
            }
        }

        private suspend fun onUtterance(ev: ContinuousAsrEvent.Utterance) {
            val decodeMs = System.currentTimeMillis() - lastEventMs
            if (processingUtterance) {
                if (AgentNameMatcher.matches(ev.text, agentIdentity().name)) {
                    cancellableAskJob?.takeIf { it.isActive }?.let { ask ->
                        Log.i(TAG, "Barge-in by name")
                        Trace.event(TraceArea.USER, "barge-in", by = turn.heardId)
                        ask.cancel()
                        bargeIn(ev.text, "Barge-in by name")
                    } ?: run {
                        Log.i(TAG, "Barge-in by name ignored: no cancellable ask")
                        Trace.event(TraceArea.VOICE, "dropped", "reason" to "name-no-ask", by = turn.heardId)
                    }
                    return
                }
                // A Polish assistant turn can last a long time. A local command, question or
                // refusal is never made to wait for it: the turn is cancelled and the
                // phrase is routed as usual. Unknown speech still waits (busy drop below).
                if (polish && cancellableAskJob?.isActive == true && localTakesPriority(ev.text)) {
                    Log.i(TAG, "Local phrase during a remote turn: cancelling the turn")
                    Trace.event(TraceArea.USER, "local-priority", by = turn.heardId)
                    cancelRemoteTurnFor(VoiceRefusal.LOCAL_PRIORITY)
                    routingJob?.join()
                    // fall through: routed like any idle-time phrase
                } else {
                    Log.i(TAG, busyDropLine(ev.text))
                    Trace.event(TraceArea.VOICE, "dropped", "reason" to "busy", by = turn.heardId)
                    droppedWhileBusy++
                    return
                }
            }
            processingUtterance = true
            val widgetTurn = statusSession?.openTurn()
            val job = route(ev, decodeMs, widgetTurn)
            routingJob = job
            job.start()
            lastEventMs = System.currentTimeMillis()
        }

        private fun route(
            ev: ContinuousAsrEvent.Utterance,
            decodeMs: Long,
            widgetTurn: VoiceStatusTracker.Turn?,
        ): Job {
            return sessionScope.launch(widgetTurn?.let { StatusTurnElement(it) } ?: EmptyCoroutineContext,
                start = CoroutineStart.LAZY) {
                runCatching { updateListeningOverlay(thinkingText()) }
                try {
                    runCatching {
                        routeUtterance(ev.text, decodeMs)
                    }.onFailure { t ->
                        // A single utterance's routing crashed. Only capture/ASR failures
                        // (caught below) tear the session down -- this one just gets
                        // journaled as an error, and the session keeps listening.
                        if (t is CancellationException) throw t
                        earcon.fail()
                        _state.value = VoiceUiState.NotUnderstood(ev.text)
                        widgetTurn?.finish(VoiceStage.FAILED)
                        record(VoiceJournalEntry(transcript = ev.text, route = VoiceJournalEntry.Route.REFUSED,
                            detail = withDecodeMs(ev.text, decodeMs), outcome = VoiceJournalEntry.Outcome.ERROR,
                            reason = t.message, refusal = VoiceRefusal.INTERNAL_ERROR, asrMs = decodeMs),
                            "Continuous session utterance failed: decodeMs=$decodeMs ${t.message}")
                        announce("Głos", lines.internalError(t.message), "Błąd")
                    }
                } finally {
                    routingJob = null
                    processingUtterance = false
                    runCatching { updateListeningOverlay(listeningText()) }
                    if (stopRequested.get()) session?.cancel()
                }
            }
        }

        private suspend fun failed(t: Throwable) {
            DiagnosticEmitters.voiceFailure("failed", t)
            stopReason = "error:${t.javaClass.simpleName}"
            statusSession?.openTurn()?.finish(VoiceStage.FAILED)
            Log.w(TAG, "Continuous session failed: ${t.message}")
            record(VoiceJournalEntry(transcript = "", route = VoiceJournalEntry.Route.REFUSED, detail = "",
                outcome = VoiceJournalEntry.Outcome.ERROR, reason = t.message, refusal = VoiceRefusal.ASR_FAILED),
                "Continuous session failed: ${t.message}")
            announce("Głos", lines.internalError(t.message), "Błąd")
        }

        private fun finish() {
            routingJob?.cancel()
            routingJob = null
            cancellableAskJob = null
            processingUtterance = false
            // Before `listening` goes false: a press after that starts a new session whose handle
            // this teardown must not close. Only these ends keep a terminal status on screen: the
            // session closing itself after a reply, and a failure. A press, silence and a bare
            // cancellation give IDLE at once.
            val ending = statusSession
            statusSession = null
            val reason = stopReason
            ending?.close(lingerTerminal = reason != null && (reason in LINGER_REASONS || reason.startsWith("error:")))
            runCatching { audioCapture.restoreMusic(earlyDuck) }
            _listening.value = false
            _state.value = VoiceUiState.Idle
            // Distinct off-cue so the driver can tell session start (ok) and stop apart by ear.
            earcon.off()
            runCatching { hideListeningOverlay() }
            sessionJob = null
            // Before busy is released: a new session resets the reason.
            DiagnosticEmitters.lifecycle("stopped")
            Trace.event(TraceArea.VOICE, "session-stop", "reason" to (stopReason ?: "cancel"), by = traceId)
            busy.set(false)
        }
    }

    /** Name barge-in: silence the agent and hand the turn back to the driver. The caller has
     *  already cancelled the in-flight ask. */
    private fun bargeIn(transcript: String, logMsg: String) {
        // Before stop(): the session auto-close resumes on the very speaking=false it causes.
        pendingClose?.takeIf { it.job.isActive }?.let {
            it.job.cancel()
            Log.i(TAG, "${it.reason} auto-close cancelled by barge-in: the driver stays in the dialog")
        }
        runCatching { ttsEngine.stop() }
        earcon.ok()
        _state.value = VoiceUiState.Listening
        statusSession?.listening() // also drops the cancelled turn's words
        runCatching { updateListeningOverlay(listeningText()) }
        record(VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.AGENT,
            detail = "Przerwano po wykryciu imienia", outcome = VoiceJournalEntry.Outcome.OK,
            refusal = VoiceRefusal.BARGE_IN), logMsg)
    }

    /** User contract: orb button is a hard OFF switch. Cancels everything immediately, regardless
     *  of whether an utterance is mid-routing. Order: (1) set stop flag FIRST so every
     *  continuation path (pcm filter, routing finally, ask callback) sees it before any job is
     *  cancelled; (2) stop TTS; (3) cancel ask job; (4) cancel routing job; (5) cancel session.
     *
     *  Vehicle-write safety: a single binder.transact() is a blocking native call that completes
     *  even when the coroutine is cancelled mid-call, but compound commands (seat switch→level,
     *  fridge mode→temp, composite sequences) issue several writes. VehicleApiImpl wraps one
     *  command's write unit in withContext(NonCancellable), so a started sequence always runs to
     *  completion and cancellation lands between commands; dispatch wrappers rethrow
     *  CancellationException so a cancelled turn is never announced as a failure. */
    private fun stopContinuousSession(reason: String) {
        stopReason = reason
        stopRequested.set(true)   // (1) gate every continuation before cancelling anything
        runCatching { ttsEngine.stop() }
        Log.i(TAG, "stopContinuousSession: hard stop; askActive=${cancellableAskJob?.isActive} routingActive=${routingJob?.isActive}")
        cancellableAskJob?.cancel()  // (2) cancel pending agent ask / follow-up
        routingJob?.cancel()         // (3) cancel in-flight routing (NLU dispatch or agent path)
        sessionJob?.cancel()         // (4) full teardown; routing finally also cancels session
                                     //     via stopRequested, but we do it here too so idle-
                                     //     session stops are instant and not deferred to finally
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
    private fun heard(decodeMs: Long, route: String, why: String? = null) {
        val id = Trace.event(TraceArea.VOICE, "heard", "route" to route, "why" to why, "asr_ms" to decodeMs,
            by = sessionTraceId)
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

    /** True when [raw] is claimed by a local resolver (command, question, saved phrase, refusal):
     *  everything but harmless unknown speech. Side-effect free. */
    private suspend fun localTakesPriority(raw: String): Boolean =
        resolve(AgentNameMatcher.stripLeadingName(raw, agentIdentity().name)) !is Resolution.None

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
            if (resetGen.get() == gen && !busy.get()) {
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
    private fun closeAfterCommand(text: String, spoken: Boolean, reply: TtsEngine.SpeechQueue? = null) {
        if (sessionJob == null || !runCatching { gate.closeAfterCommand() }.getOrDefault(false)) return
        scheduleSessionClose("auto-close", waitForSpeech = spoken, dwellMs = readingDwellMs(text, spoken = false), reply = reply)
    }

    /** Closes the running session after the reply: with [waitForSpeech] once playback began and
     *  ended (the whole [reply] queue when the reply was queued), else after [dwellMs]. [reason]
     *  goes to the session-stop trace. A newer close replaces a pending one. */
    private fun scheduleSessionClose(
        reason: String,
        waitForSpeech: Boolean,
        dwellMs: Long = 0L,
        reply: TtsEngine.SpeechQueue? = null,
    ) {
        val closingJob = sessionJob ?: return
        Log.i(TAG, "$reason auto-close armed: waitForSpeech=$waitForSpeech queued=${reply != null} dwellMs=$dwellMs")
        pendingClose?.job?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (waitForSpeech) {
                if (reply != null) {
                    // speaking dips false between two queued sentences (online voices report it
                    // per sentence), so the queue itself tells when the last one was handed out.
                    reply.awaitPlayed()
                } else {
                    // speak()/enqueue() returns before the TTS worker flips speaking=true, so
                    // waiting for !speaking alone completes immediately and would cut the reply
                    // before it starts. Wait (bounded) for playback to begin, then for it to
                    // end; if it never begins (TTS off, synth failed), the grace elapses and
                    // the session still closes.
                    withTimeoutOrNull(SPEAK_START_GRACE_MS) { ttsEngine.speaking.first { it } }
                }
                ttsEngine.speaking.first { !it }   // let the agent finish its own reply first
            } else {
                delay(dwellMs)
            }
            ensureActive()   // a cancel that landed right as the wait ended still wins
            // PTT restarting a session stops TTS -- the very signal this coroutine
            // waits for -- so only close the session this reply belongs to, never a
            // newer one the user has already started.
            if (sessionJob === closingJob) stopContinuousSession(reason)
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

    /** Cancels the running assistant turn on behalf of a local phrase and silences its playback.
     *  Ownership: this controller owns the waiting coroutine ([cancellableAskJob]); cancelling it
     *  makes the assistant abort the turn on its side, and a late answer is then dropped there. */
    private fun cancelRemoteTurnFor(reason: String) {
        askCancelReason = reason
        runCatching { ttsEngine.stop() }
        cancellableAskJob?.cancel()
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
        val queue = if (gate.ttsEnabled()) runCatching { ttsEngine.startQueue(language) }.getOrNull() else null
        val streamed = StringBuilder()
        var queuedAny = false
        // Whether the filler alone reached the TTS queue. Audible, but not an answer sentence:
        // must not suppress the earcon+legacy-speak fallback below (queuedAny gates that), or the
        // driver hears only the filler when the real answer fails to enqueue. Still counts toward
        // "something was said aloud" for the dialog dwell timer (didSpeak below).
        var fillerQueued = false
        var r: AgentResult? = null
        coroutineScope {
            // LAZY so the body cannot run before askJob is assigned: the callback below gates
            // on askJob.isCancelled — a PER-TURN cancellation mark. The global stopRequested
            // flag alone is not enough: a following session (continuous or legacy) resets it,
            // which would un-mute late callbacks from this already-hard-stopped turn.
            lateinit var askJob: Job
            askJob = launch(Trace.causedBy(turn.heardId), start = CoroutineStart.LAZY) {
                r = agentOrchestrator.ask(
                    transcript,
                    // The filler is speech only: queued for TTS, never shown in the orb's answer
                    // row. With TTS off (no queue) there is no filler at all.
                    onFiller = queue?.let { q ->
                        { phrase ->
                            // Same hard stop gate as the sentence callback below.
                            if (!stopRequested.get() && !askJob.isCancelled &&
                                runCatching { q.enqueue(phrase) }.getOrDefault(false)
                            ) {
                                noteOwnSpeech(phrase)
                                fillerQueued = true
                            }
                        }
                    },
                ) { sentence ->
                    // Hard stop gate: the SSE loop can emit one more sentence between the ask
                    // job's cancellation and its next suspension point; this callback is
                    // non-suspend, so it must check for itself (the TTS queue is already
                    // superseded by tts.stop(), but the orb dialog repaint is not).
                    if (stopRequested.get() || askJob.isCancelled) return@ask
                    if (queue != null && runCatching { queue.enqueue(sentence) }.getOrDefault(false)) {
                        noteOwnSpeech(sentence)
                        queuedAny = true
                    }
                    if (streamed.isNotEmpty()) streamed.append(' ')
                    streamed.append(sentence)
                    showAnswerHook(streamed.toString())
                }
            }
            cancellableAskJob = askJob
            statusTurn()?.waitingAssistant()
            askJob.start()
            try {
                askJob.join()
            } finally {
                cancellableAskJob = null
            }
        }
        runCatching { queue?.finish() }
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
        // Hard stop gate: if the orb went off between ask.join() returning and this point,
        // the answer must not resurrect state, TTS, or the orb dialog (the path below has
        // no suspension point that would deliver the cancellation).
        if (stopRequested.get()) {
            record(VoiceJournalEntry(transcript = transcript, route = VoiceJournalEntry.Route.AGENT,
                detail = withDecodeMs(transcript, decodeMs), outcome = VoiceJournalEntry.Outcome.ERROR,
                refusal = VoiceRefusal.HARD_STOP, asrMs = decodeMs), "Agent answer suppressed: hard stop")
            return
        }
        when (result) {
            is AgentResult.Answer -> {
                // Gated on queuedAny (an actual successful enqueue), not "sentences arrived": the
                // queue can fail to start or to enqueue (TTS toggle on but voice model not ready /
                // native exception), and that turn must still fall through to the legacy
                // earcon+speak below instead of going completely silent. When queuedAny is true,
                // the reply was already spoken sentence-by-sentence via the queue above -- the
                // earcon and a second full-text speak() would be noise on top of live audio.
                if (!queuedAny) earcon.ok()
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
                var didSpeak = queuedAny || fillerQueued
                if (!queuedAny && gate.ttsEnabled()) {
                    // See announce() for why this is stamped at call time, not only per-frame,
                    // and only when speak() actually enqueued playback.
                    if (runCatching { ttsEngine.speak(result.text, language) }.getOrDefault(false)) {
                        noteOwnSpeech(result.text)
                        didSpeak = true
                    }
                }
                // Orb dialog: this branch does not go through announce(), so feed the orb here.
                showAnswerHook(result.text)
                scheduleClear(result.text, didSpeak)
                // Wave P: a successful play_music closes the whole session after the reply -- the orb's
                // presence ducks the very music the agent just started. end_conversation is the driver
                // asking the agent to close; any other final answer (not a question, no failed tool:
                // the driver will rephrase) closes only with the close-after-command toggle on. Gated
                // on sessionJob so the legacy single-shot path (which has no session to close) is
                // untouched.
                val reply = queue.takeIf { queuedAny }
                if (sessionJob != null) when {
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
        askCancelReason = null
        val turnResult = awaitAssistantReply(transcript)
        val result = turnResult.reply
        val action = turnResult.action
        // A proposal the handler settled is journalled even when the turn was cancelled meanwhile: a dispatch
        // already made is never undone, so its true outcome must be on record. Speech is skipped if cancelled.
        if (action != null) {
            proposalResult(action, transcript, decodeMs, why, silent = turnResult.cancelled || stopRequested.get())
            return
        }
        // Cancelled (local phrase, name barge-in or hard stop): nothing is said, a late answer is dropped. A
        // proposal without a handler result was cancelled before dispatch.
        val noReply = result == null || result is AssistantReply.Aborted
        if (noReply || result is AssistantReply.Proposal || stopRequested.get()) {
            runCatching { clearDialogHook() }
            val refusal = if (stopRequested.get()) VoiceRefusal.HARD_STOP else askCancelReason ?: VoiceRefusal.BARGE_IN
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
            null, is AssistantReply.Proposal, AssistantReply.Aborted -> Unit // handled above
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
        if (gate.ttsEnabled() && runCatching { ttsEngine.speak(result.text, language) }.getOrDefault(false)) {
            noteOwnSpeech(result.text)
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
            cancellableAskJob = askJob
            statusTurn()?.waitingAssistant()
            askJob.start()
            try {
                askJob.join()
                cancelled = askJob.isCancelled
            } finally {
                cancellableAskJob = null
            }
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

        // Session-stop reason of the play_music close; a new phrase never cancels that one.
        private const val REASON_PLAY_MUSIC = "play_music"

        // Session-stop reasons that keep the last terminal status on the widget for its dwell: the
        // session closing itself after a reply. A failure (reason "error:...") does too.
        private val LINGER_REASONS = setOf("auto-close", "user-stop", REASON_PLAY_MUSIC)

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
