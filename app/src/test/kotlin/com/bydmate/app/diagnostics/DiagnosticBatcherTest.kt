package com.bydmate.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class DiagnosticBatcherTest {
    private fun event(time: Long = 1, state: String = "done"): RemoteDiagnosticEvent =
        RemoteDiagnosticEventParser.parse("""{"type":"voice.lifecycle","atMs":$time,"state":"$state","durationMs":0}""")!!

    @Test fun acceptedEventProducesBatch() {
        val batcher = DiagnosticBatcher(); batcher.add(event())
        assertTrue(RemoteDiagnosticEventParser.validBatch(batcher.flush()!!))
        assertNull(batcher.flush())
    }
    @Test fun fiveMinutesAndBackwardsClockRollOver() {
        var now = 1L
        val batcher = DiagnosticBatcher(clock = { now })
        batcher.add(event()); now = 300000; assertNull(batcher.tick())
        now++; assertNotNull(batcher.tick())
        batcher.add(event()); now = 0; assertNotNull(batcher.tick())
    }
    @Test fun lifecycleAndFailureBoundariesFlush() {
        val batcher = DiagnosticBatcher(); batcher.add(event()); assertNotNull(batcher.boundary())
        batcher.add(RemoteDiagnosticEventParser.parse("""{"type":"voice.failure","atMs":1,"stage":"failed","reason":"unknown","exceptionClass":"Other"}""")!!)
        assertNotNull(batcher.boundary())
    }
    @Test fun metadataAndIdAreFixedAtCreation() {
        var calls = 0
        val app = defaultDiagnosticMetadata().first
        val batcher = DiagnosticBatcher(metadata = { calls++; app to defaultDiagnosticMetadata().second }, ids = { "12345678-1234-4234-8234-123456789abc" })
        batcher.add(event()); app.put("version", "2")
        val body = JSONObject(batcher.flush()!!.toString(Charsets.UTF_8))
        assertEquals("0", body.getJSONObject("app").getString("version")); assertEquals(1, calls)
        assertEquals("12345678-1234-4234-8234-123456789abc", body.getString("batchId"))
    }
    @Test fun consecutiveSafeFactsCoalesce() {
        val batcher = DiagnosticBatcher(); batcher.add(event()); batcher.add(event(2))
        val body = JSONObject(batcher.flush()!!.toString(Charsets.UTF_8))
        assertEquals(1, body.getJSONObject("logcat").getJSONArray("entries").length())
    }
    @Test fun countersAreBoundedAndUnknownJournalDataNeverLeaves() {
        val batcher = DiagnosticBatcher()
        batcher.reject(); batcher.counters(-1, Long.MAX_VALUE, -3)
        batcher.closedJournal(listOf("voice  session-stop reason=cancel #1", "voice  heard d=SECRET #2", "voice  session-stop reason=cancel token=SECRET #3"), 1)
        val bytes = batcher.flush()!!
        assertFalse(bytes.toString(Charsets.UTF_8).contains("SECRET"))
        val body = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals(1, body.getJSONArray("events").length())
        assertEquals(MAX_SAFE_INTEGER, body.getJSONObject("counters").getLong("queueDropped"))
        assertEquals(1, body.getJSONObject("logcat").getInt("droppedUnrecognisedLines"))
    }
    @Test fun targetCommitsAt32KiBAndFailedStorageStaysBounded() {
        val committed = mutableListOf<ByteArray>()
        val batcher = DiagnosticBatcher(committed = { committed.add(it); true })
        repeat(2000) { batcher.add(event(it.toLong(), if (it % 2 == 0) "done" else "stopped")) }
        assertTrue(committed.isNotEmpty())
        assertTrue(committed.all { it.size in DiagnosticBatcher.TARGET_BYTES..MAX_BATCH_BYTES })
        var accept = false
        val blocked = DiagnosticBatcher(committed = { accept })
        repeat(2000) { blocked.add(event(it.toLong(), if (it % 2 == 0) "done" else "stopped")) }
        accept = true
        val bytes = blocked.flush()!!
        assertTrue(bytes.size <= MAX_BATCH_BYTES)
        val logs = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("logcat")
        assertTrue(logs.getInt("truncatedLogEvents") > 0)
        assertTrue(logs.getJSONArray("entries").getJSONObject(0).getLong("atMs") > 0)
    }
    @Test fun sameFactAtRepeatedBoundaryInOneIntervalDoesNotBurst() {
        val batcher = DiagnosticBatcher(clock = { 100 })
        batcher.add(event()); assertNotNull(batcher.boundary())
        batcher.add(event(2)); assertNull(batcher.boundary())
    }
}
