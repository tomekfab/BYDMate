package com.bydmate.app.voice.openclaw

import com.bydmate.app.diagnostics.DiagnosticEmitters

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

/** Where the client stands with the Gateway. Pairing, approval and revocation are explicit states. */
sealed interface OpenClawLinkState {
    /** No stored credentials: the device has never paired, or it was disconnected or revoked earlier. */
    object Unpaired : OpenClawLinkState
    object Disconnected : OpenClawLinkState
    object Connecting : OpenClawLinkState

    /** The owner must approve this device on the Gateway host. */
    data class AwaitingApproval(val requestId: String?) : OpenClawLinkState
    object Ready : OpenClawLinkState

    /** The Gateway no longer accepts this device. Local credentials and conversation were cleared. */
    object Revoked : OpenClawLinkState
    data class Failed(val failure: OpenClawFailure) : OpenClawLinkState
}

/** The outcome of one turn. Only [Answer] and [ActionAnswer] carry assistant text. */
sealed interface OpenClawTurnResult {
    data class Answer(val text: String) : OpenClawTurnResult

    /**
     * The final answer of an action turn ([OpenClawClient.askForActions]). [recovered] is true when it was read back
     * from history after a lost socket instead of arriving live. [turnKey] is the turn's idempotency key.
     */
    data class ActionAnswer(val text: String, val recovered: Boolean, val turnKey: String) : OpenClawTurnResult
    object Aborted : OpenClawTurnResult

    /**
     * The send may or may not have reached the assistant, and the answer could not be recovered.
     * It was not repeated. [OpenClawClient.resendUncertain] repeats it explicitly under the same key.
     */
    object Uncertain : OpenClawTurnResult

    /**
     * An explicit [OpenClawClient.resendUncertain] was refused locally: the frame is larger than the Gateway's
     * advertised limit on this connection. The retry was never sent. The original turn is still unresolved
     * (it may have reached the assistant) and can be resent again.
     */
    object ResendTooLarge : OpenClawTurnResult
    data class NeedsApproval(val requestId: String?) : OpenClawTurnResult
    object Revoked : OpenClawTurnResult
    data class Failed(val failure: OpenClawFailure) : OpenClawTurnResult
}

data class OpenClawTiming(
    val challengeTimeoutMs: Long = 10_000,
    val helloTimeoutMs: Long = 15_000,
    val ackTimeoutMs: Long = 15_000,
    val turnTimeoutMs: Long = 120_000,
    val abortTimeoutMs: Long = 10_000,
    val recoveryAttempts: Int = 3,
    val recoveryDelayMs: Long = 1_500,
    val historyPolls: Int = 30,
    val historyPollMs: Long = 2_000,
    /** An approval request expires on the Gateway after about five minutes. */
    val pairTimeoutMs: Long = 300_000,
    val pairPollMs: Long = 3_000,
)

/** A private `wss` Gateway address: no other scheme, no credentials, query or fragment in the URL. */
class OpenClawEndpoint private constructor(internal val url: HttpUrl) {
    val host: String get() = url.host

    companion object {
        fun parse(raw: String): OpenClawEndpoint? {
            val text = raw.trim()
            if (!text.startsWith("wss://", ignoreCase = true)) return null
            val url = ("https://" + text.substring("wss://".length)).toHttpUrlOrNull() ?: return null
            if (url.host.isEmpty() || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if (url.query != null || url.fragment != null) return null
            return OpenClawEndpoint(url)
        }
    }
}

object OpenClawHttp {
    /** Normal TLS validation (system trust, no pinning override), and no redirect of any kind. */
    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
}

/**
 * Text-only chat with the user's existing OpenClaw assistant over a paired, revocable WebSocket
 * device identity. Independent of every other assistant provider: a failure here never falls back.
 *
 * One turn at a time. Never sent: audio, local results, no telemetry; an action turn also carries the static
 * capability manifest.
 * Never done: approving remote tool actions, or any method outside [OpenClawProtocol.ALLOWED_METHODS].
 * The [onDelta] callback runs on a network thread.
 */
@Suppress("TooManyFunctions") // One transport interface owns pairing, turns, recovery and cancellation.
// Keep stores, transport, timing, metadata and attachment dependencies explicit.
class OpenClawClient @Suppress("LongParameterList") constructor(
    private val endpoint: OpenClawEndpoint,
    private val identityStore: OpenClawIdentityStore,
    private val conversationStore: OpenClawConversationStore,
    httpClient: OkHttpClient = OpenClawHttp.newClient(),
    private val timing: OpenClawTiming = OpenClawTiming(),
    private val metadata: OpenClawClientMetadata = OpenClawClientMetadata(),
    private val attachments: OpenClawTurnAttachments = OpenClawTurnAttachments.None,
) {
    // Redirects stay off even for an injected client: a redirect could move the token to another host.
    private val http = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private val state = MutableStateFlow<OpenClawLinkState>(
        if (identityStore.isPaired()) OpenClawLinkState.Disconnected else OpenClawLinkState.Unpaired,
    )
    val linkState: StateFlow<OpenClawLinkState> = state.asStateFlow()

    private val connectMutex = Mutex()
    private val turnGate = Mutex()

    // The live connection. Writes, and every read that must agree with [state], hold [liveGuard];
    // single reads that only need the handle may use the volatile directly.
    @Volatile private var current: Connection? = null

    // Ties [current] to the Ready state. Lock order: lifecycleMutex, then liveGuard. The socket
    // callback thread takes only this guard. Sockets are closed after the guard is released.
    private val liveGuard = Any()
    @Volatile private var active: Turn? = null
    @Volatile private var uncertain: Turn? = null

    // --- connection and pairing -----------------------------------------------------------------

    /** Connects (or confirms the live connection). One attempt; use [pair] to wait for approval. */
    suspend fun connect(): OpenClawLinkState = lifecycle.ensureConnected(lifecycle.operation)

    /** Like [connect], but never creates an identity: only an existing pairing may connect. */
    suspend fun connectPaired(): OpenClawLinkState =
        lifecycle.ensureConnected(lifecycle.operation, allowNewIdentity = false)

    /** Test seam: runs after a handshake settled and before its result is published. */
    @Volatile internal var beforePublish: (suspend (CompletableDeferred<OpenClawFailure>) -> Unit)? = null

    /** Test seam: whether a connection is currently owned as live. */
    internal val hasLiveConnection: Boolean get() = current != null

    /** Connects, and while the owner has not approved the device yet, retries until the timeout. */
    suspend fun pair(): OpenClawLinkState {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timing.pairTimeoutMs)
        val operation = lifecycle.operation
        while (lifecycle.isCurrent(operation)) {
            val result = lifecycle.ensureConnected(operation)
            if (result !is OpenClawLinkState.AwaitingApproval || System.nanoTime() >= deadline) return result
            delay(timing.pairPollMs)
        }
        return lifecycle.stoppedState
    }

    // --- turns ------------------------------------------------------------------------------------

    /** Sends one user utterance and waits for the final answer. Only [OpenClawTurnResult.Answer] is speakable. */
    suspend fun ask(text: String, onDelta: (String) -> Unit = {}): OpenClawTurnResult =
        startTurn(text, onDelta, actions = false)

    private suspend fun startTurn(text: String, onDelta: (String) -> Unit, actions: Boolean): OpenClawTurnResult {
        if (text.isBlank()) return OpenClawTurnResult.Failed(OpenClawFailure.EmptyRequest)
        if (!turnGate.tryLock()) return OpenClawTurnResult.Failed(OpenClawFailure.Busy)
        try {
            uncertain = null // a new question abandons an unresolved one; it is never replayed
            val key = metadata.newId()
            // Built once: a resend of this turn reuses the same message, so the attachments never change.
            val message = if (actions) {
                OpenClawProtocol.turnMessage(text.trim(), key, attachments.forTurn(key))
            } else {
                OpenClawProtocol.turnMessage(text.trim(), key)
            }
            return runTurn(Turn(key, message, onDelta, actions))
        } finally {
            active = null
            turnGate.unlock()
        }
    }

    /** Like [ask], but the turn carries the action attachments and its answer is an [OpenClawTurnResult.ActionAnswer]. */
    suspend fun askForActions(text: String, onDelta: (String) -> Unit = {}): OpenClawTurnResult =
        startTurn(text, onDelta, actions = true)

    /** Explicitly repeats the last uncertain turn under the same idempotency key. */
    suspend fun resendUncertain(onDelta: (String) -> Unit = {}): OpenClawTurnResult {
        if (!turnGate.tryLock()) return OpenClawTurnResult.Failed(OpenClawFailure.Busy)
        try {
            val turn = uncertain ?: return OpenClawTurnResult.Failed(OpenClawFailure.NothingToResend)
            uncertain = null
            turn.restart(onDelta)
            val result = runTurn(turn)
            // Too large for this connection: the retry was never sent, so the earlier send is still unresolved.
            if (result == OpenClawTurnResult.Failed(OpenClawFailure.RequestTooLarge)) {
                uncertain = turn
                return OpenClawTurnResult.ResendTooLarge
            }
            return result
        } finally {
            active = null
            turnGate.unlock()
        }
    }

    /** Stops the running turn. It returns [OpenClawTurnResult.Aborted] and nothing more from that run is spoken. */
    suspend fun cancel() {
        val turn = active ?: return
        delivery.abortLocally(turn)
        val conn = current
        if (conn != null && !conn.closed.isCompleted) delivery.abortRemote(conn, turn)
    }

    /** Starts a new conversation for the same agent. Pairing is kept. */
    suspend fun resetConversation() {
        cancel()
        conversationStore.reset(metadata.language)
        uncertain = null
    }

    /** Forgets this device: closes the socket and clears identity, token and conversation. Revoke it on the Gateway too. */
    suspend fun disconnect() {
        active?.let { delivery.abortLocally(it) }
        lifecycle.invalidate(clearIdentity = true)
        conversationStore.clear()
        uncertain = null
        synchronized(liveGuard) { state.value = OpenClawLinkState.Unpaired }
    }

    /** Stops connection and pairing work without forgetting an approved device. */
    suspend fun cancelConnectionOperations() {
        lifecycle.invalidate(clearIdentity = false)
    }

    private suspend fun runTurn(turn: Turn): OpenClawTurnResult {
        active = turn
        val link = lifecycle.ensureConnected(lifecycle.operation)
        if (link != OpenClawLinkState.Ready) {
            DiagnosticEmitters.failure("openclaw", reason = "not_ready")
            return link.toTurnResult()
        }
        val started = System.nanoTime()
        return delivery.submit(turn).also { result ->
            DiagnosticEmitters.request("openclaw", "chat", (System.nanoTime() - started) / 1_000_000,
                when (result) {
                    is OpenClawTurnResult.Answer, is OpenClawTurnResult.ActionAnswer -> "success"
                    OpenClawTurnResult.Aborted -> "cancelled"
                    else -> "failure"
                })
        }
    }

    private sealed interface Poll {
        class Answer(val text: String) : Poll
        object NoAnswer : Poll
        object NotFound : Poll
        object StillRunning : Poll
        object Lost : Poll
        class Stop(val result: OpenClawTurnResult) : Poll
    }

    private sealed interface Recovery {
        object Retry : Recovery
        object Uncertain : Recovery
        class Done(val result: OpenClawTurnResult) : Recovery
    }

    /** One user turn. Its idempotency key never changes, so a later look-up or explicit resend is the same turn. */
    private class Turn(val key: String, val message: String, var onDelta: (String) -> Unit, val actions: Boolean) {
        val tag = OpenClawProtocol.turnTag(key)
        @Volatile var sessionKey: String? = null
        @Volatile var runId: String? = null
        @Volatile var cancelled = false
        @Volatile var admitted = false
        @Volatile var result = CompletableDeferred<OpenClawTurnResult>()
        private var lastSeq = Long.MIN_VALUE
        private val early = ArrayList<OpenClawProtocol.Frame.Chat>()

        @Synchronized
        fun restart(onDelta: (String) -> Unit) {
            this.onDelta = onDelta
            runId = null
            cancelled = false
            result = CompletableDeferred()
            lastSeq = Long.MIN_VALUE
            early.clear()
        }

        /** Events of the run can arrive before its ack names it: keep a few and replay them in order. */
        @Synchronized
        fun accept(frame: OpenClawProtocol.Frame.Chat) {
            if (result.isCompleted) return // finished or cancelled: late events never speak
            val run = runId
            if (run == null) {
                if (early.size < MAX_EARLY_EVENTS) early.add(frame)
                return
            }
            if (frame.runId == run) apply(frame)
        }

        @Synchronized
        fun attach(run: String) {
            runId = run
            val held = early.filter { it.runId == run }
            early.clear()
            for (frame in held) if (!result.isCompleted) apply(frame)
        }

        private fun apply(frame: OpenClawProtocol.Frame.Chat) {
            frame.seq?.let {
                if (it <= lastSeq) return // duplicate or out of order
                lastSeq = it
            }
            when (frame.state) {
                "delta" -> frame.deltaText?.takeIf { it.isNotEmpty() }?.let(onDelta)
                "final" -> result.complete(
                    frame.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        if (actions) OpenClawTurnResult.ActionAnswer(it, recovered = false, turnKey = key) else OpenClawTurnResult.Answer(it)
                    } ?: OpenClawTurnResult.Failed(OpenClawFailure.EmptyAnswer),
                )
                "aborted" -> result.complete(OpenClawTurnResult.Aborted)
                "error" -> result.complete(OpenClawTurnResult.Failed(OpenClawFailure.ServerError))
                else -> Unit // startup status
            }
        }

        private companion object {
            const val MAX_EARLY_EVENTS = 64
        }
    }

    // --- socket -----------------------------------------------------------------------------------

    /** A socket-level failure with no message and no stack: nothing from the server can leak through it. */
    private class ConnectionLost(val failure: OpenClawFailure) : Exception(null, null, false, false)

    /** The complete frame is larger than the Gateway's advertised `maxPayload`. Thrown before the socket is touched. */
    private class RequestTooLarge : Exception(null, null, false, false)

    // History and abort frames are tiny; one that is refused is treated as a lost request, like a dead socket.
    private suspend fun Connection.tryRequest(method: String, params: JSONObject, timeoutMs: Long): OpenClawProtocol.Frame.Response? =
        try {
            request(method, params, timeoutMs)
        } catch (_: ConnectionLost) {
            null
        } catch (_: TimeoutCancellationException) {
            null
        } catch (_: RequestTooLarge) {
            null
        }

    private inner class Connection : WebSocketListener() {
        val challenge = CompletableDeferred<OpenClawProtocol.Frame.Challenge>()
        val closed = CompletableDeferred<OpenClawFailure>()
        private val pending = ConcurrentHashMap<String, CompletableDeferred<OpenClawProtocol.Frame.Response>>()
        private val ids = AtomicInteger()
        private lateinit var socket: WebSocket

        /** `hello-ok.policy.maxPayload` of this socket; null when the Gateway advertised none (legacy behaviour). */
        @Volatile var maxPayloadBytes: Long? = null

        fun open() {
            socket = http.newWebSocket(Request.Builder().url(endpoint.url).build(), this)
        }

        fun close() {
            if (::socket.isInitialized) socket.close(NORMAL_CLOSURE, null)
        }

        suspend fun request(method: String, params: JSONObject, timeoutMs: Long): OpenClawProtocol.Frame.Response {
            require(method in OpenClawProtocol.ALLOWED_METHODS) { "method not allowed: $method" }
            val id = ids.incrementAndGet().toString()
            val reply = CompletableDeferred<OpenClawProtocol.Frame.Response>()
            pending[id] = reply
            try {
                if (closed.isCompleted) throw ConnectionLost(closed.await())
                sendFrame(OpenClawProtocol.requestFrame(id, method, params))
                return withTimeout(timeoutMs) { reply.await() }
            } finally {
                pending.remove(id)
            }
        }

        private fun sendFrame(frame: String) {
            // The limit applies to the whole encoded frame (UTF-8 bytes, JSON escaping included), not the text alone.
            val limit = maxPayloadBytes
            if (limit != null && frame.toByteArray(Charsets.UTF_8).size > limit) throw RequestTooLarge()
            if (!socket.send(frame)) throw ConnectionLost(OpenClawFailure.NetworkUnavailable)
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // The socket must still be the configured host over TLS, whatever the http client did.
            val url = response.request.url
            if (!url.isHttps || url.host != endpoint.host) {
                fail(OpenClawFailure.HostRejected)
                webSocket.cancel()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val frame = OpenClawProtocol.parseFrame(text)) {
                is OpenClawProtocol.Frame.Challenge -> challenge.complete(frame)
                OpenClawProtocol.Frame.InvalidChallenge -> challenge.completeExceptionally(ConnectionLost(OpenClawFailure.ChallengeFailed))
                is OpenClawProtocol.Frame.Response -> pending.remove(frame.id)?.complete(frame)
                is OpenClawProtocol.Frame.Chat -> delivery.onChat(this, frame)
                OpenClawProtocol.Frame.Ignored -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            fail(OpenClawFailure.NetworkUnavailable)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail(OpenClawFailure.NetworkUnavailable)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(
                when {
                    response?.isRedirect == true -> OpenClawFailure.HostRejected
                    t is SSLException -> OpenClawFailure.TlsRejected
                    else -> OpenClawFailure.NetworkUnavailable
                },
            )
        }

        private fun fail(failure: OpenClawFailure) {
            closed.complete(failure)
            val lost = ConnectionLost(failure)
            challenge.completeExceptionally(lost)
            for (reply in pending.values) reply.completeExceptionally(lost)
            pending.clear()
            synchronized(liveGuard) {
                if (current === this) {
                    current = null
                    if (state.value == OpenClawLinkState.Ready) state.value = OpenClawLinkState.Disconnected
                }
            }
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
    private val lifecycle = ConnectionLifecycle()

    @Suppress("TooManyFunctions") // Keep lifecycle ownership and cancellation steps explicit.
    private inner class ConnectionLifecycle {
        private val operationGeneration = AtomicLong()
        private val lifecycleMutex = Mutex()
        private var connecting: Connection? = null

        val operation: Long get() = operationGeneration.get()

        fun isCurrent(operation: Long): Boolean = operationGeneration.get() == operation

        val stoppedState: OpenClawLinkState get() = state.value

        /**
         * Invalidates any in-flight handshake before closing its sockets. The short mutex only
         * protects ownership and credential writes; it is never held while waiting on the network.
         */
        suspend fun invalidate(clearIdentity: Boolean) {
            val sockets = lifecycleMutex.withLock {
                operationGeneration.incrementAndGet()
                val pending = connecting.also { connecting = null }
                if (clearIdentity) identityStore.clear()
                val resting = restingState()
                val live = synchronized(liveGuard) {
                    state.value = resting
                    current.also { current = null }
                }
                pending to live
            }
            sockets.first?.close()
            if (sockets.second !== sockets.first) sockets.second?.close()
        }

        /** The link at rest: paired devices are Disconnected, others Unpaired. */
        private fun restingState(): OpenClawLinkState =
            if (identityStore.isPaired()) OpenClawLinkState.Disconnected else OpenClawLinkState.Unpaired

        /**
         * @param allowNewIdentity false for automatic recovery: it may only use an existing pairing,
         * decided under [lifecycleMutex] right before an identity could be created, so a revocation
         * that lands between an outer check and this connect cannot turn it into a pairing request.
         */
        suspend fun ensureConnected(operation: Long, allowNewIdentity: Boolean = true): OpenClawLinkState {
            if (!isCurrent(operation)) return stoppedState
            if (readyConnection()) return OpenClawLinkState.Ready
            return connectMutex.withLock {
                if (!isCurrent(operation)) return@withLock stoppedState
                if (readyConnection()) return@withLock OpenClawLinkState.Ready
                val input = handshakeInput(operation, allowNewIdentity) ?: return@withLock stoppedState
                val conn = Connection()
                if (!beginHandshake(operation, conn)) {
                    conn.close()
                    return@withLock stoppedState
                }
                var settled: OpenClawLinkState? = null
                try {
                    settled = handshake(conn, input.first, input.second, operation)
                } finally {
                    // Cancellation or a crash mid-handshake: this attempt still owns its socket and handle.
                    if (settled == null) withContext(NonCancellable) { abandon(conn) }
                }
                // Not cancellable from here: a Ready handshake has already stored its connection.
                val outcome = checkNotNull(settled)
                withContext(NonCancellable) {
                    lifecycleMutex.withLock {
                        if (connecting === conn) connecting = null
                    }
                    if (outcome != OpenClawLinkState.Ready) conn.close()
                    beforePublish?.invoke(conn.closed)
                    finishHandshake(operation, outcome, conn)
                }
            }
        }

        /**
         * Releases only what this attempt owns. A newer attempt, an invalidation or a ready connection
         * has already replaced [connecting] or [current], and those are left alone. The link goes back
         * to its resting state only while it still shows this attempt's Connecting.
         */
        private suspend fun abandon(conn: Connection) {
            lifecycleMutex.withLock {
                if (connecting === conn) {
                    connecting = null
                    val resting = restingState()
                    synchronized(liveGuard) {
                        if (state.value == OpenClawLinkState.Connecting) state.value = resting
                    }
                }
            }
            conn.close()
        }

        private fun readyConnection(): Boolean = synchronized(liveGuard) {
            current?.let { !it.closed.isCompleted && state.value == OpenClawLinkState.Ready } == true
        }

        private suspend fun handshakeInput(
            operation: Long,
            allowNewIdentity: Boolean,
        ): Pair<OpenClawIdentity, String?>? = lifecycleMutex.withLock {
            if (!isCurrent(operation)) return@withLock null
            if (!allowNewIdentity && !identityStore.isPaired()) {
                // Nothing to reconnect with (never paired, or revoked a moment ago): no identity is made.
                synchronized(liveGuard) {
                    if (state.value == OpenClawLinkState.Disconnected) state.value = OpenClawLinkState.Unpaired
                }
                return@withLock null
            }
            synchronized(liveGuard) { state.value = OpenClawLinkState.Connecting }
            identityStore.getOrCreateIdentity() to identityStore.deviceToken()
        }

        private suspend fun beginHandshake(operation: Long, conn: Connection): Boolean = lifecycleMutex.withLock {
            if (!isCurrent(operation)) return@withLock false
            connecting = conn
            conn.open()
            true
        }

        /** What a Ready handshake becomes when its socket died before it could be published. */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private fun closedOutcome(conn: Connection): OpenClawLinkState =
            OpenClawLinkState.Failed(
                if (conn.closed.isCompleted) conn.closed.getCompleted() else OpenClawFailure.NetworkUnavailable,
            )

        /**
         * Publishes the handshake result. Ready is published only while the socket is still the live
         * one, in the same guarded step that [Connection.fail] uses, so Ready never shows without a socket.
         */
        private suspend fun finishHandshake(operation: Long, outcome: OpenClawLinkState, conn: Connection): OpenClawLinkState =
            lifecycleMutex.withLock {
                if (!isCurrent(operation)) return@withLock stoppedState
                synchronized(liveGuard) {
                    val published = if (outcome == OpenClawLinkState.Ready && !(current === conn && !conn.closed.isCompleted)) {
                        if (current === conn) current = null
                        closedOutcome(conn)
                    } else {
                        outcome
                    }
                    state.value = published
                    published
                }
            }

        suspend fun handshake(
            conn: Connection,
            identity: OpenClawIdentity,
            storedToken: String?,
            operation: Long,
        ): OpenClawLinkState {
            val challenge = try {
                withTimeout(timing.challengeTimeoutMs) { conn.challenge.await() }
            } catch (e: ConnectionLost) {
                return OpenClawLinkState.Failed(e.failure)
            } catch (_: TimeoutCancellationException) {
                return OpenClawLinkState.Failed(OpenClawFailure.ChallengeFailed)
            }
            val params = OpenClawProtocol.connectParams(identity, challenge, storedToken, metadata.clientVersion)
            val response = try {
                conn.request("connect", params, timing.helloTimeoutMs)
            } catch (e: ConnectionLost) {
                return OpenClawLinkState.Failed(e.failure)
            } catch (_: TimeoutCancellationException) {
                return OpenClawLinkState.Failed(OpenClawFailure.Timeout)
            }
            if (!response.ok) return connectRefused(response.error, hadToken = storedToken != null, operation = operation)

            val hello = OpenClawProtocol.parseHello(response.payload)
            if (hello == null || hello.protocol != OpenClawProtocol.PROTOCOL_VERSION) {
                return OpenClawLinkState.Failed(OpenClawFailure.ProtocolMismatch)
            }
            // Least privilege: exactly operator read/write. Anything else is not used and not stored.
            if (!hasExpectedAccess(hello)) {
                return OpenClawLinkState.Failed(OpenClawFailure.ScopeMismatch)
            }
            val token = hello.deviceToken ?: storedToken ?: return OpenClawLinkState.Failed(OpenClawFailure.ProtocolMismatch)
            conn.maxPayloadBytes = hello.maxPayloadBytes
            return lifecycleMutex.withLock {
                if (!isCurrent(operation)) return@withLock stoppedState
                if (token != storedToken) identityStore.saveDeviceToken(token)
                synchronized(liveGuard) {
                    // A socket that already died is not adopted; its closing handler has run or will find nothing.
                    if (conn.closed.isCompleted) {
                        closedOutcome(conn)
                    } else {
                        current = conn
                        OpenClawLinkState.Ready
                    }
                }
            }
        }

        private fun hasExpectedAccess(hello: OpenClawProtocol.Hello): Boolean =
            hello.role == OpenClawProtocol.ROLE && hello.scopes == OpenClawProtocol.SCOPES.toSet()

        suspend fun connectRefused(
            error: OpenClawProtocol.ErrorInfo?,
            hadToken: Boolean,
            operation: Long,
        ): OpenClawLinkState = lifecycleMutex.withLock {
            if (!isCurrent(operation)) return@withLock stoppedState
            when {
                error == null -> OpenClawLinkState.Failed(OpenClawFailure.ServerError)
                // A device we hold a token for is no longer approved: it was removed on the Gateway.
                error.isPairingRequired -> if (hadToken) revokeLocked() else OpenClawLinkState.AwaitingApproval(error.requestId)
                error.isAuthRejected -> if (hadToken) revokeLocked() else OpenClawLinkState.Failed(OpenClawFailure.AuthRejected)
                error.code == "FORBIDDEN" -> OpenClawLinkState.Failed(OpenClawFailure.Forbidden)
                error.code == "UNAVAILABLE" -> OpenClawLinkState.Failed(OpenClawFailure.ServerUnavailable)
                else -> OpenClawLinkState.Failed(OpenClawFailure.ServerError)
            }
        }

        /**
         * The caller owns [lifecycleMutex]. Invalidates every queued or in-flight operation, clears the
         * credentials and publishes Revoked in one step, so an automatic connect that was already
         * waiting for [connectMutex] cannot create a new identity from the cleared store.
         */
        private fun revokeLocked(): OpenClawLinkState {
            operationGeneration.incrementAndGet()
            identityStore.clear()
            conversationStore.clear()
            uncertain = null
            val live = synchronized(liveGuard) {
                state.value = OpenClawLinkState.Revoked
                current.also { current = null }
            }
            live?.close() // the old token must not stay authenticated on an open socket
            return OpenClawLinkState.Revoked
        }

        suspend fun revoke(): OpenClawLinkState = lifecycleMutex.withLock { revokeLocked() }
    }

    private val delivery = TurnDelivery()

    private inner class TurnDelivery {
        suspend fun submit(turn: Turn): OpenClawTurnResult {
            if (turn.cancelled) return OpenClawTurnResult.Aborted
            val conn = current?.takeIf { !it.closed.isCompleted }
                ?: return OpenClawTurnResult.Failed(OpenClawFailure.NetworkUnavailable)
            val sessionKey = turn.sessionKey ?: conversationStore.sessionKey(metadata.language).also { turn.sessionKey = it }

            val ack = try {
                conn.request("chat.send", OpenClawChatRequests.chatSendParams(sessionKey, turn.message, turn.key), timing.ackTimeoutMs)
            } catch (_: RequestTooLarge) {
                // Refused before the socket: known unsent. Nothing to recover or resend, the link stays Ready.
                return OpenClawTurnResult.Failed(OpenClawFailure.RequestTooLarge)
            } catch (_: ConnectionLost) {
                return recover(turn) // no ack: the send may have arrived. Look, never resend.
            } catch (_: TimeoutCancellationException) {
                return recover(turn)
            }
            if (!ack.ok) return refused(ack.error)
            if (turn.actions && !turn.admitted) {
                turn.admitted = true
                attachments.onAdmitted(turn.key) // the Gateway took the turn, so the report it carried is delivered
            }
            val runId = ack.payload?.optString("runId")?.takeIf { it.isNotEmpty() } ?: return recover(turn)

            turn.attach(runId)
            if (turn.cancelled) {
                abortRemote(conn, turn)
                return OpenClawTurnResult.Aborted
            }
            return awaitTerminal(turn, conn)
        }

        suspend fun awaitTerminal(turn: Turn, conn: Connection): OpenClawTurnResult {
            val result = turn.result
            val outcome = withTimeoutOrNull(timing.turnTimeoutMs) {
                select<Boolean> {
                    result.onAwait { true }
                    conn.closed.onAwait { false }
                }
            }
            return when {
                result.isCompleted -> result.await()
                outcome == null -> {
                    abortLocally(turn)
                    abortRemote(conn, turn)
                    OpenClawTurnResult.Failed(OpenClawFailure.Timeout)
                }
                else -> recover(turn) // the socket dropped mid-turn
            }
        }

        /** The Gateway answered an operation with an explicit error: the operation did not happen. */
        suspend fun refused(error: OpenClawProtocol.ErrorInfo?): OpenClawTurnResult = when {
            error == null -> OpenClawTurnResult.Failed(OpenClawFailure.ServerError)
            error.isPairingRequired || error.isAuthRejected -> lifecycle.revoke().toTurnResult()
            error.code == "FORBIDDEN" -> OpenClawTurnResult.Failed(OpenClawFailure.Forbidden)
            error.code == "UNAVAILABLE" -> OpenClawTurnResult.Failed(OpenClawFailure.ServerUnavailable)
            else -> OpenClawTurnResult.Failed(OpenClawFailure.ServerError)
        }

        /**
         * After an uncertain send or a dropped socket: reconnect and read the bounded history to find this
         * turn by its tag. Never sends `chat.send` again; an unresolved turn is reported as uncertain.
         */
        suspend fun recover(turn: Turn): OpenClawTurnResult {
            var outcome: Recovery = Recovery.Retry
            var attempt = 0
            while (attempt < timing.recoveryAttempts && outcome == Recovery.Retry) {
                outcome = recoverAttempt(turn, attempt)
                attempt++
            }
            if (outcome is Recovery.Done) return outcome.result
            if (turn.cancelled) return OpenClawTurnResult.Aborted
            uncertain = turn
            return OpenClawTurnResult.Uncertain
        }

        suspend fun recoverAttempt(turn: Turn, attempt: Int): Recovery {
            if (turn.cancelled) return Recovery.Done(OpenClawTurnResult.Aborted)
            if (attempt > 0) delay(timing.recoveryDelayMs)
            val link = lifecycle.ensureConnected(lifecycle.operation)
            if (link == OpenClawLinkState.Revoked || link is OpenClawLinkState.AwaitingApproval) {
                return Recovery.Done(link.toTurnResult())
            }
            if (link != OpenClawLinkState.Ready) return Recovery.Retry
            return when (val poll = pollHistory(turn)) {
                is Poll.Answer -> Recovery.Done(
                    when {
                        turn.cancelled -> OpenClawTurnResult.Aborted
                        turn.actions -> OpenClawTurnResult.ActionAnswer(poll.text, recovered = true, turnKey = turn.key)
                        else -> OpenClawTurnResult.Answer(poll.text)
                    },
                )
                Poll.NoAnswer -> Recovery.Done(OpenClawTurnResult.Failed(OpenClawFailure.EmptyAnswer))
                Poll.NotFound, Poll.StillRunning -> Recovery.Uncertain
                Poll.Lost -> Recovery.Retry
                is Poll.Stop -> Recovery.Done(poll.result)
            }
        }

        suspend fun pollHistory(turn: Turn): Poll {
            val sessionKey = turn.sessionKey ?: return Poll.NotFound
            val marker = "(#${turn.tag})"
            repeat(timing.historyPolls) {
                if (turn.cancelled) return Poll.Stop(OpenClawTurnResult.Aborted)
                val conn = current?.takeIf { !it.closed.isCompleted } ?: return Poll.Lost
                val response = conn.tryRequest("chat.history", OpenClawChatRequests.chatHistoryParams(sessionKey), timing.ackTimeoutMs)
                    ?: return Poll.Lost
                if (!response.ok) return Poll.Stop(refused(response.error))
                val history = OpenClawProtocol.parseHistory(response.payload)
                val index = history.rows.indexOfLast { it.role == "user" && it.text.contains(marker) }
                if (!history.hasActiveRun) {
                    if (index < 0) return Poll.NotFound
                    val answer = history.rows.drop(index + 1).lastOrNull { it.role == "assistant" && it.text.isNotBlank() }
                    return if (answer != null) Poll.Answer(answer.text.trim()) else Poll.NoAnswer
                }
                delay(timing.historyPollMs)
            }
            return Poll.StillRunning
        }

        fun abortLocally(turn: Turn) {
            turn.cancelled = true
            turn.result.complete(OpenClawTurnResult.Aborted)
        }

        suspend fun abortRemote(conn: Connection, turn: Turn) {
            val runId = turn.runId ?: return
            val sessionKey = turn.sessionKey ?: return
            conn.tryRequest("chat.abort", OpenClawChatRequests.chatAbortParams(sessionKey, runId), timing.abortTimeoutMs)
        }

        fun onChat(conn: Connection, frame: OpenClawProtocol.Frame.Chat) {
            if (conn !== current) return // a socket we already left
            val turn = active ?: return
            turn.accept(frame)
        }
    }

}

private fun OpenClawLinkState.toTurnResult(): OpenClawTurnResult = when (this) {
    is OpenClawLinkState.AwaitingApproval -> OpenClawTurnResult.NeedsApproval(requestId)
    OpenClawLinkState.Revoked -> OpenClawTurnResult.Revoked
    is OpenClawLinkState.Failed -> OpenClawTurnResult.Failed(failure)
    else -> OpenClawTurnResult.Failed(OpenClawFailure.NetworkUnavailable)
}

data class OpenClawClientMetadata(
    val language: String = "pl",
    val clientVersion: String = "bydmate",
    val newId: () -> String = { UUID.randomUUID().toString() },
)
