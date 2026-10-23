package com.bydmate.app.navdata

import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Bounded private writer. Binder callbacks enqueue copied records and return without file I/O. */
class NavCaptureStore(
    private val clockMs: () -> Long,
    private val sink: Sink,
    private val writer: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    interface Sink {
        fun append(line: String): Boolean
        fun delete(): Boolean
        fun reset(): Boolean = true
    }

    private var startedAt = 0L
    private var packageName: String? = null
    private var events = 0
    private var dropped = 0
    private var oversized = 0
    private var bytes = 0L
    private var reason: NavCaptureStopReason? = null
    private var error: NavCaptureError? = null
    private var sessionId = 0L
    private val queued = ArrayDeque<QueuedRecord>()

    @Synchronized
    fun start(packageName: String): Boolean {
        if (packageName !in NavCaptureBridge.supportedPackages) return false
        // Invalidate old queued work before reset; retain counters if truncation fails.
        stopLocked(NavCaptureStopReason.STOPPED)
        if (!runCatching { sink.reset() }.getOrDefault(false)) {
            error = NavCaptureError.RESET_FAILED
            return false
        }
        this.packageName = packageName
        sessionId++
        startedAt = clockMs()
        events = 0; dropped = 0; oversized = 0; bytes = 0; reason = null; error = null; queued.clear()
        return true
    }

    /** Direct deterministic seam for unit tests. Runtime callbacks call [enqueue]. */
    @Synchronized
    fun record(record: NavCaptureRecord) = recordLocked(record)

    fun enqueue(record: NavCaptureRecord) {
        synchronized(this) {
            if (packageName == null || record.packageName != packageName) return
            if (queued.size >= MAX_QUEUE_RECORDS) { dropped++; return }
            queued.addLast(QueuedRecord(sessionId, record))
        }
        writer.execute { drain() }
    }

    /** Test seam for deterministic queue saturation; it deliberately does not schedule a writer. */
    @Synchronized
    fun enqueueForTest(record: NavCaptureRecord) {
        if (queued.size >= MAX_QUEUE_RECORDS) { dropped++; return }
        queued.addLast(QueuedRecord(sessionId, record))
    }

    fun flushForTest() = drain()

    private fun drain() {
        while (true) {
            synchronized(this) {
                if (queued.isEmpty()) return
                drainOneLocked()
            }
        }
    }

    private fun drainOneLocked() {
        val queuedRecord = queued.removeFirst()
        if (queuedRecord.sessionId == sessionId) recordLocked(queuedRecord.record)
    }

    @Synchronized
    fun stop() {
        // Removal and append share the lock, so no dequeued write can outlive Stop.
        while (queued.isNotEmpty()) drainOneLocked()
        if (packageName != null) stopLocked(NavCaptureStopReason.STOPPED)
    }

    @Synchronized
    fun delete(): Boolean {
        stopLocked(NavCaptureStopReason.STOPPED)
        if (!runCatching { sink.delete() }.getOrDefault(false)) {
            error = NavCaptureError.DELETE_FAILED
            return false
        }
        events = 0; bytes = 0; dropped = 0; oversized = 0; error = null; reason = NavCaptureStopReason.DELETED
        return true
    }

    /** Keep start/delete/writes excluded until the complete stopped file is copied. */
    @Synchronized
    fun saveStopped(copy: () -> Boolean): Boolean {
        if (!snapshot().canSave) return false
        val saved = runCatching(copy).getOrDefault(false)
        error = if (saved) null else NavCaptureError.EXPORT_FAILED
        return saved
    }

    @Synchronized
    fun snapshot() = NavCaptureSnapshot(
        recording = packageName != null, packageName = packageName, eventCount = events,
        queuedRecords = queued.size, droppedRecords = dropped,
        remainingMs = if (packageName == null) 0 else (MAX_DURATION_MS - (clockMs() - startedAt)).coerceAtLeast(0),
        stopReason = reason, error = error, oversizedRecords = oversized,
    )

    private fun recordLocked(record: NavCaptureRecord) {
        if (packageName == null || record.packageName != packageName) return
        if (clockMs() - startedAt > MAX_DURATION_MS) { stopLocked(NavCaptureStopReason.TIME_LIMIT); return }
        val line = json(record)
        val lineBytes = line.toByteArray(Charsets.UTF_8).size
        if (lineBytes > MAX_RECORD_BYTES) { skipOversizedLocked(record, lineBytes); return }
        appendLocked(line, lineBytes)
    }

    /** One record is lost, not the session: count it and leave a small marker so the gap is visible. */
    private fun skipOversizedLocked(record: NavCaptureRecord, originalBytes: Int) {
        dropped++; oversized++
        val marker = runCatching {
            json(NavCaptureRecord(
                kind = "oversized_dropped", packageName = record.packageName, timestampMs = record.timestampMs,
                metadata = mapOf("originalKind" to record.kind.take(32), "originalBytes" to originalBytes.toString()),
            ))
        }.getOrNull() ?: return
        val markerBytes = marker.toByteArray(Charsets.UTF_8).size
        if (markerBytes > MAX_RECORD_BYTES) return
        appendLocked(marker, markerBytes)
    }

    private fun appendLocked(line: String, lineBytes: Int) {
        if (bytes + lineBytes + 1 > MAX_SESSION_BYTES) { stopLocked(NavCaptureStopReason.FILE_LIMIT); return }
        if (runCatching { sink.append("$line\n") }.getOrDefault(false)) {
            bytes += lineBytes + 1; events++
        } else {
            // An append may have left a partial JSONL line; do not offer it for export.
            error = NavCaptureError.WRITE_FAILED
            stopLocked(NavCaptureStopReason.STOPPED)
        }
    }

    private fun stopLocked(stopReason: NavCaptureStopReason) {
        packageName = null; sessionId++; queued.clear(); reason = stopReason
    }

    private data class QueuedRecord(val sessionId: Long, val record: NavCaptureRecord)

    private fun json(record: NavCaptureRecord): String = JSONObject().apply {
        put("timestampMs", record.timestampMs); put("kind", record.kind); put("package", record.packageName)
        put("id", record.id); put("channel", record.channelId); put("title", record.title); put("text", record.text)
        put("flags", record.flags); put("smallIcon", record.smallIcon); put("largeIcon", record.largeIcon)
        put("class", record.className); put("contentDescription", record.contentDescription); put("eventType", record.eventType)
        put("display", record.displayId); put("window", record.windowId); put("tree", record.tree)
        put("remoteViewsPresent", record.remoteViewsPresent)
        put("marker", record.marker)
        put("extras", JSONObject(record.extras)); put("metadata", JSONObject(record.metadata))
    }.toString()

    companion object {
        const val MAX_DURATION_MS = 20 * 60 * 1000L
        const val MAX_SESSION_BYTES = 10 * 1024 * 1024L
        const val MAX_QUEUE_RECORDS = 32
        const val MAX_RECORD_BYTES = 16 * 1024
    }
}
