package com.bydmate.app.voice.openclaw

import android.content.Context
import android.content.SharedPreferences
import com.bydmate.app.agent.ActionRejection
import com.bydmate.app.voice.AssistantReply
import com.bydmate.app.voice.PolishVoiceReplies.Problem
import com.bydmate.app.voice.VoiceAssistant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The surface of [OpenClawClient] this feature needs; a seam so tests can script the transport. */
interface OpenClawChat {
    val linkState: StateFlow<OpenClawLinkState>
    /**
     * One connect attempt. Implementations must capture their connection-lifecycle generation
     * before the first suspension: [OpenClawAssistant] starts this call under its mutex so that a
     * later opt-out or forget invalidates it instead of racing ahead of it.
     */
    suspend fun connect(): OpenClawLinkState
    /**
     * Like [connect], but must never create an identity: automatic recovery may only use an existing
     * pairing. The default keeps plain fakes compiling.
     */
    suspend fun connectPaired(): OpenClawLinkState = connect()
    suspend fun pair(): OpenClawLinkState
    suspend fun ask(text: String, onDelta: (String) -> Unit): OpenClawTurnResult
    /** An action turn: carries the capability manifest. The default keeps plain fakes compiling. */
    suspend fun askForActions(text: String, onDelta: (String) -> Unit): OpenClawTurnResult = ask(text, onDelta)
    suspend fun resendUncertain(onDelta: (String) -> Unit): OpenClawTurnResult
    suspend fun cancel()
    /** Stops an in-flight handshake or pairing poll without forgetting the paired device. */
    suspend fun cancelConnectionOperations() = Unit
    suspend fun resetConversation()
    suspend fun disconnect()
}

/** The production [OpenClawChat]: a thin delegate to the paired WebSocket client. */
class OpenClawClientChat(private val client: OpenClawClient) : OpenClawChat {
    override val linkState: StateFlow<OpenClawLinkState> get() = client.linkState
    override suspend fun connect() = client.connect()
    override suspend fun connectPaired() = client.connectPaired()
    override suspend fun pair() = client.pair()
    override suspend fun ask(text: String, onDelta: (String) -> Unit) = client.ask(text, onDelta)
    override suspend fun askForActions(text: String, onDelta: (String) -> Unit) = client.askForActions(text, onDelta)
    override suspend fun resendUncertain(onDelta: (String) -> Unit) = client.resendUncertain(onDelta)
    override suspend fun cancel() = client.cancel()
    override suspend fun cancelConnectionOperations() = client.cancelConnectionOperations()
    override suspend fun resetConversation() = client.resetConversation()
    override suspend fun disconnect() = client.disconnect()
}

/** The stored pairing: device identity, token and conversation. [clear] forgets all of it. */
interface OpenClawCredentials {
    fun exist(): Boolean
    fun clear()
}

class StoredOpenClawCredentials(
    private val identity: OpenClawIdentityStore,
    private val conversation: OpenClawConversationStore,
    private val onForget: () -> Unit = {},
) : OpenClawCredentials {
    override fun exist(): Boolean = identity.isPaired()
    override fun clear() {
        try {
            identity.clear()
            conversation.clear()
        } finally {
            onForget()
        }
    }
}

/**
 * The user's choices for the assistant, kept apart from the Russian agent switch: turning the
 * Russian agent on never turns this on. Stored per device and never backed up (the prefs file is
 * not in the backup whitelist), because a pairing does not move to another device either.
 */
interface OpenClawPrefs {
    /** The separate online opt-in. Off by default. */
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)

    /** The private `wss://` Gateway address, with the owner's host as the default. */
    fun endpointText(): String
    fun setEndpointText(text: String)
}

class SharedPreferencesOpenClawPrefs(private val prefs: SharedPreferences) : OpenClawPrefs {
    constructor(context: Context) : this(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))

    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    override fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    override fun endpointText(): String = prefs.getString(KEY_ENDPOINT, null)
        ?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT
    override fun setEndpointText(text: String) = prefs.edit().putString(KEY_ENDPOINT, text.trim()).apply()

    companion object {
        const val FILE_NAME = "openclaw_assistant"
        const val DEFAULT_ENDPOINT = "wss://bobs-mac-mini-openclaw.tail3549c7.ts.net/"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENDPOINT = "endpoint"
    }
}

/** How the settings screen shows the assistant right now. */
data class OpenClawStatus(
    val enabled: Boolean,
    val endpoint: String,
    val endpointValid: Boolean,
    val link: OpenClawLinkState,
)

/** The safe outcome of asking the assistant to reconnect without a user action. */
internal sealed interface AutomaticReconnectResult {
    data class Attempted(val link: OpenClawLinkState) : AutomaticReconnectResult
    data class Skipped(val reason: AutomaticReconnectSkipReason) : AutomaticReconnectResult
}

/** Why automatic recovery must not create or use a connection. */
internal enum class AutomaticReconnectSkipReason {
    Disabled,
    InvalidEndpoint,
    Unpaired,
    AwaitingApproval,
    Revoked,
    IdentityChanged,
}

/**
 * The text-only OpenClaw assistant: one place for the opt-in check, the endpoint, the paired
 * client and the mapping of every outcome to a Polish message. The voice session and the settings
 * connection test both go through [ask], so the test exercises the production transport,
 * identity and conversation. Nothing here touches the microphone, a speech model or the car.
 *
 * It is the only online path of the Polish profile: a failure is reported, never retried through
 * another provider.
 */
@Suppress("TooManyFunctions") // One opt-in lifecycle owns turns, pairing, reset and identity cleanup.
class OpenClawAssistant(
    prefs: OpenClawPrefs,
    private val chatFor: (OpenClawEndpoint) -> OpenClawChat,
    private val credentials: OpenClawCredentials,
    turnDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val actionLifecycle: AssistantActionLifecycle = AssistantActionLifecycle.None,
) : OpenClawPreferenceAccess(prefs) {

    private val lock = Mutex()
    // Both turn admission and opt-out own this mutex. A queued turn also checks its generation.
    private var turnGeneration = 0L
    // Opt-out, forget and an address change bump this. Automatic recovery binds its connect to the
    // value it was admitted under, checked at transport entry while the mutex is still held.
    private var reconnectGeneration = 0L
    // A forget is running: no connection may be admitted or created until its cleanup is finished.
    private var forgetting = 0
    private val activeTurns = mutableSetOf<Job>()

    // Turns run here, not in the caller's coroutine: when a caller gives up (a local phrase, the
    // orb button) the turn is aborted on the Gateway first, and its late answer is dropped by the
    // client. Cancelling the client call directly would clear its active turn before an abort
    // could be sent.
    private val turns = CoroutineScope(SupervisorJob() + turnDispatcher)
    @Volatile private var chat: OpenClawChat? = null
    @Volatile private var chatEndpoint: String? = null
    private val connection = AssistantConnection()
    private val statusFlow = MutableStateFlow(connection.snapshot(connection.initialLink()))
    val status: StateFlow<OpenClawStatus> = statusFlow.asStateFlow()

    /** Turns the opt-in on or off. Turning it off also stops a running turn; pairing is kept. */
    suspend fun setEnabled(enabled: Boolean) {
        var toStop: OpenClawChat? = null
        var turnsToStop: List<Job> = emptyList()
        var cleanupRequired = false
        try {
            lock.withLock {
                prefs.setEnabled(enabled)
                if (!enabled) {
                    turnGeneration++
                    reconnectGeneration++
                    toStop = chat
                    turnsToStop = activeTurns.toList()
                    cleanupRequired = true
                }
                connection.refreshLocked()
            }
        } finally {
            if (cleanupRequired) withContext(NonCancellable) {
                // A failed status snapshot or cancelled caller must not discard live handles.
                // Each cleanup stage runs even if the previous transport operation throws.
                try {
                    try {
                        try {
                            // A pending confirmation must not outlive the opt-out.
                            actionLifecycle.onOptOut()
                        } finally {
                            toStop?.cancel()
                        }
                    } finally {
                        toStop?.cancelConnectionOperations()
                    }
                } finally {
                    turnsToStop.forEach { it.cancel() }
                    try {
                        turnsToStop.forEach { it.join() }
                    } finally {
                        lock.withLock { activeTurns.removeAll(turnsToStop.toSet()) }
                    }
                }
            }
        }
        if (!enabled) connection.refresh()
    }

    /**
     * Saves the Gateway address. While a device is paired the address cannot change: the stored
     * token belongs to the old host, so the device must be disconnected first.
     * @return false when the address was refused.
     */
    suspend fun setEndpoint(text: String): Boolean = lock.withLock {
        val trimmed = text.trim()
        if (trimmed == prefs.endpointText()) return@withLock true
        if (credentials.exist()) return@withLock false
        prefs.setEndpointText(trimmed)
        reconnectGeneration++
        chat = null
        chatEndpoint = null
        statusFlow.value = connection.snapshot(connection.initialLink())
        true
    }

    /**
     * Starts pairing: connects and waits for the owner to approve the device on the Gateway host.
     * Requires the opt-in and a valid address; otherwise nothing is sent.
     */
    suspend fun pair(): OpenClawLinkState {
        // Acquire the client and publish Connecting in one section: a forget cannot slip in between.
        val client = lock.withLock {
            connection.clientLocked()?.also { statusFlow.value = connection.snapshot(OpenClawLinkState.Connecting) }
        } ?: return connection.refreshAndReturn()
        val result = client.pair()
        connection.refresh()
        return result
    }

    /** One connect attempt with the stored token (reconnect). */
    suspend fun reconnect(): OpenClawLinkState {
        return connectBound() ?: connection.refreshAndReturn()
    }

    /**
     * Runs one reconnect only when automatic recovery can safely use the existing pairing.
     * Admission is deliberately separate from the connect: that reacquires [lock].
     *
     * [onAdmitted] runs once, outside the mutex, after admission passed and before the connect.
     * It is a best-effort diagnostic hook (the Tailscale request). It never widens admission:
     * the connect still has to pass the generation check, so an opt-out or forget that happens
     * during the hook skips the connect.
     */
    internal suspend fun reconnectIfEligible(onAdmitted: () -> Unit = {}): AutomaticReconnectResult {
        val admission = lock.withLock {
            when {
                !prefs.isEnabled() -> AutomaticReconnectAdmission.Ineligible(AutomaticReconnectSkipReason.Disabled)
                connection.endpointOrNull() == null -> AutomaticReconnectAdmission.Ineligible(AutomaticReconnectSkipReason.InvalidEndpoint)
                forgetting > 0 || !credentials.exist() -> AutomaticReconnectAdmission.Ineligible(AutomaticReconnectSkipReason.Unpaired)
                else -> when (connection.currentLinkLocked()) {
                    is OpenClawLinkState.AwaitingApproval ->
                        AutomaticReconnectAdmission.Ineligible(AutomaticReconnectSkipReason.AwaitingApproval)
                    OpenClawLinkState.Revoked ->
                        AutomaticReconnectAdmission.Ineligible(AutomaticReconnectSkipReason.Revoked)
                    OpenClawLinkState.Unpaired ->
                        AutomaticReconnectAdmission.Ineligible(AutomaticReconnectSkipReason.Unpaired)
                    else -> AutomaticReconnectAdmission.Eligible(reconnectGeneration, prefs.endpointText())
                }
            }
        }
        val eligible = when (admission) {
            is AutomaticReconnectAdmission.Ineligible -> return AutomaticReconnectResult.Skipped(admission.reason)
            is AutomaticReconnectAdmission.Eligible -> admission
        }

        runCatching(onAdmitted).onFailure { if (it is CancellationException) throw it }
        // Mutex.lock does not check cancellation when it is free, so a stopped session stops here.
        currentCoroutineContext().ensureActive()
        val link = connectBound(eligible.generation, eligible.endpoint)
            ?: return AutomaticReconnectResult.Skipped(AutomaticReconnectSkipReason.IdentityChanged)
        return AutomaticReconnectResult.Attempted(link)
    }

    /**
     * Starts the transport connect while still owning [lock], then waits for it without the lock.
     *
     * The client captures its own lifecycle generation before its first suspension. Starting it
     * [CoroutineStart.UNDISPATCHED] under the mutex therefore binds the connect to this
     * admission: an opt-out or forget needs the mutex, so it can only invalidate this connect, not
     * slip in before it and leave a fresh generation behind. The mutex is not held across the
     * network wait, which lets opt-out cancel the handshake.
     *
     * @param expectedGeneration null for a manual reconnect, which only needs the opt-in and address.
     * @return null when the admission no longer holds or no client can be built.
     */
    private suspend fun connectBound(
        expectedGeneration: Long? = null,
        expectedEndpoint: String? = null,
    ): OpenClawLinkState? = coroutineScope {
        val connecting = lock.withLock {
            val client = if (expectedGeneration == null) {
                connection.clientLocked()
            } else {
                connection.clientLocked(expectedGeneration, expectedEndpoint!!)
            } ?: return@withLock null
            statusFlow.value = connection.snapshot(OpenClawLinkState.Connecting)
            // Automatic recovery may only use an existing pairing, checked again at the client's own lifecycle.
            async(start = CoroutineStart.UNDISPATCHED) {
                if (expectedGeneration == null) client.connect() else client.connectPaired()
            }
        } ?: return@coroutineScope null
        val result = connecting.await()
        connection.refresh()
        result
    }

    /**
     * Forgets this device (credentials and conversation). Revoking it on the Gateway is separate.
     *
     * The generation bump and the [forgetting] barrier are set together under the mutex, so no
     * connection can be admitted or built while the credentials are being removed. The cleanup is
     * not cancellable and never holds the mutex across the client's socket work. The barrier is
     * released last, whatever failed; the first failure is rethrown and later ones are suppressed.
     */
    @Suppress("TooGenericExceptionCaught") // Every cleanup stage must run; the failure is rethrown below.
    suspend fun disconnect() {
        val client = lock.withLock {
            reconnectGeneration++
            forgetting++
            chat.also { chat = null; chatEndpoint = null }
        }
        withContext(NonCancellable) {
            var failure: Throwable? = null
            suspend fun stage(block: suspend () -> Unit) {
                try {
                    block()
                } catch (e: Throwable) {
                    failure?.addSuppressed(e) ?: run { failure = e }
                }
            }
            try {
                stage {
                    linkWatcher?.cancel()
                    client?.disconnect()
                }
                stage { credentials.clear() } // also when no client was ever built in this process
                // Under the mutex, so no refresh that read the old link can publish after this.
                stage { lock.withLock { statusFlow.value = connection.snapshot(OpenClawLinkState.Unpaired) } }
            } finally {
                lock.withLock { forgetting-- }
            }
            failure?.let { throw it }
        }
    }

    /** A fresh conversation with the same assistant; pairing stays. */
    suspend fun resetConversation() {
        try {
            actionLifecycle.onConversationReset()
        } finally {
            connection.clientForLocalReset()?.resetConversation()
            connection.refresh()
        }
    }

    override suspend fun cancel() {
        chat?.cancel()
    }

    /** An action turn: the reply may carry a proposal. [AssistantReply.Proposal.startedAtMs] is taken here. */
    override suspend fun ask(text: String, onDelta: (String) -> Unit): AssistantReply {
        val startedAtMs = System.currentTimeMillis()
        return admission.runTurn(startedAtMs) { it.askForActions(text, onDelta) }
    }

    /** A plain turn for the settings probe: no manifest, never a [AssistantReply.Proposal]. */
    suspend fun askText(text: String, onDelta: (String) -> Unit = {}): AssistantReply =
        admission.runTurn(proposals = false) { it.ask(text, onDelta) }

    /** Repeats the last uncertain turn under its first key. Only an explicit user action calls this. */
    suspend fun resendUncertain(onDelta: (String) -> Unit = {}): AssistantReply =
        admission.runTurn { it.resendUncertain(onDelta) }

    // --- internals ---------------------------------------------------------------------------------

    // The screen shows the live link: "waiting for approval" must be visible while pair() waits.
    private var linkWatcher: Job? = null

    private val admission = TurnAdmission()

    private sealed interface AutomaticReconnectAdmission {
        data class Eligible(val generation: Long, val endpoint: String) : AutomaticReconnectAdmission
        data class Ineligible(val reason: AutomaticReconnectSkipReason) : AutomaticReconnectAdmission
    }

    private inner class TurnAdmission {
        suspend fun runTurn(
            startedAtMs: Long = System.currentTimeMillis(),
            proposals: Boolean = true,
            call: suspend (OpenClawChat) -> OpenClawTurnResult,
        ): AssistantReply {
            lateinit var client: OpenClawChat
            val turn = lock.withLock {
                if (!prefs.isEnabled()) return problem(Problem.AI_DISABLED, PolishAssistantText.DISABLED)
                client = connection.clientLocked()
                    ?: return problem(Problem.ASSISTANT_UNAVAILABLE, PolishAssistantText.ENDPOINT_MISSING)
                val generation = turnGeneration
                turns.async turn@{
                    val request = lock.withLock {
                        if (!prefs.isEnabled() || generation != turnGeneration) return@turn OpenClawTurnResult.Aborted
                        // Invoke the transport while still owning admission, but release the mutex
                        // at its first suspension so opt-out can send an abort during the response.
                        async(start = CoroutineStart.UNDISPATCHED) { call(client) }
                    }
                    request.await()
                }.also { activeTurns += it }
            }
            val result = try {
                turn.await()
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) {
                    // Abort on the Gateway before the transport call clears its active turn.
                    turns.launch { runCatching { client.cancel() }; turn.cancel() }
                    throw e
                }
                OpenClawTurnResult.Aborted
            } finally {
                withContext(NonCancellable) { lock.withLock { activeTurns -= turn } }
            }
            connection.refresh()
            return map(result, startedAtMs, proposals)
        }
    }

    @Suppress("TooManyFunctions") // Client binding and status publication share the assistant's mutex and generation checks.
    private inner class AssistantConnection {
        fun endpointOrNull(): OpenClawEndpoint? = OpenClawEndpoint.parse(prefs.endpointText())

        /** Rechecks the automatic admission; the caller owns [lock] and binds its connect before releasing it. */
        fun clientLocked(expectedGeneration: Long, expectedEndpoint: String): OpenClawChat? {
            if (
                reconnectGeneration != expectedGeneration ||
                prefs.endpointText() != expectedEndpoint ||
                !credentials.exist()
            ) {
                return null
            }
            return clientLocked()
        }

        /** Local conversation state can be reset while online use is disabled. */
        suspend fun clientForLocalReset(): OpenClawChat? = lock.withLock { clientForEndpointLocked() }

        /** The caller owns lock, including while constructing a client for turn admission. */
        fun clientLocked(): OpenClawChat? {
            if (!prefs.isEnabled()) return null
            return clientForEndpointLocked()
        }

        private fun clientForEndpointLocked(): OpenClawChat? {
            if (forgetting > 0) return null
            val text = prefs.endpointText()
            val endpoint = OpenClawEndpoint.parse(text) ?: return null
            return chat?.takeIf { chatEndpoint == text } ?: chatFor(endpoint).also {
                chat = it
                chatEndpoint = text
                follow(it)
            }
        }

        fun follow(client: OpenClawChat) {
            linkWatcher?.cancel()
            // Check and publish under the mutex that detaches clients: a forget or an address change that
            // already replaced [chat] cannot be overwritten by a link emission that passed the check earlier.
            linkWatcher = turns.launch {
                client.linkState.collect { link ->
                    lock.withLock { if (chat === client) statusFlow.value = snapshot(link) }
                }
            }
        }

        fun initialLink(): OpenClawLinkState =
            if (credentials.exist()) OpenClawLinkState.Disconnected else OpenClawLinkState.Unpaired

        /** The caller owns [lock]. */
        fun currentLinkLocked(): OpenClawLinkState = chat?.linkState?.value ?: initialLink()

        fun snapshot(link: OpenClawLinkState) = OpenClawStatus(
            enabled = prefs.isEnabled(),
            endpoint = prefs.endpointText(),
            endpointValid = endpointOrNull() != null,
            link = link,
        )

        /** Reads the live link and publishes it. Only for callers that already own [lock]. */
        fun refreshLocked() {
            statusFlow.value = snapshot(chat?.linkState?.value ?: initialLink())
        }

        /**
         * Read, snapshot and publish are one step under [lock], so a forget or address change cannot
         * publish in between and then be overwritten by the older link. Not cancellable: the section is
         * short and never waits on the network, and a cancelled caller must still leave a fresh status.
         */
        suspend fun refresh() = withContext(NonCancellable) { lock.withLock { refreshLocked() } }

        suspend fun refreshAndReturn(): OpenClawLinkState = withContext(NonCancellable) {
            lock.withLock {
                refreshLocked()
                statusFlow.value.link
            }
        }
    }
}

private fun problem(problem: Problem, detail: String, uncertain: Boolean = false) =
    AssistantReply.Problem(problem, detail, uncertain)

private fun map(result: OpenClawTurnResult, startedAtMs: Long, proposals: Boolean): AssistantReply = when (result) {
    is OpenClawTurnResult.Answer -> AssistantReply.Answer(result.text)
    // A plain turn (the settings probe) never proposes: the raw text is shown as it came.
    is OpenClawTurnResult.ActionAnswer -> if (proposals) proposal(result, startedAtMs) else AssistantReply.Answer(result.text)
    OpenClawTurnResult.Aborted -> AssistantReply.Aborted
    OpenClawTurnResult.Uncertain -> problem(Problem.ASSISTANT_UNAVAILABLE, PolishAssistantText.UNCERTAIN, uncertain = true)
    // The retry was refused locally, but the original may have arrived: keep the resend offered.
    OpenClawTurnResult.ResendTooLarge ->
        problem(Problem.ASSISTANT_UNAVAILABLE, PolishAssistantText.RESEND_TOO_LARGE, uncertain = true)
    is OpenClawTurnResult.NeedsApproval -> problem(Problem.APPROVAL_REQUIRED, PolishAssistantText.APPROVAL)
    OpenClawTurnResult.Revoked -> problem(Problem.PAIRING_REQUIRED, PolishAssistantText.REVOKED)
    is OpenClawTurnResult.Failed -> failure(result.failure)
}

private fun proposal(result: OpenClawTurnResult.ActionAnswer, startedAtMs: Long): AssistantReply =
    when (val extraction = OpenClawActionProtocol.extract(result.text)) {
        is Extraction.NoBlock -> AssistantReply.Answer(extraction.text)
        is Extraction.Block -> AssistantReply.Proposal(
            extraction.prose, OpenClawActionProtocol.parseEnvelope(extraction.rawJson),
            result.recovered, result.turnKey, startedAtMs,
        )
        Extraction.Malformed -> AssistantReply.Proposal(
            // Only the text before the broken block: the fence itself is never shown or spoken.
            result.text.substringBefore("```bydmate-action").trim(),
            ParsedEnvelope.Invalid(ActionRejection.MALFORMED), result.recovered, result.turnKey, startedAtMs,
        )
    }

private fun failure(failure: OpenClawFailure): AssistantReply.Problem = when (failure) {
    OpenClawFailure.NetworkUnavailable, OpenClawFailure.TlsRejected, OpenClawFailure.HostRejected,
    OpenClawFailure.Timeout ->
        problem(Problem.NO_CONNECTION, PolishAssistantText.failure(failure))
    OpenClawFailure.AuthRejected ->
        problem(Problem.PAIRING_REQUIRED, PolishAssistantText.failure(failure))
    else -> problem(Problem.ASSISTANT_UNAVAILABLE, PolishAssistantText.failure(failure))
}

/** Read-only preference access shared by the assistant's control and turn APIs. */
abstract class OpenClawPreferenceAccess(protected val prefs: OpenClawPrefs) : VoiceAssistant {
    override fun isEnabled(): Boolean = prefs.isEnabled()
    fun endpointText(): String = prefs.endpointText()
}
