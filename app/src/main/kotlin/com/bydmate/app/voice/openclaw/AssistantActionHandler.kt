package com.bydmate.app.voice.openclaw

import com.bydmate.app.agent.ActionExecutor
import com.bydmate.app.agent.ActionOrigin
import com.bydmate.app.agent.ActionOutcome
import com.bydmate.app.agent.ActionRegistry
import com.bydmate.app.agent.ActionRejection
import com.bydmate.app.agent.ActionValidating
import com.bydmate.app.agent.ActionValidation
import com.bydmate.app.agent.ExecutionHooks
import com.bydmate.app.agent.ValidatedAction
import com.bydmate.app.voice.VoiceJournalEntry
import com.bydmate.app.voice.VoiceJournalEntry.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/** Why pending proposals were cancelled; code-only, never spoken. */
enum class CancelReason { OPT_OUT, BARGE_IN, LOCAL_PRIORITY, SHUTDOWN }

/** Text a turn carries in addition to the driver's words (manifest, instruction, last result). */
interface OpenClawTurnAttachments {
    fun forTurn(turnKey: String): String
    fun onAdmitted(turnKey: String)

    object None : OpenClawTurnAttachments {
        override fun forTurn(turnKey: String): String = ""
        override fun onAdmitted(turnKey: String) = Unit
    }
}

interface AssistantActionLifecycle {
    suspend fun onOptOut()
    fun onConversationReset()
    fun onIdentityForgotten()

    object None : AssistantActionLifecycle {
        override suspend fun onOptOut() = Unit
        override fun onConversationReset() = Unit
        override fun onIdentityForgotten() = Unit
    }
}

data class ProposalInput(val turnKey: String, val envelope: ParsedEnvelope, val recovered: Boolean, val startedAtMs: Long)

/**
 * What the alias dialog shows. [proposalId] is an opaque id of this one presentation (a fresh UUID per attempt), not
 * the protocol proposal id: the handler maps it back to the operation's ledger ticket, so a Save or Dismiss from an
 * old dialog can only ever reach the operation that presented it, never another one that reuses the protocol ids.
 * Protocol reports keep the original proposal id. The consumer must treat it as opaque and hand it back unchanged.
 */
data class AliasProposal(
    val requestId: String,
    val proposalId: String,
    val phrase: String,
    val action: ValidatedAction,
    val summary: String,
)

fun interface AliasProposalSink {
    /** True when the proposal was shown to the driver; false when it cannot be taught now. */
    fun present(p: AliasProposal): Boolean
}

/** Lets the alias coordinator (issue 5) bind itself later without changing the DI graph. */
class AliasProposalSinkHolder : AliasProposalSink {
    @Volatile
    var target: AliasProposalSink? = null
    val bound: Boolean get() = target != null
    override fun present(p: AliasProposal): Boolean = target?.present(p) ?: false
}

sealed interface AssistantActionResult {
    data class Spoken(val line: String, val outcome: VoiceJournalEntry.Outcome, val label: String) : AssistantActionResult
    data class AliasPresented(val proposal: AliasProposal) : AssistantActionResult
    data object Silent : AssistantActionResult
}

interface AssistantActionHandler : OpenClawTurnAttachments, AssistantActionLifecycle {
    suspend fun handle(input: ProposalInput): AssistantActionResult
    fun cancelPending(reason: CancelReason)
    fun aliasSaved(proposalId: String)
    fun aliasDismissed(proposalId: String)
}

object NoAssistantActions : AssistantActionHandler {
    override suspend fun handle(input: ProposalInput): AssistantActionResult = AssistantActionResult.Silent
    override fun cancelPending(reason: CancelReason) = Unit
    override fun aliasSaved(proposalId: String) = Unit
    override fun aliasDismissed(proposalId: String) = Unit
    override fun forTurn(turnKey: String): String = ""
    override fun onAdmitted(turnKey: String) = Unit
    override suspend fun onOptOut() = Unit
    override fun onConversationReset() = Unit
    override fun onIdentityForgotten() = Unit
}

/**
 * Turns one assistant proposal into at most one car or navigation action, and keeps the ledger honest about it.
 *
 * The check order is the safety property: the ledger entry is written first (a repeated turn or proposal id is
 * silent), then the envelope, the recovered flag, correlation, freshness and the validator, and the write to the
 * `DISPATCHING` state comes last and can veto the dispatch. Assistant prose is never used for speech or for the
 * confirmation text; every line comes from [PolishActionText] and the registry.
 *
 * Ownership. Admission registers the proposal as a live operation under the same lock that [cancelPending] takes, so
 * a cancel can never fall between the ledger write and the registration. Everything after admission (checks,
 * validator, confirmation, dispatch, settlement) runs in one handler-owned job; the caller only waits for it, so a
 * cancelled caller is never held up by that work. The caller's cancellation reaches the final veto directly
 * ([Live.callerGone]), and the ledger arbitrates a cancel against the `DISPATCHING` write: exactly one wins. After the
 * write the dispatch finishes and its true result is returned, whatever happens to the caller.
 *
 * Every ledger move uses the [LedgerTicket] from admission. A completion that no longer owns its entry (forgotten,
 * another identity, pruned, a restart) settles nothing and writes no report; if it had already dispatched, the driver
 * still hears the true result and nothing is replayed. A report is written only together with the state it describes,
 * so a cancellation winner keeps its settlement and its report.
 *
 * A failed report write loses that report; nothing ever re-runs an action to repair it.
 */
@Suppress("TooManyFunctions") // One handler owns admission, cancellation, settlement and report delivery.
// Keep safety collaborators, timing, code-only logging and the execution scope explicit.
class LedgerAssistantActionHandler @Suppress("LongParameterList") constructor(
    private val registry: ActionRegistry,
    private val validator: ActionValidating,
    private val ledger: ActionLedger,
    private val executor: ActionExecutor,
    private val aliasSink: AliasProposalSink,
    private val now: () -> Long = System::currentTimeMillis,
    /** Code-only diagnostics: never the assistant's text, a phrase or a destination. */
    private val log: (String) -> Unit = {},
    private val executionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AssistantActionHandler {

    /** One live operation: the proposal between its ledger admission and its settled outcome. Never persisted. */
    private class Live(val ticket: LedgerTicket, private val callerJob: Job?) {
        val cancelled = CompletableDeferred<Unit>()

        /**
         * Serializes the cancellation decision (including a failed write, which still cancels) with the admission of
         * the dispatch write. Whoever takes it first wins: a completed cancellation vetoes every later dispatch, and
         * once `DISPATCHING` is written a cancel can no longer claim the proposal. Held across ledger writes only.
         */
        val lock = Any()

        /** Set when a ledger write that the proposal needed before dispatch failed: not a cancellation. */
        @Volatile var persistVeto = false

        /** Set when this operation itself wrote `DISPATCHING`; the truth about "did it run", never re-read. */
        @Volatile var dispatching = false

        /** The caller was cancelled (barge-in). Read directly at the veto, not through a handler or a re-read. */
        val callerGone: Boolean get() = callerJob?.isActive == false
    }

    /** Terminal alias callbacks that arrive while [AliasProposalSink.present] is still running are held here. */
    private class AliasSlot {
        var buffered: LedgerState? = null
    }

    /** Admission and [cancelPending]'s snapshot take this lock: no proposal is ever admitted but unregistered. */
    private val admission = Any()
    private val live: MutableSet<Live> = ConcurrentHashMap.newKeySet()

    /** Alias presentations by opaque presentation id, and the callbacks held while a sink call runs. Both under [aliasLock]. */
    private val presentations = HashMap<String, LedgerTicket>()
    private val aliasSlots = HashMap<String, AliasSlot>()
    private val aliasLock = Any()

    /** The report offered to a turn: which turn, its order, and the receipt for exactly that report. Immutable. */
    private class Offer(val turnKey: String, val seq: Long, val receipt: ReportReceipt)

    private val offered = AtomicReference<Offer?>(null)

    /** Test seam: called with "offered" (in forTurn, before the offer is installed) and "ack" (in onAdmitted, before it is applied). */
    @Volatile
    internal var reportHook: ((String) -> Unit)? = null

    /** Test seam: runs between the last veto check and the `DISPATCHING` write. */
    @Volatile
    internal var beforeDispatchWriteHook: (() -> Unit)? = null

    /** Alias slots and tickets currently registered; for tests (nothing may leak after a clear). */
    internal val aliasRegistrations: Int get() = synchronized(aliasLock) { presentations.size + aliasSlots.size }

    /** Proposals with an owned job still running; for tests and diagnostics. */
    internal val activeProposals: Int get() = live.size

    override suspend fun handle(input: ProposalInput): AssistantActionResult {
        val turnKey = input.turnKey
        val parsed = input.envelope
        val envelope = (parsed as? ParsedEnvelope.Ok)?.envelope
        // An invalid envelope has no usable id; the synthetic one is stable per turn and never leaves the device.
        val proposalId = envelope?.proposalId ?: "invalid-${OpenClawProtocol.turnTag(turnKey)}"
        val callerJob = currentCoroutineContext()[Job]

        var admitted: Live? = null
        val result = synchronized(admission) {
            ledger.admit(turnKey, proposalId).also {
                if (it is AdmitResult.Admitted) admitted = Live(it.ticket, callerJob).also(live::add)
            }
        }
        when (result) {
            AdmitResult.Duplicate -> return AssistantActionResult.Silent
            AdmitResult.Unavailable -> {
                log("action_rejected:${ActionRejection.LEDGER_UNAVAILABLE.name}")
                return AssistantActionResult.Spoken(
                    PolishActionText.rejection(ActionRejection.LEDGER_UNAVAILABLE), Outcome.ERROR, LABEL_REJECTED,
                )
            }
            is AdmitResult.Admitted -> Unit
        }
        val state = checkNotNull(admitted)

        val job: Deferred<AssistantActionResult> = executionScope.async {
            try {
                pipeline(input, parsed, envelope, state)
            } finally {
                live.remove(state)
            }
        }
        return try {
            job.await()
        } catch (e: CancellationException) {
            // The caller went away. The ledger decides: if the cancel wins, the proposal is over; if the dispatch
            // already won, it finishes on its own and the caller still gets the real result.
            cancelOpen(state, CANCEL_CALLER)
            if (state.cancelled.isCompleted) throw e
            try {
                withContext(NonCancellable) { job.await() }
            } catch (_: CancellationException) {
                throw e
            }
        }
    }

    private suspend fun pipeline(input: ProposalInput, parsed: ParsedEnvelope, envelope: Envelope?, state: Live): AssistantActionResult {
        val turnKey = state.ticket.turnKey
        if (envelope == null) return reject(state, (parsed as ParsedEnvelope.Invalid).reason, report = false)
        if (input.recovered) return reject(state, ActionRejection.RECOVERED_NOT_EXECUTED)
        if (envelope.requestId != OpenClawProtocol.turnTag(turnKey)) return reject(state, ActionRejection.NOT_CORRELATED)
        if (envelope.capabilities != registry.version) return reject(state, ActionRejection.STALE_CAPABILITIES)
        if (now() - input.startedAtMs > MAX_AGE_MS) return reject(state, ActionRejection.EXPIRED, to = LedgerState.EXPIRED)

        val action = when (val check = validator.validate(envelope.candidate, registry.version)) {
            is ActionValidation.Invalid -> return reject(state, check.reason)
            is ActionValidation.Valid -> check.action
        }
        // Cancelled while the checks ran: nothing is presented and nothing is dispatched.
        if (vetoed(state)) return AssistantActionResult.Silent
        if (envelope.type == TYPE_ALIAS) return presentAlias(state, envelope, action)
        return settle(state, action, execute(state, action))
    }

    // --- execution ---

    private class Executed(val outcome: ActionOutcome, val threw: Boolean)

    // Cancellation propagates; other executor failures become a local code and never reveal exception data.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun execute(state: Live, action: ValidatedAction): Executed = try {
        Executed(executor.execute(action, ActionOrigin.ASSISTANT, hooks(state)), threw = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("executor_error")
        Executed(ActionOutcome.Failed("executor_error"), threw = true)
    }

    private fun hooks(state: Live) = object : ExecutionHooks {
        override val cancelled: Deferred<Unit> get() = state.cancelled

        override fun onConfirmPending(): Unit = synchronized(state.lock) {
            when (ledger.move(state.ticket, LedgerState.CONFIRM_PENDING)) {
                TransitionResult.Applied -> Unit
                // Fail closed rather than confirm something that is not on record.
                TransitionResult.PersistFailed -> {
                    state.persistVeto = true
                    state.cancelled.complete(Unit)
                }
                TransitionResult.Stale -> state.cancelled.complete(Unit)
                // Closed by someone else (a cancel): its flag completes through cancelOpen.
                is TransitionResult.IllegalEdge -> Unit
            }
        }

        /** The last veto and the `DISPATCHING` write are one step with respect to a cancel (see [Live.lock]). */
        override suspend fun beforeDispatch(): Boolean = synchronized(state.lock) {
            if (vetoed(state)) return@synchronized false
            beforeDispatchWriteHook?.invoke()
            when (ledger.move(state.ticket, LedgerState.DISPATCHING)) {
                TransitionResult.Applied -> {
                    state.dispatching = true
                    true
                }
                TransitionResult.PersistFailed -> {
                    state.persistVeto = true
                    false
                }
                TransitionResult.Stale -> {
                    state.cancelled.complete(Unit)
                    false
                }
                is TransitionResult.IllegalEdge -> false
            }
        }
    }

    /** True when the proposal is cancelled, including a caller that is already cancelling (settled here, once). */
    private fun vetoed(state: Live): Boolean {
        if (state.callerGone) cancelOpen(state, CANCEL_CALLER)
        return state.cancelled.isCompleted
    }

    /**
     * Maps the executor's answer to one terminal ledger state, one report and one spoken line. Anything that is not
     * a clean write is handled by [outcomeOf]: an operation that already dispatched always tells the truth.
     */
    private fun settle(state: Live, action: ValidatedAction, run: Executed): AssistantActionResult {
        val label = labelOf(action)
        val dispatched = state.dispatching

        fun conclude(to: LedgerState, reason: String, status: ReportStatus, why: ReportReason, line: String, outcome: Outcome): AssistantActionResult {
            val report = OpenClawActionProtocol.reportLine(state.ticket.proposalId, status, why)
            return outcomeOf(ledger.move(state.ticket, to, reason, report), dispatched) {
                AssistantActionResult.Spoken(line, outcome, label)
            }
        }

        if (run.threw && dispatched) {
            return conclude(LedgerState.UNCERTAIN, "executor_error", ReportStatus.UNCERTAIN, ReportReason.UNCERTAIN, PolishActionText.UNCERTAIN, Outcome.ERROR)
        }
        return when (val outcome = run.outcome) {
            ActionOutcome.Done -> conclude(
                LedgerState.DONE, ReportReason.OK.name, ReportStatus.DONE, ReportReason.OK,
                PolishActionText.done(registry.summarize(action)), Outcome.OK,
            )
            is ActionOutcome.Failed -> if (dispatched) {
                conclude(LedgerState.FAILED, outcome.code, ReportStatus.FAILED, ReportReason.DISPATCH_FAILED, PolishActionText.FAILED, Outcome.ERROR)
            } else {
                reject(state, ActionRejection.NOT_EXECUTABLE)
            }
            is ActionOutcome.Blocked -> conclude(
                LedgerState.BLOCKED, ActionRejection.BLOCKED_BY_GATE.name, ReportStatus.BLOCKED, ReportReason.BLOCKED_BY_GATE,
                PolishActionText.blocked(outcome.text), Outcome.BLOCKED,
            )
            ActionOutcome.Declined -> conclude(
                LedgerState.REJECTED, ActionRejection.CONFIRM_DECLINED.name, ReportStatus.DECLINED, ReportReason.CONFIRM_DECLINED,
                PolishActionText.DECLINED, Outcome.BLOCKED,
            )
            ActionOutcome.TimedOut -> conclude(
                LedgerState.EXPIRED, ReportReason.TIMED_OUT.name, ReportStatus.EXPIRED, ReportReason.TIMED_OUT,
                PolishActionText.TIMED_OUT, Outcome.BLOCKED,
            )
            ActionOutcome.ConfirmUnavailable -> conclude(
                LedgerState.REJECTED, ActionRejection.CONFIRM_UNAVAILABLE.name, ReportStatus.REJECTED, ReportReason.CONFIRM_UNAVAILABLE,
                PolishActionText.NO_OVERLAY, Outcome.BLOCKED,
            )
            ActionOutcome.Cancelled -> if (state.persistVeto) {
                reject(state, ActionRejection.LEDGER_UNAVAILABLE, outcome = Outcome.ERROR, label = label)
            } else {
                cancelOpen(state, CANCEL_CALLER)
                AssistantActionResult.Silent
            }
        }
    }

    /**
     * The one place that decides what a settlement result means for the driver. [TransitionResult.Applied] speaks.
     * A failed write also speaks (the entry stays as it was and nothing is replayed). A result that says the
     * operation no longer owns the entry, or that someone else settled it first, speaks only when the action really
     * ran: otherwise the winner (a cancel) has already answered, and a second answer or report would overwrite it.
     */
    private fun outcomeOf(result: TransitionResult, dispatched: Boolean, speak: () -> AssistantActionResult): AssistantActionResult {
        return when (result) {
            TransitionResult.Applied -> speak()
            TransitionResult.PersistFailed -> {
                log("ledger_write_failed")
                speak()
            }
            TransitionResult.Stale, is TransitionResult.IllegalEdge -> {
                log(if (result == TransitionResult.Stale) "ledger_settle_stale" else "ledger_settle_lost")
                if (dispatched) speak() else AssistantActionResult.Silent
            }
        }
    }

    @Suppress("LongParameterList") // These fields describe one coordinated ledger settlement and driver response.
    private fun reject(
        state: Live,
        reason: ActionRejection,
        report: Boolean = true,
        to: LedgerState = LedgerState.REJECTED,
        outcome: Outcome = Outcome.NOT_UNDERSTOOD,
        label: String = LABEL_REJECTED,
    ): AssistantActionResult {
        log("action_rejected:${reason.name}")
        val line = if (report) {
            val status = if (to == LedgerState.EXPIRED) ReportStatus.EXPIRED else ReportStatus.REJECTED
            OpenClawActionProtocol.reportLine(state.ticket.proposalId, status, ReportReason.valueOf(reason.name))
        } else {
            null
        }
        return outcomeOf(ledger.move(state.ticket, to, reason.name, line), dispatched = false) {
            AssistantActionResult.Spoken(PolishActionText.rejection(reason), outcome, label)
        }
    }

    // --- cancellation ---

    /**
     * Cancels a proposal that has not dispatched. It runs under [Live.lock], the same lock as the last veto and the
     * `DISPATCHING` write, so the two never interleave: a cancel that completes (even through a failed write) is seen by
     * every later dispatch attempt, and a dispatch that was admitted first is never claimed. Safe from any thread, any
     * number of times; only the first call that wins changes anything.
     */
    private fun cancelOpen(state: Live, why: String): Unit = synchronized(state.lock) {
        val ticket = state.ticket
        val report = OpenClawActionProtocol.reportLine(ticket.proposalId, ReportStatus.CANCELLED, ReportReason.CANCELLED)
        when (ledger.move(ticket, LedgerState.CANCELLED, why, report)) {
            TransitionResult.Applied -> {
                state.cancelled.complete(Unit)
                log("cancelled:$why")
            }
            TransitionResult.Stale -> state.cancelled.complete(Unit)
            // Fail closed: it could not be recorded, so it must not run.
            TransitionResult.PersistFailed -> {
                log("ledger_write_failed:${LedgerState.CANCELLED.name}")
                state.cancelled.complete(Unit)
            }
            // Dispatching (it won) or already settled: nothing to cancel.
            is TransitionResult.IllegalEdge -> Unit
        }
    }

    override fun cancelPending(reason: CancelReason) {
        val open = synchronized(admission) { live.toList() }
        open.forEach { cancelOpen(it, reason.name) }
    }

    // --- alias proposals ---

    /**
     * Presents one alias proposal under a fresh opaque id (see [AliasProposal]). The slot and ticket for that id are
     * installed, checked and removed only under [aliasLock], the lock [onIdentityForgotten] takes to clear them, so a
     * forget never leaves a stale registration and a cleanup can only remove its own id. A callback that arrives while
     * the sink is still running is held in the slot and applied once the entry is save-pending.
     *
     * Known limit (UI only): a forget that lands between the ownership check and the sink call can still show one
     * dialog for the forgotten proposal, and the coordinator then supersedes the open proposal with it and reports that
     * consistently. Nothing it does can settle another operation: its callbacks find no ticket, and only an explicit,
     * validated Save by the driver writes an alias.
     */
    // This synchronous sink boundary owns cleanup and rejection for sink exceptions; only a fixed code is logged.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun presentAlias(state: Live, envelope: Envelope, action: ValidatedAction): AssistantActionResult {
        val ticket = state.ticket
        val presentationId = "alias-${UUID.randomUUID()}"
        val proposal = AliasProposal(envelope.requestId, presentationId, envelope.phrase.orEmpty(), action, registry.summarize(action))
        val slot = AliasSlot()
        synchronized(aliasLock) {
            presentations[presentationId] = ticket
            aliasSlots[presentationId] = slot
        }
        if (!ledger.owns(ticket)) {
            // Forgotten, cleared or replaced since admission: nothing to present and nothing to settle.
            log("alias_stale")
            releasePresentation(presentationId, slot)
            return AssistantActionResult.Silent
        }
        val accepted = try {
            aliasSink.present(proposal)
        } catch (e: Exception) {
            log("alias_sink_error")
            false
        }
        val saved = if (accepted) ledger.move(ticket, LedgerState.SAVE_PENDING) else null
        val buffered = synchronized(aliasLock) {
            aliasSlots.remove(presentationId, slot)
            slot.buffered
        }
        if (!accepted) {
            releasePresentation(presentationId, slot)
            return reject(state, ActionRejection.ALIAS_UNAVAILABLE, label = LABEL_ALIAS)
        }
        when (saved) {
            TransitionResult.Applied -> Unit
            TransitionResult.PersistFailed -> {
                log("ledger_write_failed:${LedgerState.SAVE_PENDING.name}")
                releasePresentation(presentationId, slot)
                return reject(state, ActionRejection.LEDGER_UNAVAILABLE, outcome = Outcome.ERROR, label = LABEL_ALIAS)
            }
            // Cancelled or forgotten while the dialog was being shown: the entry is not ours to save.
            else -> {
                releasePresentation(presentationId, slot)
                return AssistantActionResult.Silent
            }
        }
        buffered?.let { applyAliasResult(presentationId, it) }
        return AssistantActionResult.AliasPresented(proposal)
    }

    /** Removes this presentation's own slot and ticket, nothing else. */
    private fun releasePresentation(presentationId: String, slot: AliasSlot): Unit = synchronized(aliasLock) {
        aliasSlots.remove(presentationId, slot)
        presentations.remove(presentationId)
        Unit
    }

    override fun aliasSaved(proposalId: String) = aliasResult(proposalId, LedgerState.SAVED)

    override fun aliasDismissed(proposalId: String) = aliasResult(proposalId, LedgerState.DISMISSED)

    private fun aliasResult(presentationId: String, to: LedgerState) {
        val held = synchronized(aliasLock) {
            aliasSlots[presentationId]?.also { if (it.buffered == null) it.buffered = to }
        }
        if (held == null) applyAliasResult(presentationId, to)
    }

    /** Only the operation that presented this id in this process can settle it; an unknown id is ignored. */
    private fun applyAliasResult(presentationId: String, to: LedgerState) {
        val ticket = synchronized(aliasLock) { presentations[presentationId] } ?: return
        val saved = to == LedgerState.SAVED
        // Protocol reports name the original proposal id, never the presentation id.
        val report = OpenClawActionProtocol.reportLine(
            ticket.proposalId,
            if (saved) ReportStatus.SAVED else ReportStatus.DISMISSED,
            if (saved) ReportReason.SAVED else ReportReason.DISMISSED,
        )
        when (ledger.move(ticket, to, to.name, report)) {
            TransitionResult.PersistFailed -> log("ledger_write_failed:${to.name}")
            else -> synchronized(aliasLock) { presentations.remove(presentationId, ticket) }
        }
    }

    // --- attachments and lifecycle ---

    override fun forTurn(turnKey: String): String {
        // One atomic read gives the text and the receipt for it, so the text sent is the report that can be consumed.
        val offer = ledger.offerReport()
        reportHook?.invoke("offered")
        if (offer != null) {
            val mine = Offer(turnKey, offer.seq, offer.receipt)
            // Offers are ordered by the ledger: a slower, older forTurn can never replace a newer offer.
            offered.updateAndGet { current -> if (current == null || mine.seq > current.seq) mine else current }
        }
        // The teaching rules are offered only when something can receive a proposal.
        val teaching = (aliasSink as? AliasProposalSinkHolder)?.bound ?: true
        return listOfNotNull(
            OpenClawActionProtocol.manifest(registry, teaching),
            OpenClawActionProtocol.ACTION_INSTRUCTION,
            offer?.report?.line,
        ).joinToString("\n")
    }

    override fun onAdmitted(turnKey: String) {
        val offer = offered.get() ?: return
        if (offer.turnKey != turnKey) return
        reportHook?.invoke("ack")
        // Only the offer that is still current is retired, and only the exact report it carried is consumed. A stale
        // ack (forgotten, reset, replaced, or older than a newer offer) removes nothing; if two offers overlap the
        // newer one's ack consumes the report, so the line may be delivered twice but is never lost.
        if (!offered.compareAndSet(offer, null)) return
        if (!ledger.consumeReport(offer.receipt)) log("report_not_consumed")
    }

    override suspend fun onOptOut() = cancelPending(CancelReason.OPT_OUT)

    override fun onConversationReset() {
        offered.set(null)
        ledger.dropReport()
    }

    override fun onIdentityForgotten() {
        offered.set(null)
        synchronized(aliasLock) {
            presentations.clear()
            aliasSlots.clear()
        }
        ledger.clear()
    }

    private fun labelOf(action: ValidatedAction): String = when (action) {
        is ValidatedAction.Vehicle -> "openclaw:${action.commandId}"
        is ValidatedAction.NavigateQuery, is ValidatedAction.NavigateShortcut -> LABEL_NAVIGATE
    }

    private companion object {
        const val MAX_AGE_MS = 120_000L
        const val TYPE_ALIAS = "alias_proposal"
        const val CANCEL_CALLER = "CALLER"
        const val LABEL_REJECTED = "openclaw:rejected"
        const val LABEL_NAVIGATE = "openclaw:navigate"
        const val LABEL_ALIAS = "openclaw:alias"
    }
}
