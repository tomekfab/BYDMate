package com.bydmate.app.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Only producer of batch bodies. Metadata is copied when the first event enters a batch. */
class DiagnosticBatcher internal constructor(
    private val metadata: () -> Pair<JSONObject, JSONObject> = { defaultDiagnosticMetadata() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val committed: (ByteArray) -> Boolean = { true },
) {
    private val entries = mutableListOf<RemoteDiagnosticEvent>()
    private val journal = mutableListOf<RemoteDiagnosticEvent>()
    private var fixed: Pair<JSONObject, JSONObject>? = null
    private var createdAt = 0L
    private var id = ""
    private var lastJournalId = 0L
    private var recentWindow = -1L
    private val recentFacts = linkedSetOf<String>()
    private var dropped = 0L
    private var unrecognised = 0L
    private var captureDropped = 0L
    private var queueDropped = 0L
    private var storageFailures = 0L

    @Synchronized fun add(event: RemoteDiagnosticEvent) {
        val window = bounded(clock()) / INTERVAL_MS
        if (window != recentWindow) { recentFacts.clear(); recentWindow = window }
        if (event.factKey() in recentFacts) return
        begin()
        // Consecutive identical typed facts within a batch coalesce; timestamps stay ordered.
        if (entries.lastOrNull()?.json()?.apply { remove("atMs") }?.toString() !=
            event.json().apply { remove("atMs") }.toString()) entries.add(event)
        if (encode().size >= TARGET_BYTES) flush()
    }
    @Synchronized fun reject() { unrecognised = bounded(unrecognised + 1) }
    @Synchronized fun counters(capture: Long, queue: Long, storage: Long) {
        captureDropped = bounded(capture); queueDropped = bounded(queue); storageFailures = bounded(storage)
    }
    @Synchronized fun tick(): ByteArray? = if (fixed != null && (clock() < createdAt || clock() - createdAt >= INTERVAL_MS)) flush() else null
    @Synchronized fun boundary(): ByteArray? = flush()

    /** Only the exact closed session-stop facts are mapped. No generic journal serialization. */
    @Synchronized fun closedJournal(lines: List<String>, atMs: Long = clock()) {
        for (match in lines.mapNotNull { CLOSED_STOP.matchEntire(it.removePrefixTime()) }) {
            val journalId = match.groupValues[2].toLongOrNull() ?: 0
            if (journalId <= lastJournalId) continue
            lastJournalId = journalId
            val reason = if (match.groupValues[1] == "cancel") "cancelled" else "none"
            val event = RemoteDiagnosticEventParser.parse(JSONObject().put("type", "voice.lifecycle")
                .put("atMs", bounded(atMs)).put("state", "stopped").put("durationMs", 0).put("reason", reason).toString())
            if (event != null) { begin(); if (!journal.contains(event)) journal.add(event) }
        }
    }

    @Synchronized fun flush(): ByteArray? {
        if (fixed == null && unrecognised == 0L && captureDropped == 0L) return null
        begin()
        var bytes = encode()
        while (bytes.size > MAX_BATCH_BYTES && (entries.isNotEmpty() || journal.isNotEmpty())) {
            val logTime = entries.firstOrNull()?.json()?.getLong("atMs") ?: Long.MAX_VALUE
            val journalTime = journal.firstOrNull()?.json()?.getLong("atMs") ?: Long.MAX_VALUE
            if (logTime <= journalTime && entries.isNotEmpty()) entries.removeAt(0) else journal.removeAt(0)
            dropped = bounded(dropped + 1)
            bytes = encode()
        }
        require(RemoteDiagnosticEventParser.validBatch(bytes))
        if (!committed(bytes)) { storageFailures = bounded(storageFailures + 1); return null }
        for (event in entries) {
            recentFacts.add(event.factKey())
            if (recentFacts.size > 1024) recentFacts.remove(recentFacts.first())
        }
        entries.clear(); journal.clear(); fixed = null
        dropped = 0; unrecognised = 0; captureDropped = 0
        return bytes
    }

    private fun String.removePrefixTime(): String = replace(Regex("""^[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3} """), "")

    private fun begin() {
        if (fixed != null) return
        val current = metadata()
        fixed = JSONObject(current.first.toString()) to JSONObject(current.second.toString())
        id = ids(); createdAt = bounded(clock())
    }
    private fun encode(): ByteArray {
        val values = requireNotNull(fixed)
        return JSONObject().put("schemaVersion", 1).put("batchId", id).put("createdAtMs", createdAt)
            .put("app", values.first).put("environment", values.second)
            .put("logcat", JSONObject().put("entries", JSONArray(entries.map { it.json() }))
                .put("droppedUnrecognisedLines", unrecognised).put("truncatedLogEvents", dropped))
            .put("events", JSONArray(journal.map { it.json() }))
            .put("counters", JSONObject().put("captureDropped", captureDropped).put("queueDropped", queueDropped)
                .put("storageFailures", storageFailures)).toString().toByteArray()
    }
    companion object {
        const val TARGET_BYTES = 32 * 1024
        const val INTERVAL_MS = 5 * 60 * 1000L
        private val CLOSED_STOP = Regex("""voice +session-stop reason=(cancel|stop) #([0-9]+)(?: by=#[0-9]+)?(?: \(x[0-9]+\))?""")
    }
}

internal fun defaultDiagnosticMetadata(): Pair<JSONObject, JSONObject> =
    JSONObject().put("version", "0").put("versionCode", 1).put("buildType", "debug").put("installIdHash", "0".repeat(64)) to
        JSONObject().put("apiLevel", 29).put("manufacturer", "other").put("model", "other")
            .put("capabilities", JSONObject().put("microphone", false).put("overlay", false).put("notificationListener", false))
