package com.bydmate.app.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class DiagnosticUploadQueueTest {
    private fun fixture(id: Int = 0, time: Long = 1): ByteArray {
        val vectors = JSONObject(javaClass.getResource("/diagnostics/receiver-vectors.json")!!.readText())
        return JSONObject(vectors.getString("acceptedBatchJson")).put("createdAtMs", time)
            .put("batchId", "12345678-1234-4234-8234-%012d".format(id)).toString().toByteArray()
    }
    private fun directory(block: (java.io.File) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("diagnostics").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    @Test fun committedBodySurvivesRestartUnchanged() = directory { dir ->
        val bytes = fixture(); val queue = DiagnosticUploadQueue(dir, { 1 })
        assertTrue(queue.commit(bytes)); bytes[0] = 0
        assertArrayEquals(fixture(), DiagnosticUploadQueue(dir, { 1 }).bodies().single())
        assertFalse(dir.listFiles()!!.any { it.extension == "tmp" })
    }
    @Test fun recoveryRemovesPartialCorruptAndMisnamedBodies() = directory { dir ->
        java.io.File(dir, "partial.json.tmp").writeText("private")
        java.io.File(dir, "bad.json").writeText("{}")
        java.io.File(dir, "wrong.json").writeBytes(fixture())
        val queue = DiagnosticUploadQueue(dir, { 1 }); assertTrue(queue.bodies().isEmpty())
        assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun immutableIdCannotBeReplaced() = directory { dir ->
        val queue = DiagnosticUploadQueue(dir, { 1 }); assertTrue(queue.commit(fixture()))
        assertFalse(queue.commit(fixture(time = 2))); assertArrayEquals(fixture(), queue.bodies().single())
    }
    @Test fun ttlIsMaximumAndLeasesProtectFromEviction() = directory { dir ->
        var now = 1L; val queue = DiagnosticUploadQueue(dir, { now })
        assertTrue(queue.commit(fixture())); val record = queue.lease()!!
        now = DiagnosticUploadQueue.TTL_MS + 1; assertEquals(1, queue.bodies().size)
        queue.release(record.batchId); assertTrue(queue.bodies().isEmpty())
    }
    @Test fun max96EvictsOldestNonLeasedAndRejectsWhenAllLeased() = directory { dir ->
        val queue = DiagnosticUploadQueue(dir, { 1 })
        repeat(96) { assertTrue(queue.commit(fixture(it))) }
        val first = queue.lease()!!
        assertTrue(queue.commit(fixture(96)))
        assertEquals(96, queue.bodies().size)
        assertTrue(queue.bodies().any { JSONObject(it.toString(Charsets.UTF_8)).getString("batchId") == first.batchId })
        repeat(95) { assertNotNull(queue.lease()) }
        assertFalse(queue.commit(fixture(97)))
        assertTrue(queue.bodies().sumOf { it.size } <= DiagnosticUploadQueue.MAX_BYTES)
        assertEquals(2L, queue.queueDropped)
    }
    @Test fun blockedAndTerminalPersistButImportResumesOnlyBlocked() = directory { dir ->
        val queue = DiagnosticUploadQueue(dir, { 1 }); queue.commit(fixture()); queue.commit(fixture(1))
        val first = queue.lease()!!; queue.retain(first.batchId, UploadOutcome.BLOCKED); queue.release(first.batchId)
        val second = queue.lease()!!; queue.retain(second.batchId, UploadOutcome.TERMINAL); queue.release(second.batchId)
        val restarted = DiagnosticUploadQueue(dir, { 1 }); assertNull(restarted.lease())
        restarted.resumeBlocked(); assertEquals(first.batchId, restarted.lease()!!.batchId)
    }
    @Test fun successIsPersistedBeforeBodyDeletionAndRecoveryCompletesDeletion() = directory { dir ->
        val queue = DiagnosticUploadQueue(dir, { 10 }); queue.commit(fixture()); val record = queue.lease()!!
        queue.acknowledge(record); assertEquals(record.digest, queue.lastSuccess()!!.getString("digest"))
        assertTrue(queue.bodies().isEmpty())
        // Recreate the precise crash window: success metadata reached disk but body still exists.
        java.io.File(dir, "${record.batchId}.json").writeBytes(record.bytes)
        assertTrue(DiagnosticUploadQueue(dir, { 10 }).bodies().isEmpty())
    }
    @Test fun failedRenameCannotExposeACommittedBody() = directory { dir ->
        val queue = DiagnosticUploadQueue(dir, { 1 })
        val id = JSONObject(fixture().toString(Charsets.UTF_8)).getString("batchId")
        java.io.File(dir, "$id.json.tmp").mkdir(); java.io.File(dir, "$id.json.tmp/child").writeText("hold")
        assertFalse(queue.commit(fixture())); assertEquals(1L, queue.storageFailures)
        assertFalse(java.io.File(dir, "$id.json").exists())
    }
}
