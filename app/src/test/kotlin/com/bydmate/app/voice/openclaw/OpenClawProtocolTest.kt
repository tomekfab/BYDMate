package com.bydmate.app.voice.openclaw

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frozen step 2 contract: docs/voice/openclaw-protocol-contract.md. All fixtures are sanitized. */
class OpenClawProtocolTest {

    // RFC 8032 test 1 seed; the expected values come from Node crypto (the Gateway's own code).
    private val rfcSeed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val rfcIdentity = OpenClawIdentity(rfcSeed)

    @Test
    fun `device id and public key derive from the seed`() {
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", hexOf(rfcIdentity.publicKey))
        assertEquals("21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9", rfcIdentity.deviceId)
    }

    @Test
    fun `v3 payload and signature match the known answer`() {
        val payload = OpenClawProtocol.signingPayload(
            deviceId = rfcIdentity.deviceId,
            deviceToken = "",
            nonce = "NONCE-123",
            signedAt = 1737264000000L,
        )
        assertEquals(
            "v3|21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9|gateway-client|ui|operator|" +
                "operator.read,operator.write|1737264000000||NONCE-123|android|phone",
            payload,
        )
        assertEquals(
            "F7NS7xmfmeBSmE0XowFvnul_U33ilE7Z5CRsS-7yWpeuYvcXC9ur0Ypkfdq9szV04FjhL2WyfalyCbmSfQpOBw",
            rfcIdentity.sign(payload),
        )
    }

    @Test
    fun `rfc 8032 empty message signature is exact`() {
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            hexOf(rfcIdentity.signBytes(ByteArray(0))),
        )
    }

    @Test
    fun `connect asks for exactly operator read write and an empty capability set`() {
        val challenge = OpenClawProtocol.Frame.Challenge("NONCE-9", 1737264000123L)
        val params = OpenClawProtocol.connectParams(rfcIdentity, challenge, deviceToken = null, clientVersion = "1.2.3")

        assertEquals(4, params.getInt("minProtocol"))
        assertEquals(4, params.getInt("maxProtocol"))
        assertEquals("operator", params.getString("role"))
        assertEquals(listOf("operator.read", "operator.write"), strings(params.getJSONArray("scopes")))
        assertEquals(0, params.getJSONArray("caps").length())
        assertEquals(0, params.getJSONArray("commands").length())
        assertEquals(0, params.getJSONObject("permissions").length())
        assertFalse("no auth block on first connect", params.has("auth"))
        val client = params.getJSONObject("client")
        assertEquals("gateway-client", client.getString("id"))
        assertEquals("ui", client.getString("mode"))
        assertEquals("android", client.getString("platform"))
        assertEquals("phone", client.getString("deviceFamily"))
        val device = params.getJSONObject("device")
        assertEquals(rfcIdentity.deviceId, device.getString("id"))
        assertEquals("NONCE-9", device.getString("nonce"))
        assertEquals(1737264000123L, device.getLong("signedAt"))
        assertEquals(
            rfcIdentity.sign(OpenClawProtocol.signingPayload(rfcIdentity.deviceId, "", "NONCE-9", 1737264000123L)),
            device.getString("signature"),
        )
    }

    @Test
    fun `connect with a stored token sends it and signs it into the payload`() {
        val challenge = OpenClawProtocol.Frame.Challenge("N2", 5L)
        val params = OpenClawProtocol.connectParams(rfcIdentity, challenge, deviceToken = "tok-abc", clientVersion = "1")

        assertEquals("tok-abc", params.getJSONObject("auth").getString("deviceToken"))
        assertEquals(
            rfcIdentity.sign(OpenClawProtocol.signingPayload(rfcIdentity.deviceId, "tok-abc", "N2", 5L)),
            params.getJSONObject("device").getString("signature"),
        )
    }

    @Test
    fun `only four methods may ever be sent`() {
        assertEquals(setOf("connect", "chat.send", "chat.abort", "chat.history"), OpenClawProtocol.ALLOWED_METHODS)
    }

    @Test
    fun `chat send carries only session key message and idempotency key`() {
        val params = OpenClawChatRequests.chatSendParams("agent:tomek:bydmate-car-ab12", "hej", "key-1")
        assertEquals(setOf("sessionKey", "message", "idempotencyKey"), params.keys().asSequence().toSet())
        assertEquals("agent:tomek:bydmate-car-ab12", params.getString("sessionKey"))
        assertEquals("hej", params.getString("message"))
        assertEquals("key-1", params.getString("idempotencyKey"))
    }

    @Test
    fun `turn message is the user text plus a short polish instruction and a turn tag`() {
        val key = "0b5f1c2e-9a4d-4e11-8f00-aabbccddeeff"
        val tag = OpenClawProtocol.turnTag(key)
        assertEquals("0b5f1c2e9a4d", tag)

        val message = OpenClawProtocol.turnMessage("Jaka jest pogoda w Krakowie?", key)
        assertTrue(message.startsWith("Jaka jest pogoda w Krakowie?"))
        assertTrue(message.contains(OpenClawProtocol.REPLY_INSTRUCTION))
        assertTrue(message.contains(tag))
        assertEquals(
            "Jaka jest pogoda w Krakowie?\n\n${OpenClawProtocol.REPLY_INSTRUCTION} (#$tag)",
            message,
        )
        assertTrue("the instruction stays short", OpenClawProtocol.REPLY_INSTRUCTION.length <= 160)
        assertTrue(OpenClawProtocol.REPLY_INSTRUCTION.contains("po polsku"))
    }

    @Test
    fun `an attachment goes between the reply instruction and the turn tag`() {
        val key = "0b5f1c2e-9a4d-4e11-8f00-aabbccddeeff"
        val tag = OpenClawProtocol.turnTag(key)
        val attachments = "MANIFEST line\nINSTRUCTION line"

        val message = OpenClawProtocol.turnMessage("Zamknij okno", key, attachments)

        assertEquals(
            "Zamknij okno\n\n${OpenClawProtocol.REPLY_INSTRUCTION}\n$attachments (#$tag)",
            message,
        )
        assertTrue(message.indexOf(OpenClawProtocol.REPLY_INSTRUCTION) < message.indexOf(attachments))
        assertTrue(message.indexOf(attachments) < message.indexOf("(#$tag)"))
        assertTrue(message.endsWith("(#$tag)"))
        assertEquals(tag, Regex("\\(#([0-9a-f]+)\\)").find(message)!!.groupValues[1])
    }

    @Test
    fun `no attachment leaves the turn message byte identical`() {
        val key = "0b5f1c2e-9a4d-4e11-8f00-aabbccddeeff"
        val plain = OpenClawProtocol.turnMessage("Hej", key)
        assertEquals(plain, OpenClawProtocol.turnMessage("Hej", key, null))
        assertEquals(plain, OpenClawProtocol.turnMessage("Hej", key, ""))
        assertEquals(plain, OpenClawProtocol.turnMessage("Hej", key, "  \n"))
        assertEquals("Hej\n\n${OpenClawProtocol.REPLY_INSTRUCTION} (#0b5f1c2e9a4d)", plain)
    }

    @Test
    fun `session key is agent plus a separate car conversation`() {
        assertEquals("agent:tomek:bydmate-car-ab12cd34", OpenClawProtocol.sessionKey("tomek", "ab12cd34"))
        assertEquals("tomek", OpenClawProtocol.DEFAULT_AGENT_ID)
    }

    @Test
    fun `parses a connect challenge`() {
        val frame = OpenClawProtocol.parseFrame(
            """{"type":"event","event":"connect.challenge","payload":{"nonce":"n1","ts":1792507457865,"capabilities":["model-catalog-snapshot"]}}""",
        )
        assertEquals(OpenClawProtocol.Frame.Challenge("n1", 1792507457865L), frame)
    }

    @Test
    fun `a challenge without a nonce is invalid`() {
        assertEquals(
            OpenClawProtocol.Frame.InvalidChallenge,
            OpenClawProtocol.parseFrame("""{"type":"event","event":"connect.challenge","payload":{"ts":1}}"""),
        )
        assertEquals(
            OpenClawProtocol.Frame.InvalidChallenge,
            OpenClawProtocol.parseFrame("""{"type":"event","event":"connect.challenge","payload":{"nonce":"","ts":1}}"""),
        )
    }

    @Test
    fun `a challenge with a malformed timestamp is invalid`() {
        for (timestamp in listOf("\"1792507457865\"", "\"not-a-timestamp\"", "null", "true", "1.5", "{}", "[]")) {
            assertEquals(
                timestamp,
                OpenClawProtocol.Frame.InvalidChallenge,
                OpenClawProtocol.parseFrame(
                    """{"type":"event","event":"connect.challenge","payload":{"nonce":"n1","ts":$timestamp}}""",
                ),
            )
        }
    }

    @Test
    fun `parses delta and final chat events and joins text blocks`() {
        val delta = OpenClawProtocol.parseFrame(
            """{"type":"event","event":"chat","payload":{"runId":"r1","sessionKey":"s","seq":17,"state":"delta","deltaText":"D"}}""",
        ) as OpenClawProtocol.Frame.Chat
        assertEquals("r1", delta.runId)
        assertEquals(17L, delta.seq)
        assertEquals("delta", delta.state)
        assertEquals("D", delta.deltaText)

        val final = OpenClawProtocol.parseFrame(
            """{"type":"event","event":"chat","payload":{"runId":"r1","sessionKey":"s","seq":36,"state":"final","stopReason":"stop",
               "message":{"role":"assistant","content":[{"type":"text","text":"Dziś jest "},{"type":"image","text":"x"},{"type":"text","text":"wtorek."}]}}}""",
        ) as OpenClawProtocol.Frame.Chat
        assertEquals("final", final.state)
        assertEquals("Dziś jest wtorek.", final.text)
    }

    @Test
    fun `parses aborted and error chat states without keeping server text`() {
        val error = OpenClawProtocol.parseFrame(
            """{"type":"event","event":"chat","payload":{"runId":"r1","seq":3,"state":"error","errorKind":"model_unavailable","errorMessage":"token tok-SECRET leaked"}}""",
        ) as OpenClawProtocol.Frame.Chat
        assertEquals("error", error.state)
        assertEquals("model_unavailable", error.errorKind)
        assertFalse(error.toString().contains("tok-SECRET"))
        val aborted = OpenClawProtocol.parseFrame(
            """{"type":"event","event":"chat","payload":{"runId":"r1","seq":4,"state":"aborted"}}""",
        ) as OpenClawProtocol.Frame.Chat
        assertEquals("aborted", aborted.state)
    }

    @Test
    fun `chat events without a run id and unknown frames are ignored`() {
        assertEquals(
            OpenClawProtocol.Frame.Ignored,
            OpenClawProtocol.parseFrame("""{"type":"event","event":"chat","payload":{"state":"delta","deltaText":"x"}}"""),
        )
        assertEquals(OpenClawProtocol.Frame.Ignored, OpenClawProtocol.parseFrame("""{"type":"event","event":"tick","payload":{}}"""))
        assertEquals(OpenClawProtocol.Frame.Ignored, OpenClawProtocol.parseFrame("not json"))
    }

    @Test
    fun `parses a pairing required error with its request id and no message text`() {
        val frame = OpenClawProtocol.parseFrame(
            """{"type":"res","id":"1","ok":false,"error":{"code":"NOT_PAIRED","message":"leak tok-SECRET",
               "details":{"code":"PAIRING_REQUIRED","reason":"not-paired","requestId":"req-7","deviceId":"d",
               "requestedRole":"operator","requestedScopes":["operator.read","operator.write"]}}}""",
        ) as OpenClawProtocol.Frame.Response
        assertFalse(frame.ok)
        val error = frame.error!!
        assertEquals("NOT_PAIRED", error.code)
        assertEquals("PAIRING_REQUIRED", error.detailCode)
        assertEquals("req-7", error.requestId)
        assertTrue(error.isPairingRequired)
        assertFalse(error.isAuthRejected)
        assertFalse(error.toString().contains("tok-SECRET"))
    }

    @Test
    fun `classifies auth scope and unavailable errors`() {
        fun err(code: String, details: String) = (OpenClawProtocol.parseFrame(
            """{"type":"res","id":"1","ok":false,"error":{"code":"$code","message":"m","details":$details}}""",
        ) as OpenClawProtocol.Frame.Response).error!!

        assertTrue(err("UNAUTHORIZED", """{"code":"AUTH_TOKEN_MISMATCH"}""").isAuthRejected)
        assertFalse(err("FORBIDDEN", """{"code":"MISSING_SCOPE"}""").isAuthRejected)
        assertEquals("MISSING_SCOPE", err("FORBIDDEN", """{"code":"MISSING_SCOPE"}""").detailCode)
        val unavailable = err("UNAVAILABLE", """{"reason":"startup-sidecars","retryAfterMs":2500}""")
        assertEquals(2500L, unavailable.retryAfterMs)
    }

    @Test
    fun `parses hello ok with the granted authority`() {
        val payload = JSONObject(
            """{"type":"hello-ok","protocol":4,"server":{"version":"2026.10.1"},
               "features":{"methods":["chat.send","chat.abort","chat.history"]},
               "auth":{"role":"operator","scopes":["operator.read","operator.write"],"deviceToken":"tok-1"}}""",
        )
        val hello = OpenClawProtocol.parseHello(payload)!!
        assertEquals(4, hello.protocol)
        assertEquals("operator", hello.role)
        assertEquals(setOf("operator.read", "operator.write"), hello.scopes)
        assertEquals("tok-1", hello.deviceToken)
        assertFalse(hello.toString().contains("tok-1"))
    }

    private fun parsedHelloWith(policy: String?): OpenClawProtocol.Hello? {
        val extra = policy?.let { ""","policy":$it""" }.orEmpty()
        return OpenClawProtocol.parseHello(
            JSONObject("""{"type":"hello-ok","protocol":4,"auth":{"role":"operator","scopes":["operator.read","operator.write"]}$extra}"""),
        )
    }

    private fun helloWith(policy: String?): OpenClawProtocol.Hello = parsedHelloWith(policy)!!

    @Test
    fun `hello keeps the advertised maxPayload and ignores the other policy fields`() {
        val hello = helloWith("""{"maxPayload":1048576,"maxBufferedBytes":4194304,"tickIntervalMs":15000}""")
        assertEquals(1_048_576L, hello.maxPayloadBytes)
        assertEquals(3_000_000_000L, helloWith("""{"maxPayload":3000000000}""").maxPayloadBytes)
        assertTrue(hello.toString().contains("1048576"))
    }

    @Test
    fun `no policy or no maxPayload is unadvertised and never a limit`() {
        assertNull(helloWith(null).maxPayloadBytes)
        assertNull(helloWith("{}").maxPayloadBytes)
        assertNull(helloWith("""{"maxBufferedBytes":10}""").maxPayloadBytes)
    }

    @Test
    fun `a provided but unusable policy makes the hello unusable and never means no limit`() {
        for (policy in listOf(
            "\"big\"", "null", "[1]", "7", "true",
            """{"maxPayload":"1048576"}""", """{"maxPayload":1.5}""", """{"maxPayload":1048576.0}""",
            """{"maxPayload":true}""", """{"maxPayload":null}""", """{"maxPayload":0}""", """{"maxPayload":-1}""",
            """{"maxPayload":{}}""", """{"maxPayload":[1]}""",
        )) {
            assertNull(policy, parsedHelloWith(policy))
        }
    }

    @Test
    fun `hello without auth or with another type is rejected`() {
        assertNull(OpenClawProtocol.parseHello(JSONObject("""{"type":"hello-ok","protocol":4}""")))
        assertNull(OpenClawProtocol.parseHello(JSONObject("""{"type":"nope","protocol":4,"auth":{"role":"operator","scopes":[]}}""")))
        assertNull(OpenClawProtocol.parseHello(null))
    }

    @Test
    fun `parses history rows active run flag and in flight run`() {
        val history = OpenClawProtocol.parseHistory(
            JSONObject(
                """{"sessionInfo":{"hasActiveRun":true},"inFlightRun":{"runId":"r9","text":"par"},"deltaCursor":4,
                   "messages":[
                     {"role":"user","content":"Pytanie (#abc)","__openclaw":{"id":"1"}},
                     {"role":"assistant","content":[{"type":"text","text":"Odp"},{"type":"text","text":"owiedź"}]}]}""",
            ),
        )
        assertTrue(history.hasActiveRun)
        assertEquals("r9", history.inFlightRunId)
        assertEquals(2, history.rows.size)
        assertEquals("user", history.rows[0].role)
        assertEquals("Pytanie (#abc)", history.rows[0].text)
        assertEquals("Odpowiedź", history.rows[1].text)
    }

    @Test
    fun `history without rows is empty and idle`() {
        val history = OpenClawProtocol.parseHistory(JSONObject("{}"))
        assertFalse(history.hasActiveRun)
        assertNull(history.inFlightRunId)
        assertTrue(history.rows.isEmpty())
    }

    @Test
    fun `request frames serialise with id method and params`() {
        val frame = JSONObject(OpenClawProtocol.requestFrame("7", "chat.abort", OpenClawChatRequests.chatAbortParams("s", "r")))
        assertEquals("req", frame.getString("type"))
        assertEquals("7", frame.getString("id"))
        assertEquals("chat.abort", frame.getString("method"))
        assertEquals("r", frame.getJSONObject("params").getString("runId"))
        assertNotNull(OpenClawChatRequests.chatHistoryParams("s").getInt("limit"))
        assertEquals(OpenClawProtocol.HISTORY_LIMIT, OpenClawChatRequests.chatHistoryParams("s").getInt("limit"))
    }

    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hexOf(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
