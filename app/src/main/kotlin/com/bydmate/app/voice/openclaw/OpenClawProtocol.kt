package com.bydmate.app.voice.openclaw

import org.json.JSONArray
import com.bydmate.app.voice.openclaw.OpenClawProtocol.Frame
import com.bydmate.app.voice.openclaw.OpenClawProtocol.Hello
import com.bydmate.app.voice.openclaw.OpenClawProtocol.History
import com.bydmate.app.voice.openclaw.OpenClawProtocol.HistoryRow
import com.bydmate.app.voice.openclaw.OpenClawProtocol.ErrorInfo
import org.json.JSONObject
import java.util.Base64

/** Why an OpenClaw call failed. Carries a category only, never server text, so nothing sensitive leaks. */
enum class OpenClawFailure {
    NetworkUnavailable, TlsRejected, HostRejected, ChallengeFailed, ProtocolMismatch, ScopeMismatch,
    AuthRejected, Forbidden, ServerUnavailable, ServerError, Timeout, Busy, EmptyRequest, EmptyAnswer, NothingToResend,
    /** The complete frame is larger than the Gateway's advertised `maxPayload`. Known unsent: it never left the device. */
    RequestTooLarge,
}

/**
 * Wire format of the OpenClaw Gateway chat transport, frozen in docs/voice/openclaw-protocol-contract.md.
 * Pure functions only: no sockets, no Android, no logging.
 */
@Suppress("TooManyFunctions") // One wire-format facade keeps the frozen transport contract together.
object OpenClawProtocol {
    const val PROTOCOL_VERSION = 4

    // The Gateway accepts a closed list of client ids. `gateway-client` in `ui` mode took the normal
    // pairing path in the step 2 probe; impersonating an official app id is not acceptable.
    const val CLIENT_ID = "gateway-client"
    const val CLIENT_MODE = "ui"
    const val ROLE = "operator"
    private const val PLATFORM = "android"
    private const val DEVICE_FAMILY = "phone"

    /** Least scopes that chat needs. Anything wider is refused by the client. */
    val SCOPES: List<String> = listOf("operator.read", "operator.write")

    /** The only methods this client may send. Approvals, pairing and admin calls are never made. */
    val ALLOWED_METHODS: Set<String> = setOf("connect", "chat.send", "chat.abort", "chat.history")

    /** The existing assistant the owner chose (Bob). */
    const val DEFAULT_AGENT_ID = "tomek"

    const val HISTORY_LIMIT = 40

    const val REPLY_INSTRUCTION =
        "Odpowiedz krótko po polsku, zwykłym tekstem bez formatowania — odpowiedź zostanie przeczytana na głos."

    sealed interface Frame {
        data class Challenge(val nonce: String, val ts: Long) : Frame
        object InvalidChallenge : Frame

        /** The payload is left out of [toString]: a `hello-ok` payload carries the device token. */
        class Response(val id: String, val ok: Boolean, val payload: JSONObject?, val error: ErrorInfo?) : Frame {
            override fun toString() = "Response(id=$id, ok=$ok, error=$error)"
        }

        /** Server error text is never kept, only its kind. */
        data class Chat(
            val runId: String,
            val sessionKey: String?,
            val seq: Long?,
            val state: String,
            val deltaText: String?,
            val text: String?,
            val errorKind: String?,
        ) : Frame

        object Ignored : Frame
    }

    data class ErrorInfo(val code: String, val detailCode: String?, val requestId: String?, val retryAfterMs: Long?) {
        val isPairingRequired: Boolean get() = code == "NOT_PAIRED" || detailCode == "PAIRING_REQUIRED"
        val isAuthRejected: Boolean get() = code.startsWith("AUTH_") || detailCode?.startsWith("AUTH_") == true
    }

    /**
     * [maxPayloadBytes] is `hello-ok.policy.maxPayload`, the largest frame the Gateway accepts. It is null when the
     * Gateway advertised none (no `policy`, or a `policy` without `maxPayload`): the legacy behaviour, where the
     * client cannot check a size and the Gateway alone enforces its limit. No bound is assumed. A `policy` or
     * `maxPayload` that is provided but unusable makes the whole hello unusable ([parseHello] returns null),
     * so it can never silently mean "no limit".
     */
    class Hello(
        val protocol: Int,
        val role: String,
        val scopes: Set<String>,
        val deviceToken: String?,
        val maxPayloadBytes: Long? = null,
    ) {
        override fun toString() = "Hello(protocol=$protocol, role=$role, scopes=$scopes, maxPayloadBytes=$maxPayloadBytes)"
    }

    data class HistoryRow(val role: String, val text: String)
    data class History(val hasActiveRun: Boolean, val inFlightRunId: String?, val rows: List<HistoryRow>)

    /** `v3|deviceId|clientId|mode|role|scopes|signedAt|token|nonce|platform|deviceFamily`. */
    fun signingPayload(deviceId: String, deviceToken: String, nonce: String, signedAt: Long): String =
        listOf(
            "v3", deviceId, CLIENT_ID, CLIENT_MODE, ROLE, SCOPES.joinToString(","),
            signedAt.toString(), deviceToken, nonce, PLATFORM, DEVICE_FAMILY,
        ).joinToString("|")

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun connectParams(
        identity: OpenClawIdentity,
        challenge: Frame.Challenge,
        deviceToken: String?,
        clientVersion: String,
    ): JSONObject {
        val payload = signingPayload(identity.deviceId, deviceToken.orEmpty(), challenge.nonce, challenge.ts)
        val params = JSONObject()
            .put("minProtocol", PROTOCOL_VERSION)
            .put("maxProtocol", PROTOCOL_VERSION)
            .put(
                "client",
                JSONObject().put("id", CLIENT_ID).put("version", clientVersion).put("platform", PLATFORM)
                    .put("deviceFamily", DEVICE_FAMILY).put("mode", CLIENT_MODE),
            )
            .put("role", ROLE)
            .put("scopes", JSONArray(SCOPES))
            .put("caps", JSONArray())
            .put("commands", JSONArray())
            .put("permissions", JSONObject())
            .put(
                "device",
                JSONObject().put("id", identity.deviceId).put("publicKey", base64Url(identity.publicKey))
                    .put("signature", identity.sign(payload)).put("signedAt", challenge.ts).put("nonce", challenge.nonce),
            )
        if (deviceToken != null) params.put("auth", JSONObject().put("deviceToken", deviceToken))
        return params
    }

    fun requestFrame(id: String, method: String, params: JSONObject): String =
        JSONObject().put("type", "req").put("id", id).put("method", method).put("params", params).toString()

    /** Short marker of one turn, derived from its idempotency key. It lets recovery find the turn in history. */
    fun turnTag(idempotencyKey: String): String = idempotencyKey.replace("-", "").take(12)

    /** The new user text, the short reply instruction and the turn marker. Nothing else is ever sent. */
    fun turnMessage(userText: String, idempotencyKey: String): String =
        "$userText\n\n$REPLY_INSTRUCTION (#${turnTag(idempotencyKey)})"

    /** As above, with [attachments] (an action turn's manifest, instruction and report) between the instruction and the marker. */
    fun turnMessage(userText: String, idempotencyKey: String, attachments: String?): String {
        if (attachments.isNullOrBlank()) return turnMessage(userText, idempotencyKey)
        return "$userText\n\n$REPLY_INSTRUCTION\n$attachments (#${turnTag(idempotencyKey)})"
    }

    fun sessionKey(agentId: String, conversationId: String): String = "agent:$agentId:bydmate-car-$conversationId"

    fun parseFrame(text: String): Frame = OpenClawDecoder.parseFrame(text)
    fun parseHello(payload: JSONObject?): Hello? = OpenClawDecoder.parseHello(payload)
    fun parseHistory(payload: JSONObject?): History = OpenClawDecoder.parseHistory(payload)

}

internal object OpenClawChatRequests {
    fun chatSendParams(sessionKey: String, message: String, idempotencyKey: String): JSONObject =
        JSONObject().put("sessionKey", sessionKey).put("message", message).put("idempotencyKey", idempotencyKey)

    fun chatAbortParams(sessionKey: String, runId: String): JSONObject =
        JSONObject().put("sessionKey", sessionKey).put("runId", runId)

    fun chatHistoryParams(sessionKey: String): JSONObject =
        JSONObject().put("sessionKey", sessionKey).put("limit", OpenClawProtocol.HISTORY_LIMIT)
}

private object OpenClawDecoder {
    private const val ABSENT = -1L
    private const val INVALID = 0L

    fun parseFrame(text: String): Frame {
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return Frame.Ignored
        }
        return when (json.optString("type")) {
            "res" -> parseResponse(json)
            "event" -> parseEvent(json)
            else -> Frame.Ignored
        }
    }

    fun parseHello(payload: JSONObject?): Hello? {
        if (payload == null || payload.optString("type") != "hello-ok") return null
        val auth = payload.optJSONObject("auth") ?: return null
        val limit = maxPayload(payload)
        if (limit == INVALID) return null
        return Hello(
            protocol = payload.optInt("protocol", -1),
            role = auth.optString("role"),
            scopes = strings(auth.optJSONArray("scopes")).toSet(),
            deviceToken = auth.optString("deviceToken").takeIf { it.isNotEmpty() },
            maxPayloadBytes = limit.takeIf { it != ABSENT },
        )
    }

    /** Only `policy.maxPayload` is read: its bytes, [ABSENT] when not advertised, [INVALID] when provided but unusable. */
    private fun maxPayload(payload: JSONObject): Long {
        if (!payload.has("policy")) return ABSENT
        val policy = payload.opt("policy") as? JSONObject ?: return INVALID
        if (!policy.has("maxPayload")) return ABSENT
        val bytes = when (val value = policy.opt("maxPayload")) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            else -> INVALID
        }
        return if (bytes > 0) bytes else INVALID
    }

    fun parseHistory(payload: JSONObject?): History {
        val inFlight = payload?.optJSONObject("inFlightRun")?.optString("runId")?.takeIf { it.isNotEmpty() }
        val active = payload?.optJSONObject("sessionInfo")?.optBoolean("hasActiveRun", false) == true || inFlight != null
        val messages = payload?.optJSONArray("messages")
        val rows = (0 until (messages?.length() ?: 0)).mapNotNull { i ->
            messages?.optJSONObject(i)?.let { HistoryRow(it.optString("role"), textOf(it)) }
        }
        return History(active, inFlight, rows)
    }

    private fun parseResponse(json: JSONObject): Frame {
        val id = json.optString("id")
        if (id.isEmpty()) return Frame.Ignored
        val error = json.optJSONObject("error")?.let { e ->
            val details = e.optJSONObject("details")
            ErrorInfo(
                code = e.optString("code"),
                detailCode = details?.optString("code")?.takeIf { it.isNotEmpty() },
                requestId = details?.optString("requestId")?.takeIf { it.isNotEmpty() },
                retryAfterMs = details?.takeIf { it.has("retryAfterMs") }?.optLong("retryAfterMs"),
            )
        }
        return Frame.Response(id, json.optBoolean("ok", false), json.optJSONObject("payload"), error)
    }

    private fun parseEvent(json: JSONObject): Frame {
        val payload = json.optJSONObject("payload")
        return when (json.optString("event")) {
            "connect.challenge" -> {
                val nonce = payload?.optString("nonce").orEmpty()
                val timestamp = when (val value = payload?.opt("ts")) {
                    is Byte, is Short, is Int, is Long -> value.toLong()
                    else -> null
                }
                if (payload == null || nonce.isEmpty() || timestamp == null) Frame.InvalidChallenge
                else Frame.Challenge(nonce, timestamp)
            }
            "chat" -> {
                val runId = payload?.optString("runId").orEmpty()
                if (payload == null || runId.isEmpty()) return Frame.Ignored
                val state = payload.optString("state")
                Frame.Chat(
                    runId = runId,
                    sessionKey = payload.optString("sessionKey").takeIf { it.isNotEmpty() },
                    seq = if (payload.has("seq")) payload.optLong("seq") else null,
                    state = state,
                    deltaText = if (payload.has("deltaText")) payload.optString("deltaText") else null,
                    text = if (state == "final") textOf(payload.opt("message")) else null,
                    errorKind = payload.optString("errorKind").takeIf { it.isNotEmpty() },
                )
            }
            else -> Frame.Ignored
        }
    }

    private fun textOf(message: Any?): String {
        val obj = message as? JSONObject ?: return ""
        return when (val content = obj.opt("content")) {
            is String -> content
            is JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
            else -> ""
        }
    }

    private fun strings(array: JSONArray?): List<String> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it) }
}
