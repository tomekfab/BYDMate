package com.bydmate.app.voice.openclaw

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Self-signed `localhost` certificate: the server presents it, only the test client trusts it. */
private class TestTls {
    val serverContext: SSLContext
    private val clientContext: SSLContext
    private val trustManager: X509TrustManager

    init {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=localhost")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now), Date(now - 60_000), Date(now + 3_600_000), name, pair.public,
        )
        builder.addExtension(
            Extension.subjectAlternativeName, false,
            GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, "localhost"), GeneralName(GeneralName.iPAddress, "127.0.0.1"))),
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)))

        val serverKeys = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", pair.private, CharArray(0), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(serverKeys, CharArray(0)) }
        serverContext = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }

        val trusted = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("server", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
        trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
        clientContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    }

    /** The production client, plus trust for the test certificate. */
    fun trustingClient(): OkHttpClient =
        OpenClawHttp.newClient().newBuilder().sslSocketFactory(clientContext.socketFactory, trustManager).build()
}

/** A scripted Gateway on MockWebServer over TLS. Every frame it receives is recorded. */
private class FakeGateway(tls: TestTls) {
    class Req(val conn: Int, val id: String, val method: String, val params: JSONObject, val raw: String = "") {
        /** UTF-8 size of the complete frame as the Gateway received it. */
        val frameBytes: Int get() = raw.toByteArray(Charsets.UTF_8).size
    }

    inner class Ctx(val conn: Int, val ws: WebSocket) {
        val nonce = "nonce-$conn"
        val ts = 1_700_000_000_000L + conn
        fun send(json: JSONObject) { ws.send(json.toString()) }
        fun sendChallenge() = send(
            JSONObject().put("type", "event").put("event", "connect.challenge")
                .put("payload", JSONObject().put("nonce", nonce).put("ts", ts)),
        )
        fun ok(req: Req, payload: JSONObject) = send(
            JSONObject().put("type", "res").put("id", req.id).put("ok", true).put("payload", payload),
        )
        fun error(req: Req, code: String, detail: String? = null, requestId: String? = null, message: String = "m") {
            val details = JSONObject()
            detail?.let { details.put("code", it) }
            requestId?.let { details.put("requestId", it) }
            send(
                JSONObject().put("type", "res").put("id", req.id).put("ok", false)
                    .put("error", JSONObject().put("code", code).put("message", message).put("details", details)),
            )
        }
        fun helloOk(req: Req, token: String? = "tok-1", scopes: List<String> = listOf("operator.read", "operator.write"), protocol: Int = 4, edit: (JSONObject) -> Unit = {}) {
            val auth = JSONObject().put("role", "operator").put("scopes", JSONArray(scopes))
            token?.let { auth.put("deviceToken", it) }
            ok(
                req,
                JSONObject().put("type", "hello-ok").put("protocol", protocol)
                    .put("server", JSONObject().put("version", "2026.10.1"))
                    .put("features", JSONObject().put("methods", JSONArray(listOf("chat.send", "chat.abort", "chat.history"))))
                    .put("auth", auth).also(edit),
            )
        }
        /** A hello-ok that advertises `policy.maxPayload` (and an unrelated field the client ignores). */
        fun helloWithLimit(req: Req, maxPayload: Any, token: String? = "tok-1") = helloOk(req, token = token) {
            it.put("policy", JSONObject().put("maxPayload", maxPayload).put("maxBufferedBytes", 7).put("tickIntervalMs", 15_000))
        }
        fun ack(req: Req, runId: String, status: String = "started") =
            ok(req, JSONObject().put("runId", runId).put("status", status))
        fun chat(runId: String, seq: Int?, state: String, content: ChatContent = ChatContent()) {
            val payload = JSONObject().put("runId", runId).put("sessionKey", "s").put("state", state)
            seq?.let { payload.put("seq", it) }
            content.deltaText?.let { payload.put("deltaText", it) }
            content.errorKind?.let { payload.put("errorKind", it).put("errorMessage", "server text") }
            content.text?.let {
                payload.put(
                    "message",
                    JSONObject().put("role", "assistant")
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", it))),
                )
            }
            send(JSONObject().put("type", "event").put("event", "chat").put("payload", payload))
        }
        fun history(rows: List<Pair<String, String>>, active: Boolean = false) = JSONObject()
            .put("sessionInfo", JSONObject().put("hasActiveRun", active))
            .put(
                "messages",
                JSONArray().also { arr ->
                    rows.forEach { (role, text) ->
                        arr.put(
                            JSONObject().put("role", role).put(
                                "content",
                                if (role == "user") text else JSONArray().put(JSONObject().put("type", "text").put("text", text)),
                            ),
                        )
                    }
                },
            )
    }

    val server = MockWebServer()
    val received = CopyOnWriteArrayList<Req>()
    val connections = AtomicInteger()
    private val runCounter = AtomicInteger()
    val signatureChecks = CopyOnWriteArrayList<Boolean>()
    /** Connections that the client closed or dropped, as the Gateway saw it. */
    val closedConnections: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    var challenge: (Ctx) -> Unit = { it.sendChallenge() }
    var onConnect: (Ctx, Req) -> Unit = { ctx, req -> ctx.helloOk(req) }
    var onChatSend: (Ctx, Req) -> Unit = { ctx, req -> answer(ctx, req, "Odpowiedź") }
    var onHistory: (Ctx, Req) -> Unit = { ctx, req -> ctx.ok(req, ctx.history(emptyList())) }
    var onAbort: (Ctx, Req) -> Unit = { ctx, req ->
        ctx.ok(req, JSONObject().put("aborted", true).put("runIds", JSONArray(listOf(req.params.getString("runId")))))
    }
    var upgrade: (RecordedRequest) -> MockResponse = { MockResponse().withWebSocketUpgrade(listener(connections.incrementAndGet())) }

    init {
        server.useHttps(tls.serverContext.socketFactory, false)
        server.protocols = listOf(Protocol.HTTP_1_1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = upgrade(request)
        }
        server.start()
    }

    val url get() = "wss://localhost:${server.port}/"

    fun answer(ctx: Ctx, req: Req, text: String): String {
        val runId = nextRun()
        ctx.ack(req, runId)
        ctx.chat(runId, 1, "delta", content = ChatContent(deltaText = text))
        ctx.chat(runId, 2, "final", content = ChatContent(text = text))
        return runId
    }

    fun nextRun() = "run-${runCounter.incrementAndGet()}"

    fun methods() = received.map { it.method }
    fun sends() = received.filter { it.method == "chat.send" }

    fun listener(conn: Int) = object : WebSocketListener() {
        private lateinit var ctx: Ctx

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            ctx = Ctx(conn, webSocket)
            challenge(ctx)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closedConnections.add(conn)
            webSocket.close(code, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            closedConnections.add(conn)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = JSONObject(text)
            if (frame.optString("type") != "req") return
            val req = Req(conn, frame.getString("id"), frame.getString("method"), frame.optJSONObject("params") ?: JSONObject(), text)
            received.add(req)
            when (req.method) {
                "connect" -> {
                    signatureChecks.add(signatureValid(ctx, req.params))
                    onConnect(ctx, req)
                }
                "chat.send" -> onChatSend(ctx, req)
                "chat.history" -> onHistory(ctx, req)
                "chat.abort" -> onAbort(ctx, req)
                else -> ctx.error(req, "UNKNOWN_METHOD")
            }
        }
    }

    private fun signatureValid(ctx: Ctx, params: JSONObject): Boolean = runCatching {
        val device = params.getJSONObject("device")
        val token = params.optJSONObject("auth")?.optString("deviceToken").orEmpty()
        val payload = listOf(
            "v3", device.getString("id"), "gateway-client", "ui", "operator", "operator.read,operator.write",
            ctx.ts.toString(), token, ctx.nonce, "android", "phone",
        ).joinToString("|")
        val publicKey = Base64.getUrlDecoder().decode(device.getString("publicKey"))
        val signature = Base64.getUrlDecoder().decode(device.getString("signature"))
        val deviceIdOk = device.getString("id") ==
            java.security.MessageDigest.getInstance("SHA-256").digest(publicKey).joinToString("") { "%02x".format(it) }
        deviceIdOk && device.getString("nonce") == ctx.nonce && device.getLong("signedAt") == ctx.ts &&
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(payload.toByteArray(), 0, payload.length)
            }.verifySignature(signature)
    }.getOrDefault(false)

    fun shutdown() = runCatching { server.shutdown() }
}

@Suppress("LargeClass") // One TLS gateway fixture covers the client transport contract end to end.
class OpenClawClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var tls: TestTls
    private lateinit var gateway: FakeGateway
    private val cipher = FakeSecretCipher()
    private val extra = CopyOnWriteArrayList<FakeGateway>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun dir() = File(tmp.root, "openclaw")
    private fun identityStore() = OpenClawIdentityStore(dir(), cipher)
    private fun conversationStore() = OpenClawConversationStore(dir(), "tomek")

    @Before
    fun setUp() {
        tls = TestTls()
        gateway = FakeGateway(tls)
    }

    @After
    fun tearDown() {
        scope.cancel()
        gateway.shutdown()
        extra.forEach { it.shutdown() }
    }

    private fun client(
        http: OkHttpClient = tls.trustingClient(),
        timing: OpenClawTiming = FAST,
        endpoint: String = gateway.url,
        attachments: OpenClawTurnAttachments = OpenClawTurnAttachments.None,
    ) = OpenClawClient(
        endpoint = OpenClawEndpoint.parse(endpoint)!!,
        identityStore = identityStore(),
        conversationStore = conversationStore(),
        httpClient = http,
        timing = timing,
        attachments = attachments,
    )

    /** A device that was approved earlier: identity and token already on disk. */
    private fun pairedDevice(token: String = "tok-1") {
        identityStore().getOrCreateIdentity()
        identityStore().saveDeviceToken(token)
    }

    private class MutablePrefs(var enabledValue: Boolean, var endpoint: String) : OpenClawPrefs {
        override fun isEnabled(): Boolean = enabledValue
        override fun setEnabled(enabled: Boolean) { enabledValue = enabled }
        override fun endpointText(): String = endpoint
        override fun setEndpointText(text: String) { endpoint = text.trim() }
    }

    private fun assistant(prefs: MutablePrefs, timing: OpenClawTiming = FAST): OpenClawAssistant {
        val identity = identityStore()
        val conversation = conversationStore()
        return OpenClawAssistant(
            prefs = prefs,
            chatFor = { endpoint ->
                OpenClawClientChat(
                    OpenClawClient(endpoint, identity, conversation, tls.trustingClient(), timing),
                )
            },
            credentials = StoredOpenClawCredentials(identity, conversation),
        )
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(30_000) { block() } }

    private fun turnTag(message: String) = Regex("\\(#([0-9a-f]+)\\)").find(message)!!.groupValues[1]

    // --- endpoint, TLS and host rules -------------------------------------------------------

    @Test
    fun `endpoint accepts only a plain wss url`() {
        val ok = OpenClawEndpoint.parse("wss://bobs-mac-mini-openclaw.tail3549c7.ts.net/")!!
        assertEquals("bobs-mac-mini-openclaw.tail3549c7.ts.net", ok.host)
        assertEquals("host.example", OpenClawEndpoint.parse("WSS://Host.Example/")!!.host)
        for (bad in listOf(
            "ws://bobs-mac-mini-openclaw.tail3549c7.ts.net/",
            "http://bobs-mac-mini-openclaw.tail3549c7.ts.net/",
            "https://bobs-mac-mini-openclaw.tail3549c7.ts.net/",
            "wss://user:pw@host.example/",
            "wss://host.example/?token=abc",
            "wss://host.example/#frag",
            "wss:///",
            "",
            "host.example",
        )) {
            assertNull("must reject $bad", OpenClawEndpoint.parse(bad))
        }
    }

    @Test
    fun `an untrusted certificate is rejected by normal validation`() {
        val client = client(http = OpenClawHttp.newClient())
        val state = run { client.connect() }
        assertEquals(OpenClawLinkState.Failed(OpenClawFailure.TlsRejected), state)
        assertEquals("no websocket upgrade may have reached the server", 0, gateway.connections.get())
        assertTrue(gateway.received.isEmpty())
    }

    @Test
    fun `a redirect to another host is not followed`() {
        val other = FakeGateway(tls).also { extra.add(it) }
        gateway.upgrade = {
            MockResponse().setResponseCode(302).setHeader("Location", "https://localhost:${other.server.port}/")
        }
        val state = run { client().connect() }
        assertEquals(OpenClawLinkState.Failed(OpenClawFailure.HostRejected), state)
        assertEquals(0, other.server.requestCount)
        assertEquals(0, other.connections.get())
        assertTrue(gateway.received.isEmpty())
    }

    @Test
    fun `an injected http client cannot turn redirect following back on`() {
        val other = FakeGateway(tls).also { extra.add(it) }
        gateway.upgrade = {
            MockResponse().setResponseCode(302).setHeader("Location", "https://localhost:${other.server.port}/")
        }
        val following = tls.trustingClient().newBuilder().followRedirects(true).followSslRedirects(true).build()
        val state = run { client(http = following).connect() }
        assertEquals(OpenClawLinkState.Failed(OpenClawFailure.HostRejected), state)
        assertEquals(0, other.server.requestCount)
    }

    @Test
    fun `the production http client never follows redirects and adds no trust override`() {
        val http = OpenClawHttp.newClient()
        assertFalse(http.followRedirects)
        assertFalse(http.followSslRedirects)
        assertTrue("no pins and no custom trust: the system store decides", http.certificatePinner.pins.isEmpty())
    }

    // --- challenge, hello, pairing --------------------------------------------------------------

    @Test
    fun `a missing challenge fails and no connect frame is sent`() {
        gateway.challenge = { /* the Gateway stays silent */ }
        val state = run { client(timing = FAST.copy(challengeTimeoutMs = 300)).connect() }
        assertEquals(OpenClawLinkState.Failed(OpenClawFailure.ChallengeFailed), state)
        assertTrue(gateway.received.isEmpty())
    }

    @Test
    fun `a challenge without a nonce fails and no connect frame is sent`() {
        gateway.challenge = { ctx ->
            ctx.send(
                JSONObject().put("type", "event").put("event", "connect.challenge")
                    .put("payload", JSONObject().put("ts", 1L)),
            )
        }
        val state = run { client().connect() }
        assertEquals(OpenClawLinkState.Failed(OpenClawFailure.ChallengeFailed), state)
        assertTrue(gateway.received.isEmpty())
    }

    @Test
    fun `a fresh identity needs approval and never chats`() {
        gateway.onConnect = { ctx, req -> ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-1") }
        val client = client()

        val state = run { client.connect() }
        assertEquals(OpenClawLinkState.AwaitingApproval("req-1"), state)
        assertEquals(OpenClawLinkState.AwaitingApproval("req-1"), client.linkState.value)
        assertNull(identityStore().deviceToken())

        val result = run { client.ask("Cześć") }
        assertEquals(OpenClawTurnResult.NeedsApproval("req-1"), result)
        assertTrue("no chat before approval", gateway.sends().isEmpty())
        assertEquals(setOf("connect"), gateway.methods().toSet())
    }

    @Test
    fun `pairing polls until approved then keeps the identity and token`() {
        gateway.onConnect = { ctx, req ->
            if (gateway.connections.get() < 3) ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-1")
            else ctx.helloOk(req, token = "tok-new")
        }
        val client = client()

        val state = run { client.pair() }

        assertEquals(OpenClawLinkState.Ready, state)
        assertEquals("tok-new", identityStore().deviceToken())
        assertEquals(3, gateway.received.count { it.method == "connect" })
        val deviceIds = gateway.received.map { it.params.getJSONObject("device").getString("id") }.toSet()
        assertEquals("one identity across every attempt", 1, deviceIds.size)
        assertEquals(deviceIds.single(), identityStore().identity()!!.deviceId)
        assertTrue("every signature verified", gateway.signatureChecks.all { it })
        assertFalse(gateway.received.any { it.params.has("auth") })
    }

    @Test
    fun `pairing gives up after the timeout and stays awaiting approval`() {
        gateway.onConnect = { ctx, req -> ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-1") }
        val state = run { client(timing = FAST.copy(pairTimeoutMs = 150)).pair() }
        assertEquals(OpenClawLinkState.AwaitingApproval("req-1"), state)
        assertNull(identityStore().deviceToken())
    }

    @Test
    fun `connect requests exactly read and write and the signature verifies`() {
        pairedDevice("tok-old")
        val state = run { client().connect() }
        assertEquals(OpenClawLinkState.Ready, state)
        val connect = gateway.received.single { it.method == "connect" }.params
        assertEquals(listOf("operator.read", "operator.write"), (0 until 2).map { connect.getJSONArray("scopes").getString(it) })
        assertEquals("operator", connect.getString("role"))
        assertEquals("tok-old", connect.getJSONObject("auth").getString("deviceToken"))
        assertEquals(listOf(true), gateway.signatureChecks)
    }

    @Test
    fun `a grant wider than read and write is refused and its token is not kept`() {
        gateway.onConnect = { ctx, req -> ctx.helloOk(req, scopes = listOf("operator.read", "operator.write", "operator.admin")) }
        val state = run { client().connect() }
        assertEquals(OpenClawLinkState.Failed(OpenClawFailure.ScopeMismatch), state)
        assertNull(identityStore().deviceToken())
        assertTrue(gateway.sends().isEmpty())
    }

    @Test
    fun `a grant narrower than read and write is refused`() {
        gateway.onConnect = { ctx, req -> ctx.helloOk(req, scopes = listOf("operator.read")) }
        assertEquals(OpenClawLinkState.Failed(OpenClawFailure.ScopeMismatch), run { client().connect() })
        assertNull(identityStore().deviceToken())
    }

    @Test
    fun `another protocol version is refused`() {
        gateway.onConnect = { ctx, req -> ctx.helloOk(req, protocol = 3) }
        assertEquals(OpenClawLinkState.Failed(OpenClawFailure.ProtocolMismatch), run { client().connect() })
        assertNull(identityStore().deviceToken())
    }

    @Test
    fun `a rotated token replaces the stored one`() {
        pairedDevice("tok-old")
        gateway.onConnect = { ctx, req -> ctx.helloOk(req, token = "tok-rotated") }
        assertEquals(OpenClawLinkState.Ready, run { client().connect() })
        assertEquals("tok-rotated", identityStore().deviceToken())
    }

    // --- revocation ------------------------------------------------------------------------------

    @Test
    fun `remote revocation is detected and every credential is cleared`() {
        pairedDevice("tok-old")
        val sessionBefore = conversationStore().sessionKey("pl")
        gateway.onConnect = { ctx, req -> ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-9") }
        val client = client()

        val result = run { client.ask("Cześć") }

        assertEquals(OpenClawTurnResult.Revoked, result)
        assertEquals(OpenClawLinkState.Revoked, client.linkState.value)
        assertEquals("tok-old", gateway.received.single().params.getJSONObject("auth").getString("deviceToken"))
        assertNull(identityStore().identity())
        assertNull(identityStore().deviceToken())
        assertTrue(dir().listFiles().orEmpty().isEmpty())
        assertNotEquals(sessionBefore, conversationStore().sessionKey("pl"))
        assertTrue(gateway.sends().isEmpty())
    }

    @Test
    fun `a rejected token is also a revocation`() {
        pairedDevice("tok-old")
        gateway.onConnect = { ctx, req -> ctx.error(req, "UNAUTHORIZED", "AUTH_TOKEN_MISMATCH") }
        val client = client()
        assertEquals(OpenClawLinkState.Revoked, run { client.connect() })
        assertNull(identityStore().deviceToken())
        assertNull(identityStore().identity())
    }

    @Test
    fun `disconnect clears credentials and conversation and the next connect is a new device`() {
        pairedDevice()
        val client = client()
        assertEquals(OpenClawTurnResult.Answer("Odpowiedź"), run { client.ask("Cześć") })
        val firstDevice = gateway.received.first { it.method == "connect" }.params.getJSONObject("device").getString("id")
        val firstSession = gateway.sends().single().params.getString("sessionKey")

        run { client.disconnect() }

        assertEquals(OpenClawLinkState.Unpaired, client.linkState.value)
        assertNull(identityStore().identity())
        assertNull(identityStore().deviceToken())
        assertTrue(dir().listFiles().orEmpty().isEmpty())

        gateway.onConnect = { ctx, req -> ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-2") }
        assertEquals(OpenClawLinkState.AwaitingApproval("req-2"), run { client.connect() })
        val secondDevice = gateway.received.last { it.method == "connect" }.params.getJSONObject("device").getString("id")
        assertNotEquals(firstDevice, secondDevice)
        assertNotEquals(firstSession, conversationStore().sessionKey("pl"))
    }

    @Test
    fun `disconnect during a handshake cannot save a late token`() {
        val hello = CompletableDeferred<Pair<FakeGateway.Ctx, FakeGateway.Req>>()
        gateway.onConnect = { ctx, req -> hello.complete(ctx to req) }
        val client = client()
        val connecting = scope.async { client.connect() }
        val (ctx, req) = run { hello.await() }

        run { client.disconnect() }
        ctx.helloOk(req, token = "tok-late")
        run { connecting.await() }

        assertEquals(OpenClawLinkState.Unpaired, client.linkState.value)
        assertNull(identityStore().identity())
        assertNull(identityStore().deviceToken())
    }

    @Test
    fun `disconnect during pairing poll prevents another connection attempt`() {
        gateway.onConnect = { ctx, req -> ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-1") }
        val client = client(timing = FAST.copy(pairTimeoutMs = 50, pairPollMs = 200))
        val pairing = scope.async { client.pair() }

        run {
            withTimeout(2_000) {
                while (client.linkState.value !is OpenClawLinkState.AwaitingApproval) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
        run { client.disconnect() }
        run { pairing.await() }

        assertEquals(OpenClawLinkState.Unpaired, client.linkState.value)
        assertEquals(1, gateway.received.count { it.method == "connect" })
        assertNull(identityStore().identity())
        assertNull(identityStore().deviceToken())
    }

    @Test
    fun `opting out during pairing cannot save a late token or continue traffic`() {
        val hello = CompletableDeferred<Pair<FakeGateway.Ctx, FakeGateway.Req>>()
        gateway.onConnect = { ctx, req -> hello.complete(ctx to req) }
        val prefs = MutablePrefs(enabledValue = true, endpoint = gateway.url)
        val assistant = assistant(prefs)
        val pairing = scope.async { assistant.pair() }
        val (ctx, req) = run { hello.await() }

        run { assistant.setEnabled(false) }
        ctx.helloOk(req, token = "tok-late")
        run { pairing.await() }

        assertFalse(prefs.enabledValue)
        assertNull(identityStore().deviceToken())
        assertEquals(1, gateway.received.count { it.method == "connect" })
    }

    @Test
    fun `opting out during reconnect preserves the paired identity and token`() {
        pairedDevice("tok-existing")
        val identityBefore = identityStore().identity()!!.deviceId
        val hello = CompletableDeferred<Pair<FakeGateway.Ctx, FakeGateway.Req>>()
        gateway.onConnect = { ctx, req -> hello.complete(ctx to req) }
        val prefs = MutablePrefs(enabledValue = true, endpoint = gateway.url)
        val assistant = assistant(prefs)
        val reconnecting = scope.async { assistant.reconnect() }
        val (ctx, req) = run { hello.await() }

        run { assistant.setEnabled(false) }
        ctx.helloOk(req, token = "tok-rotated")
        run { reconnecting.await() }

        assertFalse(prefs.enabledValue)
        assertEquals(identityBefore, identityStore().identity()!!.deviceId)
        assertEquals("tok-existing", identityStore().deviceToken())
        assertEquals(1, gateway.received.count { it.method == "connect" })
    }

    @Test
    fun `a connect parked behind another captures its operation before it suspends`() {
        // OpenClawAssistant starts connect() UNDISPATCHED under its own mutex and relies on this:
        // an opt-out or forget that runs after that point must stop the parked connect.
        pairedDevice("tok-existing")
        val hello = CompletableDeferred<Pair<FakeGateway.Ctx, FakeGateway.Req>>()
        gateway.onConnect = { ctx, req -> hello.complete(ctx to req) }
        val client = client()
        val first = scope.async { client.connect() }
        run { hello.await() } // the first connect owns the handshake and waits for the Gateway

        val parked = scope.async(start = CoroutineStart.UNDISPATCHED) { client.connect() }
        run { client.cancelConnectionOperations() }
        run { first.await() }
        val result = run { parked.await() }

        assertEquals(OpenClawLinkState.Disconnected, result)
        assertEquals(OpenClawLinkState.Disconnected, client.linkState.value)
        assertEquals("no second socket after the invalidation", 1, gateway.connections.get())
        assertEquals(1, gateway.received.count { it.method == "connect" })
        assertEquals("tok-existing", identityStore().deviceToken())
    }

    @Test
    fun `revocation invalidates a queued automatic connect so it cannot create a new identity`() {
        pairedDevice("tok-existing")
        val hello = CompletableDeferred<Pair<FakeGateway.Ctx, FakeGateway.Req>>()
        gateway.onConnect = { ctx, req ->
            if (ctx.conn == 1) hello.complete(ctx to req) else ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-2")
        }
        val client = client()
        val first = scope.async { client.connect() }
        val (ctx, req) = run { hello.await() }
        val queued = scope.async(start = CoroutineStart.UNDISPATCHED) { client.connect() }

        ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-1")
        val owner = run { first.await() }
        val parked = run { queued.await() }

        assertEquals(OpenClawLinkState.Revoked, owner)
        assertEquals(OpenClawLinkState.Revoked, parked)
        assertEquals(OpenClawLinkState.Revoked, client.linkState.value)
        assertEquals("the queued connect opened no second socket", 1, gateway.connections.get())
        assertEquals(1, gateway.received.count { it.method == "connect" })
        assertNull(identityStore().identity())
        assertNull(identityStore().deviceToken())
    }

    @Test
    fun `an explicit pair after revocation is still a user action that creates a new device`() {
        pairedDevice("tok-existing")
        gateway.onConnect = { ctx, req -> ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-1") }
        val client = client(timing = FAST.copy(pairTimeoutMs = 50, pairPollMs = 20))
        assertEquals(OpenClawLinkState.Revoked, run { client.connect() })
        assertNull(identityStore().identity())

        gateway.onConnect = { ctx, req -> ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-9") }
        val result = run { client.pair() }

        assertEquals(OpenClawLinkState.AwaitingApproval("req-9"), result)
        assertNotEquals(null, identityStore().identity())
        assertNull(identityStore().deviceToken())
    }

    @Test
    fun `a turn revocation publishes Revoked and clears every credential`() {
        pairedDevice("tok-existing")
        gateway.onChatSend = { ctx, req -> ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-7") }
        val client = client()

        val result = run { client.ask("Cześć") }

        assertEquals(OpenClawTurnResult.Revoked, result)
        assertEquals(OpenClawLinkState.Revoked, client.linkState.value)
        assertNull(identityStore().identity())
        assertNull(identityStore().deviceToken())
        assertTrue("the revoked live socket is closed", awaitClosedConnections(1))
        assertFalse(client.hasLiveConnection)
    }

    @Test
    fun `a paired-only connect after a revocation creates no identity`() {
        pairedDevice("tok-existing")
        gateway.onConnect = { ctx, req ->
            ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-${ctx.conn}")
        }
        val client = client()
        assertEquals(OpenClawLinkState.Revoked, run { client.connect() })

        // The revocation landed before this connect captured its operation: it is a fresh, valid operation.
        val result = run { client.connectPaired() }

        assertEquals(OpenClawLinkState.Revoked, result)
        assertEquals(OpenClawLinkState.Revoked, client.linkState.value)
        assertEquals("no second socket", 1, gateway.connections.get())
        assertNull(identityStore().identity())
        assertNull(identityStore().deviceToken())
    }

    @Test
    fun `a paired-only connect without any pairing creates no identity while explicit pair still does`() {
        gateway.onConnect = { ctx, req -> ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-9") }
        val client = client(timing = FAST.copy(pairTimeoutMs = 50, pairPollMs = 20))

        assertEquals(OpenClawLinkState.Unpaired, run { client.connectPaired() })
        assertEquals(OpenClawLinkState.Unpaired, client.linkState.value)
        assertEquals(0, gateway.connections.get())
        assertNull(identityStore().identity())

        assertEquals(OpenClawLinkState.AwaitingApproval("req-9"), run { client.pair() })
        assertNotEquals(null, identityStore().identity())
    }

    @Test
    fun `a queued paired-only connect is stopped by a revocation that follows its capture`() {
        pairedDevice("tok-existing")
        val hello = CompletableDeferred<Pair<FakeGateway.Ctx, FakeGateway.Req>>()
        gateway.onConnect = { ctx, req ->
            if (ctx.conn == 1) hello.complete(ctx to req) else ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-2")
        }
        val client = client()
        val first = scope.async { client.connectPaired() }
        val (ctx, req) = run { hello.await() }
        val queued = scope.async(start = CoroutineStart.UNDISPATCHED) { client.connectPaired() }

        ctx.error(req, "NOT_PAIRED", "PAIRING_REQUIRED", requestId = "req-1")

        assertEquals(OpenClawLinkState.Revoked, run { first.await() })
        assertEquals(OpenClawLinkState.Revoked, run { queued.await() })
        assertEquals(1, gateway.connections.get())
        assertNull(identityStore().identity())
    }

    @Test
    fun `a socket that closes between handshake success and publication is never published as Ready`() {
        pairedDevice("tok-existing")
        val serverSide = CompletableDeferred<FakeGateway.Ctx>()
        gateway.onConnect = { ctx, req ->
            serverSide.complete(ctx)
            ctx.helloOk(req, token = "tok-existing")
        }
        val client = client()
        client.beforePublish = { closed ->
            serverSide.await().ws.close(1000, null)
            closed.await()
            // The socket handler has also dropped the live handle: only the publication is left.
            while (client.hasLiveConnection) kotlinx.coroutines.delay(5)
        }

        val result = run { client.connect() }

        assertEquals(OpenClawLinkState.Failed(OpenClawFailure.NetworkUnavailable), result)
        assertNotEquals(OpenClawLinkState.Ready, client.linkState.value)
        assertFalse(client.hasLiveConnection)
        assertEquals("tok-existing", identityStore().deviceToken())
    }

    @Test
    fun `a socket that closes right after hello never leaves a Ready link without a socket`() {
        pairedDevice("tok-existing")
        gateway.onConnect = { ctx, req ->
            ctx.helloOk(req, token = "tok-existing")
            ctx.ws.close(1000, null)
        }
        repeat(10) { attempt ->
            val client = client()
            run { client.connect() }
            val left = run {
                withTimeout(3_000) {
                    while (client.linkState.value == OpenClawLinkState.Ready) kotlinx.coroutines.delay(10)
                    true
                }
            }
            assertTrue("attempt $attempt", left)
            assertFalse("attempt $attempt", client.hasLiveConnection)
        }
    }

    @Test
    fun `a live Ready connection that drops later leaves Ready and releases its handle`() {
        pairedDevice("tok-existing")
        val serverSide = CompletableDeferred<FakeGateway.Ctx>()
        gateway.onConnect = { ctx, req ->
            serverSide.complete(ctx)
            ctx.helloOk(req, token = "tok-existing")
        }
        val client = client()
        assertEquals(OpenClawLinkState.Ready, run { client.connect() })
        assertTrue(client.hasLiveConnection)

        run { serverSide.await() }.ws.close(1000, null)

        run {
            withTimeout(3_000) {
                while (client.linkState.value == OpenClawLinkState.Ready) kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(OpenClawLinkState.Disconnected, client.linkState.value)
        assertFalse(client.hasLiveConnection)
    }

    private fun awaitClosedConnections(count: Int): Boolean = run {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (gateway.closedConnections.size < count && System.nanoTime() < deadline) kotlinx.coroutines.delay(10)
        gateway.closedConnections.size >= count
    }

    private fun awaitReceivedConnect(count: Int) = run {
        withTimeout(5_000) { while (gateway.received.count { it.method == "connect" } < count) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun `cancelling a connect during the challenge closes its socket and keeps the pairing`() {
        pairedDevice("tok-existing")
        gateway.challenge = { /* the Gateway stays silent */ }
        val client = client(timing = FAST.copy(challengeTimeoutMs = 30_000))
        val connecting = scope.async { client.connect() }
        run { withTimeout(5_000) { while (gateway.connections.get() < 1) kotlinx.coroutines.delay(10) } }

        connecting.cancel()
        run { connecting.join() }

        assertTrue("the abandoned socket is closed", awaitClosedConnections(1))
        assertEquals(OpenClawLinkState.Disconnected, client.linkState.value)
        assertEquals("tok-existing", identityStore().deviceToken())

        // A later attempt owns its own socket; the first handle is not reused or closed twice.
        gateway.challenge = { it.sendChallenge() }
        assertEquals(OpenClawLinkState.Ready, run { client.connect() })
        assertEquals(2, gateway.connections.get())
        assertEquals(setOf(1), gateway.closedConnections.toSet())
        run { client.cancelConnectionOperations() }
        assertTrue("the live connection is closed by the invalidation", awaitClosedConnections(2))
    }

    @Test
    fun `cancelling a connect during the hello closes its socket and keeps the pairing`() {
        pairedDevice("tok-existing")
        gateway.onConnect = { _, _ -> /* the Gateway never answers the connect request */ }
        val client = client(timing = FAST.copy(helloTimeoutMs = 30_000))
        val connecting = scope.async { client.connect() }
        awaitReceivedConnect(1)

        connecting.cancel()
        run { connecting.join() }

        assertTrue("the abandoned socket is closed", awaitClosedConnections(1))
        assertEquals(OpenClawLinkState.Disconnected, client.linkState.value)
        assertEquals("tok-existing", identityStore().deviceToken())
        assertNotEquals(null, identityStore().identity())

        gateway.onConnect = { ctx, req -> ctx.helloOk(req) }
        assertEquals(OpenClawLinkState.Ready, run { client.connect() })
        assertEquals(setOf(1), gateway.closedConnections.toSet())
    }

    @Test
    fun `an abandoned attempt never closes a newer connection that is already live`() {
        pairedDevice("tok-existing")
        val client = client()
        assertEquals(OpenClawLinkState.Ready, run { client.connect() })

        // A connect while Ready is not a new attempt; cancelling it must leave the live socket alone.
        val again = scope.async { client.connect() }
        again.cancel()
        run { again.join() }

        assertEquals(OpenClawLinkState.Ready, client.linkState.value)
        assertTrue(gateway.closedConnections.isEmpty())
    }

    // --- chat ------------------------------------------------------------------------------------

    @Test
    fun `a turn sends only the new text with the polish instruction and nothing else`() {
        pairedDevice()
        gateway.onChatSend = { ctx, req ->
            // The Gateway may announce a tool approval; the client has no scope to answer it and must not try.
            ctx.send(
                JSONObject().put("type", "event").put("event", "exec.approval.requested")
                    .put("payload", JSONObject().put("id", "approval-1").put("command", "rm -rf /")),
            )
            gateway.answer(ctx, req, "Jest słonecznie.")
        }
        val client = client()

        val result = run { client.ask("Jaka jest pogoda w Krakowie?") }

        assertEquals(OpenClawTurnResult.Answer("Jest słonecznie."), result)
        assertEquals(listOf("connect", "chat.send"), gateway.methods())
        val send = gateway.sends().single().params
        assertEquals(setOf("sessionKey", "message", "idempotencyKey"), send.keys().asSequence().toSet())
        val key = send.getString("idempotencyKey")
        assertEquals(key, UUID.fromString(key).toString())
        assertEquals(OpenClawProtocol.turnMessage("Jaka jest pogoda w Krakowie?", key), send.getString("message"))
        val connect = gateway.received.first().params
        assertEquals(0, connect.getJSONArray("caps").length())
        assertEquals(0, connect.getJSONArray("commands").length())
        assertEquals(0, connect.getJSONObject("permissions").length())
    }

    @Test
    fun `the car conversation is the tomek agent and stays stable across turns`() {
        pairedDevice()
        val client = client()
        run { client.ask("Pierwsze") }
        run { client.ask("Drugie") }
        val keys = gateway.sends().map { it.params.getString("sessionKey") }
        assertEquals(2, keys.size)
        assertEquals(keys[0], keys[1])
        assertTrue(keys[0], Regex("agent:tomek:bydmate-car-[0-9a-f]{8,}").matches(keys[0]))
        assertNotEquals("each turn gets its own idempotency key",
            gateway.sends()[0].params.getString("idempotencyKey"), gateway.sends()[1].params.getString("idempotencyKey"))
    }

    @Test
    fun `reset starts a new conversation and keeps the pairing`() {
        pairedDevice()
        val client = client()
        run { client.ask("Pierwsze") }
        val deviceBefore = identityStore().identity()!!.deviceId

        run { client.resetConversation() }
        run { client.ask("Drugie") }

        val keys = gateway.sends().map { it.params.getString("sessionKey") }
        assertNotEquals(keys[0], keys[1])
        assertEquals("tok-1", identityStore().deviceToken())
        assertEquals(deviceBefore, identityStore().identity()!!.deviceId)
    }

    @Test
    fun `deltas stream in order and only the final text is the answer`() {
        pairedDevice()
        gateway.onChatSend = { ctx, req ->
            val run = gateway.nextRun()
            ctx.ack(req, run)
            ctx.chat(run, 1, "status")
            ctx.chat(run, 2, "delta", content = ChatContent(deltaText = "Cze"))
            ctx.chat(run, 3, "delta", content = ChatContent(deltaText = "ść"))
            ctx.chat(run, 4, "final", content = ChatContent(text = "Cześć, Tomek!"))
        }
        val deltas = CopyOnWriteArrayList<String>()
        val result = run { client().ask("Hej") { deltas.add(it) } }
        assertEquals(OpenClawTurnResult.Answer("Cześć, Tomek!"), result)
        assertEquals(listOf("Cze", "ść"), deltas.toList())
    }

    @Test
    fun `out of order duplicate and foreign events are dropped`() {
        pairedDevice()
        gateway.onChatSend = { ctx, req ->
            val run = gateway.nextRun()
            ctx.ack(req, run)
            ctx.chat("other-run", 1, "final", content = ChatContent(text = "FOREIGN"))
            ctx.chat(run, 2, "delta", content = ChatContent(deltaText = "A"))
            ctx.chat(run, 2, "delta", content = ChatContent(deltaText = "A"))
            ctx.chat(run, 1, "delta", content = ChatContent(deltaText = "X"))
            ctx.chat(run, 3, "delta", content = ChatContent(deltaText = "B"))
            ctx.chat(run, 4, "final", content = ChatContent(text = "AB"))
            ctx.chat(run, 4, "final", content = ChatContent(text = "DUPLICATE"))
            ctx.chat(run, 5, "delta", content = ChatContent(deltaText = "LATE"))
        }
        val deltas = CopyOnWriteArrayList<String>()
        val result = run { client().ask("Hej") { deltas.add(it) } }
        assertEquals(OpenClawTurnResult.Answer("AB"), result)
        assertEquals(listOf("A", "B"), deltas.toList())
    }

    @Test
    fun `events that arrive before the ack still belong to the run`() {
        pairedDevice()
        gateway.onChatSend = { ctx, req ->
            val run = gateway.nextRun()
            ctx.chat(run, 1, "delta", content = ChatContent(deltaText = "Wc"))
            ctx.chat(run, 2, "final", content = ChatContent(text = "Wcześnie"))
            ctx.ack(req, run)
        }
        val deltas = CopyOnWriteArrayList<String>()
        val result = run { client().ask("Hej") { deltas.add(it) } }
        assertEquals(OpenClawTurnResult.Answer("Wcześnie"), result)
        assertEquals(listOf("Wc"), deltas.toList())
    }

    @Test
    fun `an error run never speaks`() {
        pairedDevice()
        gateway.onChatSend = { ctx, req ->
            val run = gateway.nextRun()
            ctx.ack(req, run)
            ctx.chat(run, 1, "delta", content = ChatContent(deltaText = "pół"))
            ctx.chat(run, 2, "error", content = ChatContent(errorKind = "model_unavailable"))
        }
        val result = run { client().ask("Hej") }
        assertEquals(OpenClawTurnResult.Failed(OpenClawFailure.ServerError), result)
        assertEquals(1, gateway.sends().size)
    }

    @Test
    fun `an empty final answer is a failure not silence`() {
        pairedDevice()
        gateway.onChatSend = { ctx, req ->
            val run = gateway.nextRun()
            ctx.ack(req, run)
            ctx.chat(run, 1, "final", content = ChatContent(text = "   "))
        }
        assertEquals(OpenClawTurnResult.Failed(OpenClawFailure.EmptyAnswer), run { client().ask("Hej") })
    }

    @Test
    fun `an explicit server refusal of chat send is final and never replayed`() {
        pairedDevice()
        gateway.onChatSend = { ctx, req -> ctx.error(req, "FORBIDDEN", "MISSING_SCOPE") }
        val client = client()
        assertEquals(OpenClawTurnResult.Failed(OpenClawFailure.Forbidden), run { client.ask("Hej") })
        assertEquals(1, gateway.sends().size)
        assertEquals(OpenClawTurnResult.Failed(OpenClawFailure.NothingToResend), run { client.resendUncertain() })
        assertEquals(1, gateway.sends().size)
    }

    @Test
    fun `a blank request sends nothing`() {
        pairedDevice()
        assertEquals(OpenClawTurnResult.Failed(OpenClawFailure.EmptyRequest), run { client().ask("   ") })
        assertTrue(gateway.received.isEmpty())
    }

    @Test
    fun `a second turn while one is running is refused`() {
        pairedDevice()
        val release = CompletableDeferred<Unit>()
        val arrived = CountDownLatch(1)
        gateway.onChatSend = { ctx, req ->
            arrived.countDown()
            runBlocking { release.await() }
            gateway.answer(ctx, req, "Pierwsza")
        }
        val client = client()
        val first = scope.async { client.ask("Pierwsze") }
        assertTrue(arrived.await(10, TimeUnit.SECONDS))
        assertEquals(OpenClawTurnResult.Failed(OpenClawFailure.Busy), run { client.ask("Drugie") })
        release.complete(Unit)
        assertEquals(OpenClawTurnResult.Answer("Pierwsza"), run { first.await() })
        assertEquals(1, gateway.sends().size)
    }

    // --- uncertain delivery and recovery -------------------------------------------------------

    @Test
    fun `a lost ack is recovered from history without sending again`() {
        pairedDevice()
        gateway.onChatSend = { ctx, _ -> ctx.ws.cancel() } // connection dies before any ack
        gateway.onHistory = { ctx, req ->
            val tag = turnTag(gateway.sends().single().params.getString("message"))
            ctx.ok(req, ctx.history(listOf("user" to "Hej (#$tag)", "assistant" to "Cześć po powrocie")))
        }
        val result = run { client().ask("Hej") }
        assertEquals(OpenClawTurnResult.Answer("Cześć po powrocie"), result)
        assertEquals("zero replay", 1, gateway.sends().size)
        assertTrue(gateway.methods().contains("chat.history"))
        assertTrue("a new connection recovered it", gateway.connections.get() >= 2)
    }

    @Test
    fun `reconnect waits for a still running turn and sends nothing again`() {
        pairedDevice()
        val polls = AtomicInteger()
        gateway.onChatSend = { ctx, req ->
            val run = gateway.nextRun()
            ctx.ack(req, run)
            ctx.chat(run, 1, "delta", content = ChatContent(deltaText = "Pa"))
            ctx.ws.cancel() // connection dies mid answer
        }
        gateway.onHistory = { ctx, req ->
            val tag = turnTag(gateway.sends().single().params.getString("message"))
            if (polls.incrementAndGet() <= 2) {
                ctx.ok(req, ctx.history(listOf("user" to "Hej (#$tag)"), active = true))
            } else {
                ctx.ok(req, ctx.history(listOf("user" to "Hej (#$tag)", "assistant" to "Pełna odpowiedź")))
            }
        }
        val result = run { client().ask("Hej") }
        assertEquals(OpenClawTurnResult.Answer("Pełna odpowiedź"), result)
        assertEquals(1, gateway.sends().size)
        assertTrue(polls.get() >= 3)
    }

    @Test
    fun `history of an earlier identical question is not mistaken for this turn`() {
        pairedDevice()
        gateway.onChatSend = { ctx, _ -> ctx.ws.cancel() }
        gateway.onHistory = { ctx, req ->
            ctx.ok(req, ctx.history(listOf("user" to "Hej (#aaaaaaaaaaaa)", "assistant" to "STARA ODPOWIEDŹ")))
        }
        val result = run { client().ask("Hej") }
        assertEquals(OpenClawTurnResult.Uncertain, result)
        assertEquals(1, gateway.sends().size)
    }

    @Test
    fun `an uncertain send with no trace is reported and only an explicit resend reuses the same key`() {
        pairedDevice()
        val sendCount = AtomicInteger()
        gateway.onChatSend = { ctx, req ->
            if (sendCount.incrementAndGet() == 1) ctx.ws.cancel() else gateway.answer(ctx, req, "Teraz działa")
        }
        val client = client()

        assertEquals(OpenClawTurnResult.Uncertain, run { client.ask("Zamknij okno") })
        assertEquals("automatic recovery never replays", 1, gateway.sends().size)

        assertEquals(OpenClawTurnResult.Answer("Teraz działa"), run { client.resendUncertain() })
        val sends = gateway.sends()
        assertEquals(2, sends.size)
        assertEquals(sends[0].params.getString("idempotencyKey"), sends[1].params.getString("idempotencyKey"))
        assertEquals(sends[0].params.getString("message"), sends[1].params.getString("message"))
        assertEquals(sends[0].params.getString("sessionKey"), sends[1].params.getString("sessionKey"))
        assertEquals(OpenClawTurnResult.Failed(OpenClawFailure.NothingToResend), run { client.resendUncertain() })
    }

    @Test
    fun `a new question abandons an uncertain one without replaying it`() {
        pairedDevice()
        val sendCount = AtomicInteger()
        gateway.onChatSend = { ctx, req ->
            if (sendCount.incrementAndGet() == 1) ctx.ws.cancel() else gateway.answer(ctx, req, "Druga odpowiedź")
        }
        val client = client()
        assertEquals(OpenClawTurnResult.Uncertain, run { client.ask("Pierwsze") })
        assertEquals(OpenClawTurnResult.Answer("Druga odpowiedź"), run { client.ask("Drugie") })
        assertEquals(OpenClawTurnResult.Failed(OpenClawFailure.NothingToResend), run { client.resendUncertain() })
        val keys = gateway.sends().map { it.params.getString("idempotencyKey") }
        assertEquals(2, keys.size)
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun `a network that never returns leaves the turn uncertain after bounded retries`() {
        pairedDevice()
        gateway.onChatSend = { ctx, _ -> ctx.ws.cancel() }
        gateway.upgrade = {
            val conn = gateway.connections.incrementAndGet()
            if (conn == 1) MockResponse().withWebSocketUpgrade(gateway.listener(conn)) else MockResponse().setResponseCode(503)
        }
        val result = run { client().ask("Hej") }
        assertEquals(OpenClawTurnResult.Uncertain, result)
        assertEquals(1, gateway.sends().size)
        assertTrue("retries are bounded", gateway.connections.get() <= 1 + FAST.recoveryAttempts)
    }

    // --- cancellation ----------------------------------------------------------------------------

    @Test
    fun `cancel aborts the run and late events from it cannot speak`() {
        pairedDevice()
        val streaming = CountDownLatch(1)
        val sends = AtomicInteger()
        gateway.onChatSend = { ctx, req ->
            val run = gateway.nextRun()
            ctx.ack(req, run)
            if (sends.incrementAndGet() == 1) {
                ctx.chat(run, 1, "delta", content = ChatContent(deltaText = "Za"))
            } else {
                // A stale final of the aborted first run arrives during the second turn.
                ctx.chat("run-1", 9, "final", content = ChatContent(text = "STARA ODPOWIEDŹ"))
                ctx.chat(run, 1, "final", content = ChatContent(text = "Świeża odpowiedź"))
            }
        }
        gateway.onAbort = { ctx, req ->
            ctx.ok(req, JSONObject().put("aborted", true).put("runIds", JSONArray(listOf("run-1"))))
            ctx.chat("run-1", 5, "final", content = ChatContent(text = "SPÓŹNIONA ODPOWIEDŹ"))
        }
        val client = client()
        val deltas = CopyOnWriteArrayList<String>()
        // Release the barrier when the client receives the delta, not when the server sends it:
        // the frame is delivered on a network thread, so cancelling earlier could race ahead of it.
        val first = scope.async {
            client.ask("Opowiedz długą historię") {
                deltas.add(it)
                streaming.countDown()
            }
        }
        assertTrue(streaming.await(10, TimeUnit.SECONDS))

        run { client.cancel() }

        assertEquals(OpenClawTurnResult.Aborted, run { first.await() })
        val abort = gateway.received.single { it.method == "chat.abort" }.params
        assertEquals("run-1", abort.getString("runId"))
        assertEquals(gateway.sends().single().params.getString("sessionKey"), abort.getString("sessionKey"))

        assertEquals(OpenClawTurnResult.Answer("Świeża odpowiedź"), run { client.ask("Nowe pytanie") })
        assertEquals(listOf("Za"), deltas.toList())
    }

    @Test
    fun `cancel before the ack still aborts the run once it is known`() {
        pairedDevice()
        val sendArrived = CountDownLatch(1)
        val ackGate = CompletableDeferred<Unit>()
        gateway.onChatSend = { ctx, req ->
            sendArrived.countDown()
            runBlocking { ackGate.await() }
            val run = gateway.nextRun()
            ctx.ack(req, run)
            ctx.chat(run, 1, "final", content = ChatContent(text = "NIE POWINNO PADAĆ"))
        }
        val client = client()
        val ask = scope.async { client.ask("Hej") }
        assertTrue(sendArrived.await(10, TimeUnit.SECONDS))

        run { client.cancel() }
        ackGate.complete(Unit)

        assertEquals(OpenClawTurnResult.Aborted, run { ask.await() })
        assertEquals("run-1", gateway.received.single { it.method == "chat.abort" }.params.getString("runId"))
    }

    @Test
    fun `cancel with nothing running is harmless`() {
        pairedDevice()
        run { client().cancel() }
        assertTrue(gateway.received.isEmpty())
    }

    // --- independence and secrets ----------------------------------------------------------------

    @Test
    fun `the transport is independent of every other assistant provider`() {
        val sourceDir = File("src/main/kotlin/com/bydmate/app/voice/openclaw")
        val files = sourceDir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue("expected the four OpenClaw sources in ${sourceDir.absolutePath}", files.size >= 4)
        val forbidden = listOf("com.bydmate.app.agent", "LlmConnections", "AgentOrchestrator", "com.bydmate.app.voice.online", "ActionDispatcher")
        // The action layer (issue 4) is the one deliberate user of the agent package's validated-action types; OpenClawAssistant.kt only names ActionRejection.MALFORMED for a broken block.
        val actionLayer = setOf("ActionLedger.kt", "AssistantActionHandler.kt", "OpenClawActionProtocol.kt", "PolishActionText.kt", "OpenClawAssistant.kt")
        for (file in files) {
            val text = file.readText()
            for (word in forbidden) {
                if (word == "com.bydmate.app.agent" && file.name in actionLayer) continue
                assertFalse("${file.name} must not reference $word", text.contains(word))
            }
            assertFalse("${file.name} must not log", text.contains("android.util.Log"))
        }
    }

    @Test
    fun `failures never carry server text or the token`() {
        pairedDevice("tok-SECRET-1")
        gateway.onChatSend = { ctx, req -> ctx.error(req, "INTERNAL", "X", message = "boom tok-SECRET-1") }
        val result = run { client().ask("Hej") }
        assertFalse(result.toString().contains("tok-SECRET-1"))
        assertFalse(result.toString().contains("boom"))
    }

    // --- Gateway payload limit (issue 4 transport size) ---------------------------------------

    /** Long, multibyte and JSON-escaped on purpose: its UTF-8 frame is far bigger than its character count. */
    private val awkwardText =
        ("Zażółć gęślą jaźń 😀 \"cytat\" \\ ukośnik / nowa\nlinia\ttab \u0001 ").repeat(40).trim()

    /** The UTF-8 size of the one `chat.send` frame the Gateway received for [text], with no limit advertised. */
    private fun measuredFrameBytes(actions: Boolean, text: String): Int {
        val before = gateway.sends().size
        val client = client(attachments = RecordingAttachments())
        val result = run { if (actions) client.askForActions(text) else client.ask(text) }
        assertTrue(result is OpenClawTurnResult.Answer || result is OpenClawTurnResult.ActionAnswer)
        val frame = gateway.sends().drop(before).single()
        assertTrue("bytes must differ from characters", frame.frameBytes > frame.raw.length)
        return frame.frameBytes
    }

    private fun advertise(limit: Long) {
        gateway.onConnect = { ctx, req -> ctx.helloWithLimit(req, limit) }
    }

    private fun forgetTraffic() = gateway.received.clear()

    private fun assertEdge(actions: Boolean) {
        pairedDevice()
        val size = measuredFrameBytes(actions, awkwardText)

        // Exactly the limit: the whole message is delivered, never shortened.
        forgetTraffic()
        advertise(size.toLong())
        val attachments = RecordingAttachments()
        val exact = client(attachments = attachments)
        val delivered = run { if (actions) exact.askForActions(awkwardText) else exact.ask(awkwardText) }
        assertTrue(delivered is OpenClawTurnResult.Answer || delivered is OpenClawTurnResult.ActionAnswer)
        val sent = gateway.sends().single()
        assertEquals(size, sent.frameBytes)
        val key = sent.params.getString("idempotencyKey")
        assertEquals(
            if (actions) OpenClawProtocol.turnMessage(awkwardText, key, attachments.text) else OpenClawProtocol.turnMessage(awkwardText, key),
            sent.params.getString("message"),
        )

        // One byte over: refused before the socket, with nothing to recover, resend or admit.
        forgetTraffic()
        advertise(size - 1L)
        val base = gateway.connections.get()
        val over = RecordingAttachments()
        val client = client(attachments = over)
        val result = run { if (actions) client.askForActions(awkwardText) else client.ask(awkwardText) }
        assertEquals(OpenClawTurnResult.Failed(OpenClawFailure.RequestTooLarge), result)
        assertEquals("only the handshake reached the Gateway", listOf("connect"), gateway.methods())
        assertTrue("never admitted", over.admitted.isEmpty())
        assertEquals(OpenClawLinkState.Ready, client.linkState.value)
        assertTrue(client.hasLiveConnection)
        assertEquals(OpenClawTurnResult.Failed(OpenClawFailure.NothingToResend), run { client.resendUncertain() })

        // The same socket still carries a small turn.
        assertTrue(run { client.ask("Hej") } is OpenClawTurnResult.Answer)
        assertEquals(1, gateway.sends().size)
        assertEquals("one socket throughout", 1, gateway.connections.get() - base)
    }

    @Test
    fun `an ordinary turn of exactly the advertised size is sent whole and one byte more is refused unsent`() = assertEdge(actions = false)

    @Test
    fun `an action turn counts its manifest and instructions and is refused unsent one byte over`() = assertEdge(actions = true)

    @Test
    fun `without an advertised policy a large turn is sent as before`() {
        pairedDevice()
        val big = "Długi tekst. ".repeat(8_000).trim()
        assertTrue(run { client().ask(big) } is OpenClawTurnResult.Answer)
        assertTrue(gateway.sends().single().frameBytes > 100_000)
    }

    @Test
    fun `a policy without maxPayload keeps the legacy unadvertised behaviour`() {
        pairedDevice()
        gateway.onConnect = { ctx, req ->
            ctx.helloOk(req) { it.put("policy", JSONObject().put("maxBufferedBytes", 7).put("tickIntervalMs", 15_000)) }
        }
        assertTrue(run { client().ask("Długi tekst. ".repeat(8_000).trim()) } is OpenClawTurnResult.Answer)
    }

    @Test
    fun `a provided but malformed policy is refused before the token is kept`() {
        val bad: List<Pair<String, (JSONObject) -> Unit>> = listOf(
            "policy is a string" to { h -> h.put("policy", "big") },
            "policy is null" to { h -> h.put("policy", JSONObject.NULL) },
            "policy is an array" to { h -> h.put("policy", JSONArray().put(1)) },
            "maxPayload is a string" to { h -> h.put("policy", JSONObject().put("maxPayload", "1048576")) },
            "maxPayload is a fraction" to { h -> h.put("policy", JSONObject().put("maxPayload", 1.5)) },
            "maxPayload is a boolean" to { h -> h.put("policy", JSONObject().put("maxPayload", true)) },
            "maxPayload is null" to { h -> h.put("policy", JSONObject().put("maxPayload", JSONObject.NULL)) },
            "maxPayload is zero" to { h -> h.put("policy", JSONObject().put("maxPayload", 0)) },
            "maxPayload is negative" to { h -> h.put("policy", JSONObject().put("maxPayload", -5)) },
        )
        for ((label, edit) in bad) {
            gateway.onConnect = { ctx, req -> ctx.helloOk(req, token = "tok-bad", edit = edit) }
            val state = run { client().connect() }
            assertEquals(label, OpenClawLinkState.Failed(OpenClawFailure.ProtocolMismatch), state)
            assertNull(label, identityStore().deviceToken())
        }
        assertTrue(gateway.sends().isEmpty())
    }

    @Test
    fun `a reconnect that advertises a smaller limit is enforced on the new connection`() {
        pairedDevice()
        val size = measuredFrameBytes(actions = false, text = awkwardText)
        forgetTraffic()
        val base = gateway.connections.get()
        val first = CompletableDeferred<FakeGateway.Ctx>()
        gateway.onConnect = { ctx, req ->
            if (ctx.conn == base + 1) first.complete(ctx)
            ctx.helloWithLimit(req, if (ctx.conn == base + 1) size.toLong() else size - 1L)
        }
        val client = client()
        assertTrue(run { client.ask(awkwardText) } is OpenClawTurnResult.Answer)

        run { first.await() }.ws.close(1000, null)
        run { withTimeout(3_000) { while (client.hasLiveConnection) kotlinx.coroutines.delay(5) } }

        assertEquals(OpenClawTurnResult.Failed(OpenClawFailure.RequestTooLarge), run { client.ask(awkwardText) })
        assertEquals("the oversize turn was not sent on the new connection", 1, gateway.sends().size)
        assertEquals(OpenClawLinkState.Ready, client.linkState.value)
    }

    @Test
    fun `an explicit resend that is now too large keeps the earlier uncertain turn`() {
        pairedDevice()
        val size = measuredFrameBytes(actions = false, text = awkwardText)
        forgetTraffic()
        val base = gateway.connections.get()
        val serverSides = CopyOnWriteArrayList<FakeGateway.Ctx>()
        var limitFor: (Int) -> Long = { size - 1L }
        gateway.onConnect = { ctx, req ->
            serverSides.add(ctx)
            ctx.helloWithLimit(req, if (ctx.conn == base + 1) size.toLong() else limitFor(ctx.conn))
        }
        val sendCount = AtomicInteger()
        gateway.onChatSend = { ctx, req ->
            if (sendCount.incrementAndGet() == 1) ctx.ws.cancel() else gateway.answer(ctx, req, "Teraz działa")
        }
        val client = client()

        assertEquals(OpenClawTurnResult.Uncertain, run { client.ask(awkwardText) })
        assertEquals("recovery reconnected to a Gateway with a smaller limit", base + 2, gateway.connections.get())
        val key = gateway.sends().single().params.getString("idempotencyKey")

        assertEquals(OpenClawTurnResult.ResendTooLarge, run { client.resendUncertain() })
        assertEquals(1, gateway.sends().size)
        assertEquals("still uncertain, so it can be tried again", OpenClawTurnResult.ResendTooLarge, run { client.resendUncertain() })
        assertEquals(1, gateway.sends().size)

        // A later connection with room: the same turn is repeated, once, under the same key.
        limitFor = { size.toLong() }
        serverSides.last().ws.close(1000, null)
        run { withTimeout(3_000) { while (client.hasLiveConnection) kotlinx.coroutines.delay(5) } }
        assertEquals(OpenClawTurnResult.Answer("Teraz działa"), run { client.resendUncertain() })
        assertEquals(2, gateway.sends().size)
        assertEquals(key, gateway.sends()[1].params.getString("idempotencyKey"))
        assertEquals(OpenClawTurnResult.Failed(OpenClawFailure.NothingToResend), run { client.resendUncertain() })
    }

    // --- action turns (issue 4, Step 7a) -----------------------------------------------------

    private class RecordingAttachments : OpenClawTurnAttachments {
        val text = "BYDMATE-MANIFEST v1 (110 ids)\n${OpenClawActionProtocol.ACTION_INSTRUCTION}"
        val offered = CopyOnWriteArrayList<String>()
        val admitted = CopyOnWriteArrayList<String>()
        override fun forTurn(turnKey: String): String { offered.add(turnKey); return text }
        override fun onAdmitted(turnKey: String) { admitted.add(turnKey) }
    }

    @Test
    fun `an action turn carries the attachments before the tag and a plain turn does not`() {
        pairedDevice()
        val attachments = RecordingAttachments()
        val client = client(attachments = attachments)

        assertTrue(run { client.askForActions("Zamknij okno") } is OpenClawTurnResult.ActionAnswer)
        assertTrue(run { client.ask("Jaka pogoda?") } is OpenClawTurnResult.Answer)

        val sends = gateway.sends()
        assertEquals(2, sends.size)
        val action = sends[0].params
        val actionMessage = action.getString("message")
        assertEquals(
            OpenClawProtocol.turnMessage("Zamknij okno", action.getString("idempotencyKey"), attachments.text),
            actionMessage,
        )
        assertTrue(actionMessage.contains("BYDMATE-MANIFEST v1"))
        assertTrue(actionMessage.contains(OpenClawActionProtocol.ACTION_INSTRUCTION))
        assertTrue(actionMessage.endsWith("(#${turnTag(actionMessage)})"))

        val plain = sends[1].params
        assertEquals(
            OpenClawProtocol.turnMessage("Jaka pogoda?", plain.getString("idempotencyKey")),
            plain.getString("message"),
        )
        assertFalse(plain.getString("message").contains("BYDMATE-MANIFEST"))
        assertEquals("only the action turn asked for attachments", listOf(action.getString("idempotencyKey")), attachments.offered.toList())
        assertEquals("only the action turn was admitted", listOf(action.getString("idempotencyKey")), attachments.admitted.toList())
    }

    @Test
    fun `an action turn with nothing to attach is still an action turn`() {
        pairedDevice()
        val client = client() // OpenClawTurnAttachments.None
        val result = run { client.askForActions("Hej") }
        val key = gateway.sends().single().params.getString("idempotencyKey")
        assertEquals(OpenClawTurnResult.ActionAnswer("Odpowiedź", recovered = false, turnKey = key), result)
        assertEquals(OpenClawProtocol.turnMessage("Hej", key), gateway.sends().single().params.getString("message"))
    }

    @Test
    fun `a live final of an action turn is not recovered and a plain ask stays an Answer`() {
        pairedDevice()
        gateway.onChatSend = { ctx, req -> gateway.answer(ctx, req, "Zamykam.") }
        val client = client()

        val live = run { client.askForActions("Zamknij okno") }
        val key = gateway.sends().single().params.getString("idempotencyKey")
        assertEquals(OpenClawTurnResult.ActionAnswer("Zamykam.", recovered = false, turnKey = key), live)

        assertEquals(OpenClawTurnResult.Answer("Zamykam."), run { client.ask("Jeszcze raz") })
    }

    @Test
    fun `a dropped socket then a history answer is a recovered action answer`() {
        pairedDevice()
        gateway.onChatSend = { ctx, req ->
            val run = gateway.nextRun()
            ctx.ack(req, run)
            ctx.chat(run, 1, "delta", content = ChatContent(deltaText = "Pa"))
            ctx.ws.cancel() // connection dies mid answer
        }
        gateway.onHistory = { ctx, req ->
            val tag = turnTag(gateway.sends().single().params.getString("message"))
            ctx.ok(req, ctx.history(listOf("user" to "Zamknij okno (#$tag)", "assistant" to "Zamykam okno.")))
        }

        val result = run { client().askForActions("Zamknij okno") }

        val key = gateway.sends().single().params.getString("idempotencyKey")
        assertEquals(OpenClawTurnResult.ActionAnswer("Zamykam okno.", recovered = true, turnKey = key), result)
        assertEquals("zero replay", 1, gateway.sends().size)
    }

    @Test
    fun `a lost ack of an action turn recovers from history with one send only`() {
        pairedDevice()
        gateway.onChatSend = { ctx, _ -> ctx.ws.cancel() } // connection dies before any ack
        gateway.onHistory = { ctx, req ->
            val tag = turnTag(gateway.sends().single().params.getString("message"))
            ctx.ok(req, ctx.history(listOf("user" to "Zamknij okno (#$tag)", "assistant" to "Zamykam okno.")))
        }

        val result = run { client(attachments = RecordingAttachments()).askForActions("Zamknij okno") }

        val key = gateway.sends().single().params.getString("idempotencyKey")
        assertEquals(OpenClawTurnResult.ActionAnswer("Zamykam okno.", recovered = true, turnKey = key), result)
        assertEquals("zero duplicate send", 1, gateway.sends().size)
        assertTrue(gateway.methods().contains("chat.history"))
    }

    @Test
    fun `onAdmitted runs once and only after chat send is acknowledged`() {
        pairedDevice()
        val attachments = RecordingAttachments()
        val admittedWhenSent = CopyOnWriteArrayList<Int>()
        gateway.onChatSend = { ctx, req ->
            admittedWhenSent.add(attachments.admitted.size)
            gateway.answer(ctx, req, "Zamykam.")
        }

        run { client(attachments = attachments).askForActions("Zamknij okno") }

        assertEquals("not admitted while the send was still unacknowledged", listOf(0), admittedWhenSent.toList())
        assertEquals(1, attachments.offered.size)
        assertEquals(attachments.offered.toList(), attachments.admitted.toList())
    }

    @Test
    fun `a refused chat send is never admitted`() {
        pairedDevice()
        val attachments = RecordingAttachments()
        gateway.onChatSend = { ctx, req -> ctx.error(req, "FORBIDDEN", "MISSING_SCOPE") }

        val result = run { client(attachments = attachments).askForActions("Zamknij okno") }

        assertEquals(OpenClawTurnResult.Failed(OpenClawFailure.Forbidden), result)
        assertEquals(1, attachments.offered.size)
        assertTrue(attachments.admitted.isEmpty())
    }

    @Test
    fun `resending an uncertain action turn reuses its flags message and key and admits it once`() {
        pairedDevice()
        val attachments = RecordingAttachments()
        val sendCount = AtomicInteger()
        gateway.onChatSend = { ctx, req ->
            if (sendCount.incrementAndGet() == 1) ctx.ws.cancel() else gateway.answer(ctx, req, "Teraz działa")
        }
        val client = client(attachments = attachments)

        assertEquals(OpenClawTurnResult.Uncertain, run { client.askForActions("Zamknij okno") })
        assertEquals("automatic recovery never replays", 1, gateway.sends().size)
        assertTrue("an unacknowledged send is not admitted", attachments.admitted.isEmpty())

        val resent = run { client.resendUncertain() }

        val sends = gateway.sends()
        assertEquals(2, sends.size)
        val key = sends[0].params.getString("idempotencyKey")
        assertEquals(key, sends[1].params.getString("idempotencyKey"))
        assertEquals(sends[0].params.getString("message"), sends[1].params.getString("message"))
        assertTrue(sends[1].params.getString("message").contains("BYDMATE-MANIFEST v1"))
        assertEquals("still an action turn after resend", OpenClawTurnResult.ActionAnswer("Teraz działa", recovered = false, turnKey = key), resent)
        assertEquals("attachments are built once per turn", listOf(key), attachments.offered.toList())
        assertEquals(listOf(key), attachments.admitted.toList())
    }

    @Test
    fun `a plain ask never touches the attachments`() {
        pairedDevice()
        val attachments = RecordingAttachments()

        assertTrue(run { client(attachments = attachments).ask("Hej") } is OpenClawTurnResult.Answer)

        assertTrue(attachments.offered.isEmpty())
        assertTrue(attachments.admitted.isEmpty())
    }
}

private val FAST = OpenClawTiming(
    challengeTimeoutMs = 3_000,
    helloTimeoutMs = 3_000,
    ackTimeoutMs = 3_000,
    turnTimeoutMs = 8_000,
    abortTimeoutMs = 2_000,
    recoveryAttempts = 3,
    recoveryDelayMs = 20,
    historyPolls = 6,
    historyPollMs = 20,
    pairTimeoutMs = 3_000,
    pairPollMs = 20,
)

private class ChatContent(val deltaText: String? = null, val text: String? = null, val errorKind: String? = null)
