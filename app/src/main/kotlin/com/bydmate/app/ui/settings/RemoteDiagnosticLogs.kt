package com.bydmate.app.ui.settings

import com.bydmate.app.diagnostics.RemoteDiagnosticEventParser
import org.json.JSONArray
import org.json.JSONObject

/** One approved diagnostic event. Field values come from the validated receiver protocol enums and numbers. */
data class RemoteDiagnosticLogEvent(
    val type: String,
    val atMs: Long,
    val fields: Map<String, String>,
)

data class RemoteDiagnosticLogCounters(
    val captureDropped: Long,
    val queueDropped: Long,
    val storageFailures: Long,
)

/**
 * Credential-free view of one queued batch. App, environment and configuration data are not
 * projected, so they cannot reach the screen.
 */
data class RemoteDiagnosticLogBatch(
    val batchId: String,
    val createdAtMs: Long,
    val status: String,
    val events: List<RemoteDiagnosticLogEvent>,
    val logcatEntries: List<RemoteDiagnosticLogEvent>,
    val droppedUnrecognisedLines: Long,
    val truncatedLogEvents: Long,
    val counters: RemoteDiagnosticLogCounters,
) {
    val eventCount: Int get() = events.size + logcatEntries.size
}

/** Returns null for bytes the queue protocol would reject. */
fun projectRemoteDiagnosticLog(bytes: ByteArray, status: String): RemoteDiagnosticLogBatch? {
    if (!RemoteDiagnosticEventParser.validBatch(bytes)) return null
    return runCatching {
        val batch = JSONObject(bytes.toString(Charsets.UTF_8))
        val logcat = batch.getJSONObject("logcat")
        val counters = batch.getJSONObject("counters")
        RemoteDiagnosticLogBatch(
            batchId = batch.getString("batchId"),
            createdAtMs = batch.getLong("createdAtMs"),
            status = status,
            events = events(batch.getJSONArray("events")),
            logcatEntries = events(logcat.getJSONArray("entries")),
            droppedUnrecognisedLines = logcat.getLong("droppedUnrecognisedLines"),
            truncatedLogEvents = logcat.getLong("truncatedLogEvents"),
            counters = RemoteDiagnosticLogCounters(
                captureDropped = counters.getLong("captureDropped"),
                queueDropped = counters.getLong("queueDropped"),
                storageFailures = counters.getLong("storageFailures"),
            ),
        )
    }.getOrNull()
}

private fun events(array: JSONArray): List<RemoteDiagnosticLogEvent> = List(array.length()) { index ->
    val event = array.getJSONObject(index)
    RemoteDiagnosticLogEvent(
        type = event.getString("type"),
        atMs = event.getLong("atMs"),
        fields = event.keys().asSequence().filter { it != "type" && it != "atMs" }.sorted()
            .associateWith { event.get(it).toString() },
    )
}

fun newestFirst(logs: List<RemoteDiagnosticLogBatch>): List<RemoteDiagnosticLogBatch> =
    logs.sortedWith(compareByDescending<RemoteDiagnosticLogBatch> { it.createdAtMs }.thenByDescending { it.batchId })

/** Selection is only an id; resolving it against the latest list drops acknowledged or expired batches. */
fun resolveRemoteDiagnosticLog(logs: List<RemoteDiagnosticLogBatch>, id: String?): RemoteDiagnosticLogBatch? =
    id?.let { wanted -> logs.firstOrNull { it.batchId == wanted } }

/** Content keys let repeat polls reuse projections without assuming a particular JSON serialization. */
private class DiagnosticLogBody(private val bytes: ByteArray) {
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun equals(other: Any?): Boolean = other is DiagnosticLogBody && bytes.contentEquals(other.bytes)
}

/**
 * Parses queued bodies outside the queue monitor. A batch id always names identical bytes, so its
 * parsed body is reused on later polls; only the status is re-applied.
 */
internal class RemoteDiagnosticLogCache {
    private val parsed = HashMap<DiagnosticLogBody, RemoteDiagnosticLogBatch>()

    @Synchronized fun project(bodies: List<ByteArray>, states: Map<String, String>): List<RemoteDiagnosticLogBatch> {
        val kept = HashMap<DiagnosticLogBody, RemoteDiagnosticLogBatch>()
        val logs = bodies.mapNotNull { bytes ->
            val key = DiagnosticLogBody(bytes)
            val cached = parsed[key] ?: projectRemoteDiagnosticLog(bytes, "queued") ?: return@mapNotNull null
            val status = states[cached.batchId]?.let { if (it == "pending") "queued" else it } ?: return@mapNotNull null
            val current = if (cached.status == status) cached else cached.copy(status = status)
            kept[key] = current
            current
        }
        parsed.clear(); parsed.putAll(kept)
        return newestFirst(logs)
    }
}
