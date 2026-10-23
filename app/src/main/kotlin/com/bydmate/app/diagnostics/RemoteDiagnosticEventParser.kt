package com.bydmate.app.diagnostics

import org.json.JSONArray
import org.json.JSONObject

/** Strict mirror of receiver protocol v1; optional values may be absent, never null. */
object RemoteDiagnosticEventParser {
    private val states = setOf("preparing", "listening", "processing", "running_command", "waiting_assistant", "speaking", "done", "refused", "failed", "stopped")
    private val reasons = setOf("none", "cancelled", "timeout", "not_ready", "permission", "unavailable", "transport", "http", "protocol", "storage", "rejected", "unknown")
    private val backends = setOf("local_asr", "local_tts", "openrouter", "openclaw")
    private val exceptions = setOf("none", "IOException", "SocketTimeoutException", "SSLException", "IllegalStateException", "CancellationException", "Other")
    private val enums = mapOf("state" to states, "stage" to states, "reason" to reasons, "backend" to backends,
        "exceptionClass" to exceptions, "operation" to setOf("load", "recognize", "synthesize", "chat", "prewarm"),
        "outcome" to setOf("success", "failure", "cancelled"), "phase" to setOf("connect", "request", "response", "decode"))
    private val templates = mapOf(
        "voice.lifecycle" to (setOf("state", "durationMs") to setOf("reason")),
        "voice.failure" to (setOf("stage", "reason", "exceptionClass") to setOf("durationMs")),
        "connection.request" to (setOf("backend", "operation", "durationMs", "outcome") to setOf("httpStatus")),
        "connection.failure" to (setOf("backend", "phase", "reason", "exceptionClass", "retryable") to setOf("httpStatus")))

    fun parse(line: String): RemoteDiagnosticEvent? = runCatching {
        require(line.toByteArray().size <= 2048)
        val value = strictDiagnosticObject(line)
        checkEvent(value)
        RemoteDiagnosticEvent(value.toString())
    }.getOrNull()

    fun validBatch(bytes: ByteArray): Boolean = runCatching {
        require(bytes.size <= MAX_BATCH_BYTES)
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray().contentEquals(bytes))
        val batch = strictDiagnosticObject(text)
        exact(batch, setOf("schemaVersion", "batchId", "createdAtMs", "app", "environment", "logcat", "events", "counters"))
        integer(batch.get("schemaVersion"), 1, 1)
        require(uuid.matches(batch.getString("batchId")))
        integer(batch.get("createdAtMs"))
        checkApp(batch.getJSONObject("app"))
        checkEnvironment(batch.getJSONObject("environment"))
        val logs = batch.getJSONObject("logcat")
        exact(logs, setOf("entries", "droppedUnrecognisedLines", "truncatedLogEvents"))
        checkEvents(logs.getJSONArray("entries"))
        integer(logs.get("droppedUnrecognisedLines")); integer(logs.get("truncatedLogEvents"))
        checkEvents(batch.getJSONArray("events"))
        val counters = batch.getJSONObject("counters")
        exact(counters, setOf("captureDropped", "queueDropped", "storageFailures"))
        counters.keys().forEach { integer(counters.get(it)) }
    }.isSuccess

    private fun checkApp(app: JSONObject) {
        exact(app, setOf("version", "versionCode", "buildType", "installIdHash"))
        require(Regex("[0-9.\\-]{1,32}").matches(app.getString("version")))
        integer(app.get("versionCode"), 1)
        require(app.get("buildType") in setOf("debug", "release"))
        require(Regex("[0-9a-f]{64}").matches(app.getString("installIdHash")))
    }

    private fun checkEnvironment(env: JSONObject) {
        exact(env, setOf("apiLevel", "manufacturer", "model", "capabilities"))
        integer(env.get("apiLevel"), 29, 100)
        require(env.get("manufacturer") in setOf("byd", "google", "other"))
        require(env.get("model") in setOf("head_unit", "emulator", "other"))
        val caps = env.getJSONObject("capabilities")
        exact(caps, setOf("microphone", "overlay", "notificationListener"))
        caps.keys().forEach { require(caps.get(it) is Boolean) }
    }

    private fun checkEvents(events: JSONArray) {
        for (i in 0 until events.length()) checkEvent(events.getJSONObject(i))
    }

    private fun checkEvent(value: JSONObject) {
        val type = value.getString("type")
        val template = requireNotNull(templates[type])
        exact(value, template.first + setOf("type", "atMs"), template.second)
        integer(value.get("atMs"))
        enums.forEach { (key, values) -> if (value.has(key)) require(value.get(key) in values) }
        if (value.has("durationMs")) integer(value.get("durationMs"), 0, if (type.startsWith("voice.")) 300_000 else 120_000)
        if (value.has("httpStatus")) integer(value.get("httpStatus"), 100, 599)
        if (value.has("retryable")) require(value.get("retryable") is Boolean)
    }

    internal fun exact(value: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val keys = value.keys().asSequence().toSet()
        require(keys.containsAll(required) && (keys - required - optional).isEmpty())
    }

    internal fun integer(value: Any, min: Long = 0, max: Long = MAX_SAFE_INTEGER) {
        require(value is Number)
        val decimal = value.toString().toBigDecimal()
        require(decimal.stripTrailingZeros().scale() <= 0 && decimal >= min.toBigDecimal() && decimal <= max.toBigDecimal())
    }

    internal val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}

/** org.json accepts non-JSON syntax; check JSON grammar before handing it the document. */
internal fun strictDiagnosticObject(document: String): JSONObject {
    DiagnosticJsonSyntax(document).check()
    return JSONObject(document)
}

private class DiagnosticJsonSyntax(private val text: String) {
    private var position = 0
    fun check() { value(0); whitespace(); require(position == text.length) }
    private fun whitespace() { while (position < text.length && text[position] in " \t\r\n") position++ }
    private fun take(character: Char): Boolean {
        whitespace()
        return if (position < text.length && text[position] == character) { position++; true } else false
    }
    private fun value(depth: Int) {
        require(depth <= 16)
        whitespace(); require(position < text.length)
        when (text[position]) {
            '{' -> objectValue(depth + 1)
            '[' -> arrayValue(depth + 1)
            '"' -> stringValue()
            else -> {
                val token = PRIMITIVE.find(text, position)
                require(token != null && token.range.first == position)
                position = token.range.last + 1
            }
        }
    }
    private fun objectValue(depth: Int) {
        require(take('{'))
        if (take('}')) return
        val keys = mutableSetOf<String>()
        do {
            whitespace()
            val start = position
            stringValue()
            val key = org.json.JSONTokener(text.substring(start, position)).nextValue() as String
            require(keys.add(key))
            require(take(':')); value(depth)
        } while (take(','))
        require(take('}'))
    }
    private fun arrayValue(depth: Int) {
        require(take('['))
        if (take(']')) return
        do { value(depth) } while (take(','))
        require(take(']'))
    }
    private fun stringValue() {
        require(position < text.length && text[position++] == '"')
        while (position < text.length) {
            val character = text[position++]
            if (character == '"') return
            require(character.code >= 32)
            if (character == '\\') escape()
        }
        error("invalid_schema")
    }
    private fun escape() {
        require(position < text.length)
        val character = text[position++]
        require(character in "\"\\/bfnrtu")
        if (character == 'u') {
            require(position + 4 <= text.length && text.substring(position, position + 4).all { it in "0123456789abcdefABCDEF" })
            position += 4
        }
    }
    companion object {
        private val PRIMITIVE = Regex("""true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")
    }
}
