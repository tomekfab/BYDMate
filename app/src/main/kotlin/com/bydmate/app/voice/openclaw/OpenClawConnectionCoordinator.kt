package com.bydmate.app.voice.openclaw

import android.content.Context
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.service.TailscaleStartup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal sealed interface OpenClawRecoveryState {
    data object Idle : OpenClawRecoveryState
    data class Connecting(val tailscale: TailscaleHintDiagnostic?) : OpenClawRecoveryState
    data class Retrying(val nextDelayMs: Long, val failure: OpenClawFailure?) : OpenClawRecoveryState
    data object Ready : OpenClawRecoveryState
    data class Stopped(val reason: AutomaticReconnectSkipReason?) : OpenClawRecoveryState
}

internal data class TailscaleHintDiagnostic(
    val receiverSupported: Boolean,
    val sent: Boolean,
)

/** Coordinates one service-owned automatic OpenClaw recovery session. */
@Suppress("TooManyFunctions") // One service-owned recovery session keeps admission, retries and cancellation under its monitor.
internal class OpenClawConnectionCoordinator(
    private val assistantStatus: StateFlow<OpenClawStatus>,
    private val reconnectIfEligible: suspend (onAdmitted: () -> Unit) -> AutomaticReconnectResult,
    private val reconnectManually: suspend () -> OpenClawLinkState,
    private val requestTailscale: (Context) -> TailscaleStartup.ConnectionRequestResult,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    // Records automatic decisions with fixed codes only. A test seam; a failure never affects recovery.
    @Suppress("SpreadOperator") // at most five fields per decision
    private val diagnostic: (String, List<Pair<String, String>>) -> Unit = { what, fields ->
        Trace.event(TraceArea.NET, what, *fields.toTypedArray())
    },
) {
    private val mutableRecovery = MutableStateFlow<OpenClawRecoveryState>(OpenClawRecoveryState.Idle)
    val recovery: StateFlow<OpenClawRecoveryState> = mutableRecovery.asStateFlow()
    private val mutableTailscaleHint = MutableStateFlow<TailscaleHintDiagnostic?>(null)
    /** Last best-effort VPN startup request. This is never a connection signal. */
    val tailscaleHint: StateFlow<TailscaleHintDiagnostic?> = mutableTailscaleHint.asStateFlow()

    private val monitor = Any()
    private var session: Session? = null
    private var attemptJob: Job? = null
    private var retryJob: Job? = null
    private var statusJob: Job? = null
    private var networkJob: Job? = null
    private var retryIndex = 0
    // The automatic event coalesced while an attempt runs (null: none), and whether any of them asked for a hint.
    private var pendingAutomatic: TriggerOrigin? = null
    private var pendingManual = false
    private var pendingTailscaleHint = false
    private var staleSkips = 0

    /** Starts a session that is owned by exactly one foreground service instance. */
    fun start(serviceContext: Context, serviceScope: CoroutineScope, reachableNetworkEdges: Flow<Long>) {
        val next = synchronized(monitor) {
            cancelJobsLocked()
            retryIndex = 0
            pendingAutomatic = null
            pendingManual = false
            pendingTailscaleHint = false
            staleSkips = 0
            mutableRecovery.value = OpenClawRecoveryState.Idle
            mutableTailscaleHint.value = null
            Session(serviceContext, serviceScope).also { session = it }
        }

        // Both collectors belong to the session that created them: a value they deliver after a
        // stop or restart is rejected under the monitor instead of reaching the newer session.
        val newStatusJob = serviceScope.launch {
            var first = true
            assistantStatus.collect { status ->
                if (first) first = false else onStatusChanged(next, status)
            }
        }
        val newNetworkJob = serviceScope.launch {
            reachableNetworkEdges.collect { edge ->
                if (edge > 0L) enqueueAutomatic(next, TriggerOrigin.Network)
            }
        }
        synchronized(monitor) {
            if (session === next) {
                statusJob = newStatusJob
                networkJob = newNetworkJob
                enqueueAutomaticLocked(next, TriggerOrigin.Start)
            } else {
                newStatusJob.cancel()
                newNetworkJob.cancel()
            }
        }
    }

    /** A meaningful service wake: it may request Tailscale once and reevaluate OpenClaw now. */
    fun onWake() {
        synchronized(monitor) { session?.let { enqueueAutomaticLocked(it, TriggerOrigin.Wake) } }
    }

    /** Cancels this service session only. It never clears pairing or sends a command. */
    fun stop() = synchronized(monitor) {
        if (session == null) return
        cancelJobsLocked()
        session = null
        pendingAutomatic = null
        pendingManual = false
        pendingTailscaleHint = false
        mutableRecovery.value = OpenClawRecoveryState.Stopped(reason = null)
    }

    /** Uses the same session queue when present; otherwise retains the existing direct reconnect path. */
    suspend fun requestManualReconnect() {
        val direct = synchronized(monitor) {
            if (session == null) true else {
                enqueueManualLocked()
                false
            }
        }
        if (direct) reconnectManually()
    }

    private fun enqueueAutomatic(owner: Session, origin: TriggerOrigin) = synchronized(monitor) {
        if (session === owner) enqueueAutomaticLocked(owner, origin)
    }

    private fun enqueueAutomaticLocked(activeSession: Session, origin: TriggerOrigin) {
        if (attemptJob != null) {
            // The recorded origin follows the hint: it names the event the coalesced attempt is for.
            if (pendingAutomatic == null || (origin.tailscaleHint && !pendingTailscaleHint)) pendingAutomatic = origin
            pendingTailscaleHint = pendingTailscaleHint || origin.tailscaleHint
            return
        }
        retryJob?.cancel()
        retryJob = null
        startAutomaticLocked(activeSession, origin, origin.tailscaleHint)
    }

    private fun enqueueManualLocked() {
        val activeSession = session ?: return
        if (attemptJob != null) {
            pendingManual = true
            return
        }
        retryJob?.cancel()
        retryJob = null
        startManualLocked(activeSession)
    }

    private fun startAutomaticLocked(activeSession: Session, origin: TriggerOrigin, tailscaleHint: Boolean) {
        attemptJob = activeSession.scope.launch {
            val self = coroutineContext[Job]
            val result = reconnectIfEligible { onAdmitted(activeSession, self, origin, tailscaleHint) }
            synchronized(monitor) { finishAutomaticLocked(activeSession, self, origin, result) }
        }
    }

    /**
     * Runs only after the assistant admitted the attempt, so a skipped admission sends no hint.
     * The hint is a best-effort diagnostic: the assistant still rechecks its generation afterwards.
     */
    private fun onAdmitted(activeSession: Session, self: Job?, origin: TriggerOrigin, tailscaleHint: Boolean) {
        if (!isCurrentAttempt(activeSession, self)) return
        val hint = if (tailscaleHint) runCatching {
            requestTailscale(activeSession.context).let { TailscaleHintDiagnostic(it.receiverSupported, it.sent) }
        }.getOrNull() else null
        synchronized(monitor) {
            if (session !== activeSession || attemptJob !== self) return
            if (hint != null) mutableTailscaleHint.value = hint
            mutableRecovery.value = OpenClawRecoveryState.Connecting(hint)
            val outcome = when {
                !tailscaleHint -> listOf(FIELD_HINT to "not_requested")
                hint == null -> listOf(FIELD_HINT to "request_error")
                else -> listOf(
                    FIELD_HINT to "requested",
                    "receiver" to if (hint.receiverSupported) "supported" else "missing",
                    "sent" to if (hint.sent) "yes" else "no",
                )
            }
            recordLocked(origin, "admitted", outcome)
        }
    }

    private fun isCurrentAttempt(activeSession: Session, self: Job?): Boolean =
        synchronized(monitor) { session === activeSession && attemptJob === self }

    private fun startManualLocked(activeSession: Session) {
        attemptJob = activeSession.scope.launch {
            val self = coroutineContext[Job]
            synchronized(monitor) {
                if (session !== activeSession || attemptJob !== self) return@launch
                mutableRecovery.value = OpenClawRecoveryState.Connecting(tailscale = null)
            }
            val result = reconnectManually()
            synchronized(monitor) { finishManualLocked(activeSession, self, result) }
        }
    }

    private fun finishAutomaticLocked(
        activeSession: Session,
        self: Job?,
        origin: TriggerOrigin,
        result: AutomaticReconnectResult,
    ) {
        // A stopped or replaced attempt may still return a late result; it must change nothing.
        if (session !== activeSession || attemptJob !== self) return
        attemptJob = null
        if (result is AutomaticReconnectResult.Skipped) {
            recordLocked(origin, "skipped", listOf("reason" to result.reason.code))
        }
        if (pendingManual) {
            pendingManual = false
            startManualLocked(activeSession)
            return
        }
        when (result) {
            is AutomaticReconnectResult.Attempted -> reconcileLocked(
                activeSession, returnedReady = result.link == OpenClawLinkState.Ready,
                failure = result.link.failureOrNull(), skip = null,
            )
            is AutomaticReconnectResult.Skipped -> reconcileLocked(
                activeSession, returnedReady = false, failure = null, skip = result.reason,
            )
        }
    }

    private fun finishManualLocked(activeSession: Session, self: Job?, result: OpenClawLinkState) {
        if (session !== activeSession || attemptJob !== self) return
        attemptJob = null
        reconcileLocked(
            activeSession, returnedReady = result == OpenClawLinkState.Ready,
            failure = result.failureOrNull(), skip = null,
        )
    }

    /**
     * Decides the next step from the latest assistant status, not from the returned link alone: the
     * link can drop or the settings can change between a connect returning and this running, and
     * the status collector ignores those events while an attempt is in flight. A terminal status
     * wins over everything, a Ready status ends recovery, and a returned Ready that the status no
     * longer shows is a lost link that retries on the timer.
     */
    private fun reconcileLocked(
        activeSession: Session,
        returnedReady: Boolean,
        failure: OpenClawFailure?,
        skip: AutomaticReconnectSkipReason?,
    ) {
        if (skip == null) staleSkips = 0
        val latest = assistantStatus.value
        val terminal = terminalReason(latest)
        if (terminal != null) {
            stopAutomaticLocked(terminal, cancelAttempt = false)
            return
        }
        if (latest.link == OpenClawLinkState.Ready) {
            finishReadyLocked()
            return
        }
        val pending = pendingAutomatic
        if (pending != null) {
            val tailscaleHint = pendingTailscaleHint
            pendingAutomatic = null
            pendingTailscaleHint = false
            startAutomaticLocked(activeSession, pending, tailscaleHint)
            return
        }
        when {
            skip != null -> {
                // The assistant skipped, yet the latest status is eligible: the settings changed while the
                // attempt ran. Evaluate once more now (no hint: nothing was admitted), then use the timer so
                // a persistent mismatch cannot spin.
                if (staleSkips++ == 0) startAutomaticLocked(activeSession, TriggerOrigin.Stale, tailscaleHint = false)
                else scheduleRetryLocked(activeSession, null)
            }
            returnedReady -> {
                // Ready was reached, so the backoff starts over; the drop itself is retried on the timer.
                retryIndex = 0
                scheduleRetryLocked(activeSession, latest.link.failureOrNull())
            }
            else -> scheduleRetryLocked(activeSession, failure)
        }
    }

    private fun terminalReason(status: OpenClawStatus): AutomaticReconnectSkipReason? = when {
        !status.enabled -> AutomaticReconnectSkipReason.Disabled
        !status.endpointValid -> AutomaticReconnectSkipReason.InvalidEndpoint
        status.link is OpenClawLinkState.AwaitingApproval -> AutomaticReconnectSkipReason.AwaitingApproval
        status.link == OpenClawLinkState.Revoked -> AutomaticReconnectSkipReason.Revoked
        status.link == OpenClawLinkState.Unpaired -> AutomaticReconnectSkipReason.Unpaired
        else -> null
    }

    private fun finishReadyLocked() {
        retryJob?.cancel()
        retryJob = null
        retryIndex = 0
        staleSkips = 0
        pendingAutomatic = null
        pendingTailscaleHint = false
        mutableRecovery.value = OpenClawRecoveryState.Ready
    }

    private fun scheduleRetryLocked(activeSession: Session, failure: OpenClawFailure?) {
        if (session !== activeSession) return
        val delayMs = RETRY_DELAYS_MS[retryIndex.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        retryIndex++
        mutableRecovery.value = OpenClawRecoveryState.Retrying(delayMs, failure)
        retryJob = activeSession.scope.launch {
            retryDelay(delayMs)
            synchronized(monitor) {
                if (session !== activeSession || retryJob !== coroutineContext[Job]) return@launch
                retryJob = null
                // A timer retries OpenClaw only. Tailscale hints are reserved for service events.
                startAutomaticLocked(activeSession, TriggerOrigin.Timer, tailscaleHint = false)
            }
        }
    }

    private fun onStatusChanged(owner: Session, status: OpenClawStatus) = synchronized(monitor) {
        if (session !== owner) return
        val terminal = terminalReason(status)
        when {
            terminal != null -> stopAutomaticLocked(terminal, cancelAttempt = true)
            status.link == OpenClawLinkState.Ready -> finishReadyLocked()
            attemptJob == null && retryJob == null -> enqueueAutomaticLocked(owner, TriggerOrigin.Status)
            // Events during an attempt (its own Connecting/Failed, or a drop) are reconciled when it
            // completes; events while a retry is pending leave its chosen backoff intact.
        }
    }

    private fun stopAutomaticLocked(reason: AutomaticReconnectSkipReason, cancelAttempt: Boolean) {
        retryJob?.cancel()
        retryJob = null
        if (cancelAttempt) attemptJob?.cancel()
        attemptJob = null
        pendingAutomatic = null
        pendingManual = false
        pendingTailscaleHint = false
        mutableRecovery.value = OpenClawRecoveryState.Stopped(reason)
    }

    private fun cancelJobsLocked() {
        attemptJob?.cancel()
        retryJob?.cancel()
        statusJob?.cancel()
        networkJob?.cancel()
        attemptJob = null
        retryJob = null
        statusJob = null
        networkJob = null
    }

    /**
     * One automatic decision for the trace, with fixed codes only: never an endpoint, identity,
     * request id, text, token or exception message. Callers hold the monitor and have already
     * checked that the attempt belongs to the current session.
     */
    private fun recordLocked(origin: TriggerOrigin, admission: String, outcome: List<Pair<String, String>>) {
        runCatching {
            diagnostic(TRACE_EVENT, listOf("origin" to origin.code, "admission" to admission) + outcome)
        }
    }

    /** What started an automatic attempt. Only service events may request a Tailscale hint. */
    private enum class TriggerOrigin(val code: String, val tailscaleHint: Boolean) {
        Start("start", tailscaleHint = true),
        Wake("wake", tailscaleHint = true),
        Network("network", tailscaleHint = true),
        Timer("timer", tailscaleHint = false),
        Status("status", tailscaleHint = false),
        Stale("stale", tailscaleHint = false),
    }

    private val AutomaticReconnectSkipReason.code: String
        get() = when (this) {
            AutomaticReconnectSkipReason.Disabled -> "disabled"
            AutomaticReconnectSkipReason.InvalidEndpoint -> "invalid_endpoint"
            AutomaticReconnectSkipReason.Unpaired -> "unpaired"
            AutomaticReconnectSkipReason.AwaitingApproval -> "awaiting_approval"
            AutomaticReconnectSkipReason.Revoked -> "revoked"
            AutomaticReconnectSkipReason.IdentityChanged -> "identity_changed"
        }

    private data class Session(val context: Context, val scope: CoroutineScope)

    private fun OpenClawLinkState.failureOrNull(): OpenClawFailure? =
        (this as? OpenClawLinkState.Failed)?.failure

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(5_000, 10_000, 20_000, 40_000, 60_000)
        const val TRACE_EVENT = "openclaw_auto"
        const val FIELD_HINT = "hint"
    }
}
