package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavCaptureStoreTest {
    private class FakeSink : NavCaptureStore.Sink {
        val lines = mutableListOf<String>()
        var deleted = false
        override fun append(line: String): Boolean = lines.add(line)
        override fun delete(): Boolean { deleted = true; lines.clear(); return true }
    }

    @Test
    fun `stop settles all queued records before export and late work cannot append`() {
        val sink = FakeSink()
        val store = NavCaptureStore(clockMs = { 100L }, sink = sink)
        store.start("com.waze")
        repeat(3) { store.enqueueForTest(NavCaptureRecord.notification("com.waze", "event-$it")) }
        assertFalse(store.snapshot().canSave)
        var copied = false
        assertFalse(store.saveStopped { copied = true; true })
        assertFalse(copied)

        store.stop()

        assertEquals(3, store.snapshot().eventCount)
        assertEquals(0, store.snapshot().queuedRecords)
        assertTrue(store.snapshot().canSave)
        assertTrue(store.saveStopped { assertEquals(3, sink.lines.size); true })
        store.enqueue(NavCaptureRecord.notification("com.waze", "too late"))
        store.flushForTest()
        assertEquals(3, sink.lines.size)
    }

    @Test
    fun `failed export is visible and can be retried`() {
        val store = NavCaptureStore(clockMs = { 100L }, sink = FakeSink())
        store.start("com.waze")
        store.record(NavCaptureRecord.notification("com.waze", "street"))
        store.stop()

        assertFalse(store.saveStopped { throw java.io.IOException("destination unavailable") })
        assertEquals(NavCaptureError.EXPORT_FAILED, store.snapshot().error)
        assertTrue(store.snapshot().canSave)
        assertTrue(store.saveStopped { true })
        assertEquals(null, store.snapshot().error)
    }

    @Test
    fun `recording is off until explicit start and a new store does not resume it`() {
        val sink = FakeSink()
        val store = NavCaptureStore(clockMs = { 100L }, sink = sink)

        store.record(NavCaptureRecord.notification("com.google.android.apps.maps", "street before start"))
        assertTrue(sink.lines.isEmpty())

        assertTrue(store.start("com.google.android.apps.maps"))
        store.record(NavCaptureRecord.notification("com.google.android.apps.maps", "street while active"))
        assertEquals(1, sink.lines.size)
        assertFalse(NavCaptureStore(clockMs = { 200L }, sink = sink).snapshot().recording)
    }

    @Test
    fun `only registry packages record and stop delete cancel queued work`() {
        val sink = FakeSink()
        val store = NavCaptureStore(clockMs = { 100L }, sink = sink)
        assertFalse(store.start("com.spotify.music"))
        assertTrue(store.start("com.waze"))

        store.record(NavCaptureRecord.notification("com.spotify.music", "ignore"))
        store.record(NavCaptureRecord.notification("com.waze", "keep"))
        store.stop()
        store.delete()

        assertTrue(sink.deleted)
        assertEquals(0, store.snapshot().queuedRecords)
        assertFalse(store.snapshot().recording)
    }

    @Test
    fun `queue record and session caps stop deterministically and count drops`() {
        var now = 0L
        val sink = FakeSink()
        val store = NavCaptureStore(clockMs = { now }, sink = sink)
        store.start("com.google.android.apps.maps")

        repeat(NavCaptureStore.MAX_QUEUE_RECORDS + 1) {
            store.enqueueForTest(NavCaptureRecord.notification("com.google.android.apps.maps", "event-$it"))
        }
        assertEquals(1, store.snapshot().droppedRecords)

        assertEquals(0, store.snapshot().oversizedRecords)

        val recordStore = NavCaptureStore(clockMs = { now }, sink = FakeSink())
        recordStore.start("com.google.android.apps.maps")
        recordStore.record(NavCaptureRecord.notification("com.google.android.apps.maps", "x".repeat(NavCaptureStore.MAX_RECORD_BYTES + 1)))
        assertNull(recordStore.snapshot().stopReason)
        assertTrue(recordStore.snapshot().recording)
        assertEquals(1, recordStore.snapshot().oversizedRecords)

        val timedStore = NavCaptureStore(clockMs = { now }, sink = FakeSink())
        timedStore.start("com.google.android.apps.maps")
        now += NavCaptureStore.MAX_DURATION_MS + 1
        timedStore.record(NavCaptureRecord.notification("com.google.android.apps.maps", "late"))
        assertEquals(NavCaptureStopReason.TIME_LIMIT, timedStore.snapshot().stopReason)
    }

    @Test
    fun `fixed marker is written as a bounded primitive field`() {
        val sink = FakeSink()
        val store = NavCaptureStore(clockMs = { 100L }, sink = sink)
        store.start("com.google.android.apps.maps")

        store.record(NavCaptureRecord(
            kind = "marker", packageName = "com.google.android.apps.maps", timestampMs = 100L,
            marker = NavCaptureMarker.TURN.wireName,
        ))

        assertTrue(sink.lines.single().contains("\"marker\":\"turn\""))
    }

    private class CountingSink : NavCaptureStore.Sink {
        var totalBytes = 0L
        var lines = 0
        override fun append(line: String): Boolean {
            totalBytes += line.toByteArray(Charsets.UTF_8).size
            lines++
            return true
        }
        override fun delete(): Boolean = true
    }

    private fun oversizedPolish() = NavCaptureRecord.notification("com.waze", "\u0142".repeat(9_000))

    private fun assertOversizedSkippedAndNextPersisted(store: NavCaptureStore, sink: FakeSink) {
        val snapshot = store.snapshot()
        assertNull(snapshot.stopReason)
        assertTrue(snapshot.recording)
        assertEquals(1, snapshot.oversizedRecords)
        assertEquals(1, snapshot.droppedRecords)
        assertEquals(2, sink.lines.size)
        assertTrue(sink.lines[0].contains("\"kind\":\"oversized_dropped\""))
        assertTrue(sink.lines[1].contains("next valid street"))
        assertEquals(2, snapshot.eventCount)
    }

    @Test
    fun `oversized record is skipped, counted, and later records persist`() {
        val sink = FakeSink()
        val store = NavCaptureStore(clockMs = { 100L }, sink = sink)
        store.start("com.waze")

        store.record(oversizedPolish())
        store.record(NavCaptureRecord.notification("com.waze", "next valid street"))

        assertOversizedSkippedAndNextPersisted(store, sink)
        assertTrue(sink.lines[0].contains("\"originalKind\":\"notification\""))
        assertTrue(sink.lines[0].contains("\"originalBytes\":\""))
    }

    @Test
    fun `oversized record from the queue path persists the next record`() {
        val sink = FakeSink()
        val store = NavCaptureStore(clockMs = { 100L }, sink = sink)
        store.start("com.waze")

        store.enqueueForTest(oversizedPolish())
        store.enqueueForTest(NavCaptureRecord.notification("com.waze", "next valid street"))
        store.flushForTest()

        assertOversizedSkippedAndNextPersisted(store, sink)
    }

    @Test
    fun `escape-expanded record over the byte cap is skipped even when under the char cap`() {
        val sink = FakeSink()
        val store = NavCaptureStore(clockMs = { 100L }, sink = sink)
        store.start("com.waze")

        store.record(NavCaptureRecord.notification("com.waze", "\n".repeat(9_000)))
        store.record(NavCaptureRecord.notification("com.waze", "next valid street"))

        assertOversizedSkippedAndNextPersisted(store, sink)
    }

    @Test
    fun `oversized marker respects the session byte cap`() {
        val sink = CountingSink()
        val store = NavCaptureStore(clockMs = { 100L }, sink = sink)
        store.start("com.waze")
        assertNull(store.snapshot().stopReason)

        store.record(NavCaptureRecord.notification("com.waze", ""))
        val emptyLine = sink.totalBytes
        val target = NavCaptureStore.MAX_SESSION_BYTES - 50
        val chunk = 15_000
        while (sink.totalBytes < target) {
            val remaining = target - sink.totalBytes
            val textLength = when {
                remaining <= chunk + emptyLine -> remaining - emptyLine
                remaining < chunk + 2 * emptyLine -> remaining - 2 * emptyLine
                else -> chunk.toLong()
            }
            store.record(NavCaptureRecord.notification("com.waze", "x".repeat(textLength.toInt())))
        }
        assertEquals(target, sink.totalBytes)
        assertNull(store.snapshot().stopReason)
        assertTrue(store.snapshot().recording)
        val linesBefore = sink.lines

        store.record(oversizedPolish())

        assertEquals(NavCaptureStopReason.FILE_LIMIT, store.snapshot().stopReason)
        assertFalse(store.snapshot().recording)
        assertEquals(1, store.snapshot().oversizedRecords)
        assertEquals(linesBefore, sink.lines)
        assertEquals(target, sink.totalBytes)
    }

    @Test
    fun `start and delete reset the oversized counter`() {
        val store = NavCaptureStore(clockMs = { 100L }, sink = FakeSink())
        store.start("com.waze")
        store.record(oversizedPolish())
        assertEquals(1, store.snapshot().oversizedRecords)

        store.start("com.waze")
        assertEquals(0, store.snapshot().oversizedRecords)

        store.record(oversizedPolish())
        store.delete()
        assertEquals(0, store.snapshot().oversizedRecords)
    }
}
