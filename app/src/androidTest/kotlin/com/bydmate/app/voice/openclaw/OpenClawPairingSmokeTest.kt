package com.bydmate.app.voice.openclaw

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Feasibility gate for the Polish-voice OpenClaw assistant (plan step 2).
 *
 * Proves, against the real Gateway over private Tailscale HTTPS, from an Android device:
 * Ed25519 device signing, "pairing required" for a fresh identity, read/write-only scopes after
 * approval, denial of admin/pairing/approval methods, a streamed Polish chat turn, idempotent
 * resend, abort, reconnect recovery, and rejection after revocation.
 *
 * The test client is test-only and contained here; production transport lands in a later step.
 * Nothing secret is logged: the device token stays in memory, and the pairing request id and
 * device id (a public-key hash) are the only identifiers printed.
 *
 * Opt-in (a normal `connectedDebugAndroidTest` run skips it so it never touches the network):
 *   -Pandroid.testInstrumentationRunnerArguments.openclawSmoke=true
 * Optional arguments: openclawUrl, openclawSession, openclawApprovalTimeoutSec,
 * openclawRevokeTimeoutSec. Approval and revocation happen on the Gateway host while the test
 * waits; see docs/voice/openclaw-setup.md.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenClawPairingSmokeTest {

    // --- 1. Ed25519 on this device -------------------------------------------------------

    @Test
    fun t01_ed25519SigningWorksOnThisDevice() {
        log("device sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} abi=${Build.SUPPORTED_ABIS.first()}")

        // RFC 8032 section 7.1 test 1: known seed, empty message.
        val rfcSeed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val rfcIdentity = DeviceIdentity(rfcSeed)
        assertEquals(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            hexOf(rfcIdentity.publicKey),
        )
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            hexOf(rfcIdentity.signBytes(ByteArray(0))),
        )

        // Known answer for the Gateway's v3 payload. The expected signature was produced by
        // Node's crypto (the Gateway's own implementation) for the same seed and payload.
        val payload = buildPayloadV3(
            deviceId = rfcIdentity.deviceId,
            token = "",
            nonce = "NONCE-123",
            signedAt = 1737264000000L,
        )
        assertEquals(
            "v3|21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9|gateway-client|ui|operator|" +
                "operator.read,operator.write|1737264000000||NONCE-123|android|phone",
            payload,
        )
        assertEquals("21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9", rfcIdentity.deviceId)
        assertEquals(
            "F7NS7xmfmeBSmE0XowFvnul_U33ilE7Z5CRsS-7yWpeuYvcXC9ur0Ypkfdq9szV04FjhL2WyfalyCbmSfQpOBw",
            rfcIdentity.sign(payload),
        )

        // A fresh random key signs, verifies, and rejects a tampered payload.
        val fresh = DeviceIdentity(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val sig = fresh.signBytes(payload.toByteArray())
        assertTrue(verify(fresh.publicKey, payload.toByteArray(), sig))
        assertFalse(verify(fresh.publicKey, (payload + "x").toByteArray(), sig))

        // The private seed can be wrapped by a hardware-backed-when-available Keystore AES key
        // and still sign after unwrapping, which is the storage shape the plan allows when the
        // Keystore cannot hold an Ed25519 key.
        val unwrapped = wrapAndUnwrapWithKeystore(fresh.seed)
        assertEquals(hexOf(fresh.seed), hexOf(unwrapped))
        assertEquals(hexOf(sig), hexOf(DeviceIdentity(unwrapped).signBytes(payload.toByteArray())))

        log("keystore Ed25519 native support: ${keystoreSupportsEd25519()}")
    }

    // --- 2. Fresh identity must be approved ----------------------------------------------

    @Test
    fun t02_freshIdentityRequiresApprovalAndCannotChat() {
        val identity = DeviceIdentity(ByteArray(32).also { SecureRandom().nextBytes(it) })
        state.identity = identity

        val conn = Conn(url).open()
        val res = conn.connect(identity, deviceToken = null)
        assertFalse("an unpaired identity must not connect", res.ok())
        assertEquals("PAIRING_REQUIRED", res.errorDetailCode())
        val details = res.error().getJSONObject("details")
        assertEquals("operator", details.getString("requestedRole"))
        assertEquals(setOf("operator.read", "operator.write"), details.getJSONArray("requestedScopes").strings().toSet())
        state.requestId = details.getString("requestId")
        log("OPENCLAW_PAIRING_REQUEST requestId=${state.requestId} deviceId=${identity.deviceId}")
        conn.close()

        // A socket that skips connect cannot call chat.
        val raw = Conn(url).open()
        raw.awaitChallenge()
        val denied = runCatching { raw.req("chat.send", chatSendParams(session, "x", UUID.randomUUID().toString()), 10) }
        assertTrue("chat.send before connect must fail", denied.isFailure || !denied.getOrThrow().ok())
        raw.close()
    }

    // --- 3. After approval: exactly read + write ------------------------------------------

    @Test
    fun t03_approvedDeviceGetsOnlyReadWriteScopes() {
        val identity = requireNotNull(state.identity) { "t02 must run first" }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(arg("openclawApprovalTimeoutSec", "240").toLong())
        log("OPENCLAW_WAITING_FOR_APPROVAL deviceId=${identity.deviceId} (approve the pending request on the Gateway host)")
        var last: JSONObject? = null
        while (System.nanoTime() < deadline) {
            val conn = Conn(url).open()
            val res = conn.connect(identity, deviceToken = null)
            if (res.ok()) {
                val auth = res.payload().getJSONObject("auth")
                assertEquals("operator", auth.getString("role"))
                assertEquals(setOf("operator.read", "operator.write"), auth.getJSONArray("scopes").strings().toSet())
                val token = auth.optString("deviceToken")
                assertTrue("hello-ok must carry a device token", token.isNotEmpty())
                assertEquals(4, res.payload().getInt("protocol"))
                val methods = res.payload().getJSONObject("features").getJSONArray("methods").strings().toSet()
                assertTrue(methods.containsAll(listOf("chat.send", "chat.abort", "chat.history")))
                state.deviceToken = token
                conn.close()
                return
            }
            last = res
            val id = res.error().optJSONObject("details")?.optString("requestId")
            if (id != null && id != state.requestId) {
                log("OPENCLAW_PAIRING_REQUEST requestId=$id deviceId=${identity.deviceId} (previous request expired)")
                state.requestId = id
            }
            conn.close()
            Thread.sleep(3000)
        }
        fail("pairing was not approved in time; last response: ${last?.errorSummary()}")
    }

    // --- 4. Excess privilege is denied -----------------------------------------------------

    @Test
    fun t04_adminPairingAndApprovalMethodsAreDenied() {
        val conn = connectPaired()
        val calls = listOf(
            "device.pair.list" to JSONObject(),
            "device.pair.approve" to JSONObject().put("requestId", "00000000-0000-0000-0000-000000000000"),
            "device.token.revoke" to JSONObject().put("deviceId", "x").put("role", "operator"),
            "device.pair.setupCode" to JSONObject(),
            "node.pair.list" to JSONObject(),
            "exec.approval.list" to JSONObject(),
        )
        for ((method, params) in calls) {
            val res = conn.req(method, params, 30)
            assertFalse("$method must be denied for a read/write device", res.ok())
            assertEquals("$method: ${res.errorSummary()}", "FORBIDDEN", res.error().getString("code"))
            assertEquals("$method: ${res.errorSummary()}", "MISSING_SCOPE", res.errorDetailCode())
        }
        conn.close()
    }

    @Test
    fun t05_configReadExposesNoSecrets() {
        val conn = connectPaired()
        val res = conn.req("config.get", JSONObject(), 30)
        // Read scope can read config on this Gateway version; secrets must come back redacted.
        if (res.ok()) {
            val rawConfig = res.payload().optString("raw")
            val secretValues = Regex("\"(?:token|apiKey|password|secret)\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                .findAll(rawConfig).map { it.groupValues[1] }.toList()
            assertTrue(
                "unredacted secret-like values in config.get: ${secretValues.count { it != "__OPENCLAW_REDACTED__" }}",
                secretValues.all { it == "__OPENCLAW_REDACTED__" },
            )
            log("config.get readable with read scope; secret-like fields=${secretValues.size}, all redacted")
        } else {
            log("config.get denied: ${res.errorSummary()}")
        }
        conn.close()
    }

    @Test
    fun t06_documentedScopeLimitsAreRecorded() {
        // Informational: read/write operator scopes are not conversation-scoped. Record the
        // extent so a Gateway upgrade that changes it is visible. Counts only, no content.
        val conn = connectPaired()
        val list = conn.req("sessions.list", JSONObject().put("limit", 50), 30)
        if (list.ok()) {
            val sessions = list.payload().optJSONArray("sessions") ?: JSONArray()
            val agents = (0 until sessions.length()).map { sessions.getJSONObject(it).optString("key").split(":").getOrNull(1) }.toSet()
            log("sessions.list readable: ${sessions.length()} sessions across agents $agents")
        } else {
            log("sessions.list denied: ${list.errorSummary()}")
        }
        conn.close()
    }

    // --- 5. Chat ---------------------------------------------------------------------------

    @Test
    fun t07_chatStreamsPolishAnswer() {
        val conn = connectPaired()
        val nonce = nonce()
        val key = UUID.randomUUID().toString()
        val message = "Odpowiedz jednym krótkim zdaniem po polsku, bez używania narzędzi: powiedz cześć. (kod $nonce)"
        val ack = conn.req("chat.send", chatSendParams(session, message, key), 30)
        assertTrue("chat.send ack: ${ack.errorSummary()}", ack.ok())
        assertEquals("started", ack.payload().getString("status"))
        val runId = ack.payload().getString("runId")

        val terminal = conn.awaitTerminal(runId, 120)
        assertEquals("final", terminal.getString("state"))
        val events = conn.chatEvents(runId)
        assertTrue("events must be ordered", events.map { it.getInt("seq") }.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(session, terminal.getString("sessionKey"))
        val answer = textOf(terminal.opt("message"))
        assertTrue("assistant answer must not be empty", answer.isNotBlank())
        log("chat final states=${events.map { it.getString("state") }.distinct()} answerChars=${answer.length}")

        state.chat = ChatTurn(key, message, nonce, runId)
        conn.close()
    }

    @Test
    fun t08_sameIdempotencyKeyDoesNotDuplicateTurn() {
        val turn = requireNotNull(state.chat) { "t07 must run first" }
        val conn = connectPaired()
        val again = conn.req("chat.send", chatSendParams(session, turn.message, turn.key), 30)
        assertTrue("resend ack: ${again.errorSummary()}", again.ok())
        assertEquals("same key must map to the same run", turn.runId, again.payload().getString("runId"))
        assertTrue(again.payload().getString("status") in setOf("in_flight", "ok"))
        Thread.sleep(3000)
        assertEquals(1, userTurnsContaining(conn, turn.nonce))
        conn.close()
    }

    @Test
    fun t09_abortStopsARunningTurn() {
        val conn = connectPaired()
        val key = UUID.randomUUID().toString()
        val message = "Bez używania narzędzi napisz po polsku bardzo długie opowiadanie (co najmniej 800 słów) o podróży samochodem. (kod ${nonce()})"
        val ack = conn.req("chat.send", chatSendParams(session, message, key), 30)
        assertTrue(ack.errorSummary(), ack.ok())
        val runId = ack.payload().getString("runId")
        assertTrue("expected streamed output before abort", conn.awaitEvent(90) { it.isChat(runId, "delta") } != null)
        val abort = conn.req("chat.abort", JSONObject().put("sessionKey", session).put("runId", runId), 30)
        assertTrue(abort.errorSummary(), abort.ok())
        assertTrue(abort.payload().getBoolean("aborted"))
        assertEquals("aborted", conn.awaitTerminal(runId, 60).getString("state"))
        conn.close()
    }

    @Test
    fun t10_reconnectRecoversWithoutDuplicateSend() {
        val first = connectPaired()
        val key = UUID.randomUUID().toString()
        val nonce = nonce()
        val message = "Bez używania narzędzi odpowiedz po polsku w dwóch zdaniach: dlaczego niebo jest niebieskie? (kod $nonce)"
        val ack = first.req("chat.send", chatSendParams(session, message, key), 30)
        assertTrue(ack.errorSummary(), ack.ok())
        first.close() // connection lost after the ack, before the answer

        val second = connectPaired()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150)
        var active = true
        while (active && System.nanoTime() < deadline) {
            val history = second.req("chat.history", JSONObject().put("sessionKey", session).put("limit", 40), 30)
            assertTrue(history.errorSummary(), history.ok())
            active = history.payload().optJSONObject("sessionInfo")?.optBoolean("hasActiveRun", false) ?: false
            if (active) Thread.sleep(2000)
        }
        assertFalse("run did not finish after reconnect", active)

        // Uncertain delivery: the client resends with the same key and must not create a second turn.
        val resend = second.req("chat.send", chatSendParams(session, message, key), 30)
        assertTrue(resend.errorSummary(), resend.ok())
        Thread.sleep(3000)
        assertEquals(1, userTurnsContaining(second, nonce))
        val rows = history(second)
        val userIndex = rows.indexOfFirst { it.optString("role") == "user" && it.toString().contains(nonce) }
        assertTrue(
            "assistant answer must follow the user turn after recovery",
            rows.drop(userIndex + 1).any { it.optString("role") == "assistant" && textOf(it).isNotBlank() },
        )
        second.close()
    }

    // --- 6. Revocation ---------------------------------------------------------------------

    @Test
    fun t11_revokedDeviceTokenIsRejected() {
        val identity = requireNotNull(state.identity)
        val token = requireNotNull(state.deviceToken)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(arg("openclawRevokeTimeoutSec", "240").toLong())
        log("OPENCLAW_REVOKE_NOW deviceId=${identity.deviceId} (remove the device on the Gateway host)")
        var rejected: JSONObject? = null
        while (rejected == null && System.nanoTime() < deadline) {
            val conn = Conn(url).open()
            val res = conn.connect(identity, deviceToken = token)
            conn.close()
            if (!res.ok()) rejected = res else Thread.sleep(3000)
        }
        assertNotNull("device token still accepted after the revoke window", rejected)
        val code = rejected!!.errorDetailCode()
        assertTrue("unexpected rejection: ${rejected.errorSummary()}", code == "PAIRING_REQUIRED" || code.orEmpty().startsWith("AUTH_"))

        val fresh = Conn(url).open()
        val again = fresh.connect(identity, deviceToken = null)
        fresh.close()
        assertFalse("a revoked device must need pairing again", again.ok())
        assertEquals("PAIRING_REQUIRED", again.errorDetailCode())
        log("OPENCLAW_REJECT_PENDING requestId=${again.error().optJSONObject("details")?.optString("requestId")}")
    }

    // --- helpers ---------------------------------------------------------------------------

    private val url get() = arg("openclawUrl", DEFAULT_URL)
    private val session get() = arg("openclawSession", DEFAULT_SESSION)

    private fun connectPaired(): Conn {
        val identity = requireNotNull(state.identity) { "t02 must run first" }
        val token = requireNotNull(state.deviceToken) { "t03 must run first" }
        val conn = Conn(url).open()
        val res = conn.connect(identity, token)
        assertTrue("reconnect with stored device token: ${res.errorSummary()}", res.ok())
        res.payload().optJSONObject("auth")?.optString("deviceToken")?.takeIf { it.isNotEmpty() }?.let { state.deviceToken = it }
        return conn
    }

    private fun history(conn: Conn): List<JSONObject> {
        val res = conn.req("chat.history", JSONObject().put("sessionKey", session).put("limit", 40), 30)
        assertTrue(res.errorSummary(), res.ok())
        val rows = res.payload().optJSONArray("messages") ?: JSONArray()
        return (0 until rows.length()).map { rows.getJSONObject(it) }
    }

    private fun userTurnsContaining(conn: Conn, nonce: String): Int =
        history(conn).count { it.optString("role") == "user" && it.toString().contains(nonce) }

    private fun chatSendParams(sessionKey: String, message: String, key: String) =
        JSONObject().put("sessionKey", sessionKey).put("message", message).put("idempotencyKey", key)

    private fun nonce() = UUID.randomUUID().toString().take(8)

    private class ChatTurn(val key: String, val message: String, val nonce: String, val runId: String)

    private class SharedState {
        @Volatile var identity: DeviceIdentity? = null
        @Volatile var requestId: String? = null
        @Volatile var deviceToken: String? = null
        @Volatile var chat: ChatTurn? = null
    }

    /** Ed25519 identity. The seed would be Keystore-wrapped in production; here it is in memory. */
    private class DeviceIdentity(val seed: ByteArray) {
        private val privateKey = Ed25519PrivateKeyParameters(seed, 0)
        val publicKey: ByteArray = privateKey.generatePublicKey().encoded
        val deviceId: String = hexOf(MessageDigest.getInstance("SHA-256").digest(publicKey))

        fun signBytes(data: ByteArray): ByteArray =
            Ed25519Signer().apply { init(true, privateKey); update(data, 0, data.size) }.generateSignature()

        fun sign(payload: String): String = b64url(signBytes(payload.toByteArray(Charsets.UTF_8)))
    }

    /** Minimal Gateway WebSocket client: challenge, connect, request/response, events. */
    private class Conn(private val url: String) {
        private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
        private val challenge = CompletableFuture<JSONObject>()
        private val counter = AtomicInteger()
        val events = CopyOnWriteArrayList<JSONObject>()
        private lateinit var socket: WebSocket

        private val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JSONObject(text)
                when (frame.optString("type")) {
                    "res" -> pending.remove(frame.optString("id"))?.complete(frame)
                    "event" -> if (frame.optString("event") == "connect.challenge") {
                        challenge.complete(frame.getJSONObject("payload"))
                    } else {
                        events.add(frame)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail(IOException("closed $code $reason"))

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail(t)

            private fun fail(t: Throwable) {
                challenge.completeExceptionally(t)
                pending.values.forEach { it.completeExceptionally(t) }
                pending.clear()
            }
        }

        fun open(): Conn {
            socket = HTTP.newWebSocket(Request.Builder().url(url).build(), listener)
            return this
        }

        fun close() {
            socket.close(1000, null)
        }

        fun awaitChallenge(): JSONObject = challenge.get(15, TimeUnit.SECONDS)

        /** Sends `connect`; returns the response frame (ok or error), or a synthetic error if closed. */
        fun connect(identity: DeviceIdentity, deviceToken: String?): JSONObject {
            val ch = awaitChallenge()
            val nonce = ch.getString("nonce")
            val signedAt = ch.getLong("ts")
            val payload = buildPayloadV3(identity.deviceId, deviceToken.orEmpty(), nonce, signedAt)
            val params = JSONObject()
                .put("minProtocol", 4).put("maxProtocol", 4)
                .put(
                    "client",
                    JSONObject().put("id", CLIENT_ID).put("version", "bydmate-smoke-0").put("platform", PLATFORM)
                        .put("deviceFamily", DEVICE_FAMILY).put("mode", CLIENT_MODE),
                )
                .put("role", ROLE).put("scopes", JSONArray(SCOPES))
                .put("caps", JSONArray()).put("commands", JSONArray()).put("permissions", JSONObject())
                .put(
                    "device",
                    JSONObject().put("id", identity.deviceId).put("publicKey", b64url(identity.publicKey))
                        .put("signature", identity.sign(payload)).put("signedAt", signedAt).put("nonce", nonce),
                )
            if (deviceToken != null) params.put("auth", JSONObject().put("deviceToken", deviceToken))
            return req("connect", params, 30)
        }

        fun req(method: String, params: JSONObject, timeoutSec: Long): JSONObject {
            val id = counter.incrementAndGet().toString()
            val future = CompletableFuture<JSONObject>()
            pending[id] = future
            val sent = socket.send(JSONObject().put("type", "req").put("id", id).put("method", method).put("params", params).toString())
            if (!sent) return syntheticError("send failed")
            return try {
                future.get(timeoutSec, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                syntheticError(e.cause?.message ?: "closed")
            } catch (e: TimeoutException) {
                throw AssertionError("timeout waiting for $method", e)
            }
        }

        fun awaitEvent(timeoutSec: Long, predicate: (JSONObject) -> Boolean): JSONObject? {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
            while (System.nanoTime() < deadline) {
                events.firstOrNull(predicate)?.let { return it }
                Thread.sleep(50)
            }
            return null
        }

        fun awaitTerminal(runId: String, timeoutSec: Long): JSONObject {
            val frame = awaitEvent(timeoutSec) { f -> TERMINAL.any { f.isChat(runId, it) } }
            return requireNotNull(frame) { "no terminal chat event for run $runId" }.getJSONObject("payload")
        }

        fun chatEvents(runId: String): List<JSONObject> =
            events.filter { it.optString("event") == "chat" && it.getJSONObject("payload").optString("runId") == runId }
                .map { it.getJSONObject("payload") }

        private fun syntheticError(message: String) =
            JSONObject().put("ok", false).put("error", JSONObject().put("code", "CLIENT_CLOSED").put("message", message))
    }

    companion object {
        private const val TAG = "OpenClawSmoke"
        private const val DEFAULT_URL = "wss://bobs-mac-mini-openclaw.tail3549c7.ts.net/"
        private const val DEFAULT_SESSION = "agent:tomek:bydmate-car-test"

        // The Gateway accepts only a closed registry of client ids. `gateway-client` in `ui`
        // mode is the generic one that took the normal pairing path in the 2026-10-20 probe.
        private const val CLIENT_ID = "gateway-client"
        private const val CLIENT_MODE = "ui"
        private const val PLATFORM = "android"
        private const val DEVICE_FAMILY = "phone"
        private const val ROLE = "operator"
        private val SCOPES = listOf("operator.read", "operator.write")
        private val TERMINAL = listOf("final", "aborted", "error")

        private val HTTP: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .pingInterval(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        private val state = SharedState()
        private const val WRAP_ALIAS = "bydmate-openclaw-smoke-wrap"

        @BeforeClass
        @JvmStatic
        fun requireOptIn() {
            assumeTrue(
                "OpenClaw smoke test is opt-in: pass -Pandroid.testInstrumentationRunnerArguments.openclawSmoke=true",
                arg("openclawSmoke", "false") == "true",
            )
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            state.deviceToken = null
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(WRAP_ALIAS) }
        }

        private fun arg(name: String, default: String): String =
            InstrumentationRegistry.getArguments().getString(name) ?: default

        private fun log(message: String) {
            Log.i(TAG, message)
        }

        private fun buildPayloadV3(deviceId: String, token: String, nonce: String, signedAt: Long): String =
            listOf(
                "v3", deviceId, CLIENT_ID, CLIENT_MODE, ROLE, SCOPES.joinToString(","),
                signedAt.toString(), token, nonce, PLATFORM, DEVICE_FAMILY,
            ).joinToString("|")

        private fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(data, 0, data.size)
            }.verifySignature(signature)

        private fun wrapAndUnwrapWithKeystore(secret: ByteArray): ByteArray {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(WRAP_ALIAS)) {
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            WRAP_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build(),
                    )
                }.generateKey()
            }
            val key = store.getKey(WRAP_ALIAS, null)
            val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val ciphertext = encrypt.doFinal(secret)
            check(!ciphertext.copyOf(secret.size).contentEquals(secret)) { "seed was not encrypted" }
            val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypt.iv)) }
            return decrypt.doFinal(ciphertext)
        }

        private fun keystoreSupportsEd25519(): Boolean = try {
            KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
            true
        } catch (_: Exception) {
            false
        }

        private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        private fun b64url(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        private fun textOf(message: Any?): String = when (message) {
            is String -> message
            is JSONObject -> when (val content = message.opt("content")) {
                is String -> content
                is JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it) }
                    .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                else -> message.optString("text")
            }
            else -> ""
        }

        private fun JSONObject.ok(): Boolean = optBoolean("ok", false)

        private fun JSONObject.payload(): JSONObject = getJSONObject("payload")

        private fun JSONObject.error(): JSONObject = getJSONObject("error")

        private fun JSONObject.errorDetailCode(): String? = optJSONObject("error")?.optJSONObject("details")?.optString("code")

        private fun JSONObject.errorSummary(): String =
            optJSONObject("error")?.let { "${it.optString("code")}/${errorDetailCode()}: ${it.optString("message").take(120)}" } ?: "ok"

        private fun JSONObject.isChat(runId: String, state: String): Boolean =
            optString("event") == "chat" && optJSONObject("payload")?.let {
                it.optString("runId") == runId && it.optString("state") == state
            } == true

        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    }
}
