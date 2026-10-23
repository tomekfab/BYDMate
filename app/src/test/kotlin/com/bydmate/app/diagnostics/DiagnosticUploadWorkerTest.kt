package com.bydmate.app.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DiagnosticUploadWorkerTest {
    @Test fun retryIsTheOnlyRetryOutcome() {
        assertFalse(uploadWorkOutcome(UploadOutcome.BLOCKED))
        assertTrue(uploadWorkOutcome(UploadOutcome.RETRY))
    }
    @Test fun runnerReleasesLeaseAndRetriesTheOriginalBody() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("diagnostic-worker").toFile()
        try {
            val vectors = org.json.JSONObject(javaClass.getResource("/diagnostics/receiver-vectors.json")!!.readText())
            val bytes = vectors.getString("acceptedBatchJson").toByteArray()
            val queue = DiagnosticUploadQueue(dir, { 1 }); assertTrue(queue.commit(bytes))
            val seen = mutableListOf<ByteArray>()
            val runner = DiagnosticUploadRunner(queue, { DiagnosticCredentials("a".repeat(43)) }) { record, _ ->
                seen.add(record.bytes); if (seen.size == 1) UploadOutcome.RETRY else UploadOutcome.SENT
            }
            assertEquals(UploadOutcome.RETRY, runner.run())
            assertEquals("retrying", queue.states().values.single())
            assertEquals(UploadOutcome.SENT, runner.run())
            assertEquals(2, seen.size); assertArrayEquals(bytes, seen[0]); assertArrayEquals(bytes, seen[1])
            assertTrue(queue.bodies().isEmpty()); assertNotNull(queue.lastSuccess())
        } finally { dir.deleteRecursively() }
    }
    @Test fun offlineBatchSurvivesMissingCredentialsRetryAndRestartThenIsSentOnceAndRemoved() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("diagnostic-offline").toFile()
        try {
            val bytes = org.json.JSONObject(javaClass.getResource("/diagnostics/receiver-vectors.json")!!.readText()).getString("acceptedBatchJson").toByteArray()
            val file = java.io.File(dir, "12345678-1234-4234-8234-123456789abc.json")
            assertTrue(DiagnosticUploadQueue(dir, { 1 }).commit(bytes)); assertArrayEquals(bytes, file.readBytes())
            val sends = mutableListOf<ByteArray>()
            // No imported access: nothing is sent and the stored bytes stay untouched.
            assertEquals(UploadOutcome.BLOCKED, DiagnosticUploadRunner(DiagnosticUploadQueue(dir, { 1 }), { null }) { r, _ -> sends.add(r.bytes); UploadOutcome.SENT }.run())
            assertTrue(sends.isEmpty()); assertArrayEquals(bytes, file.readBytes())
            // Receiver unreachable: retry keeps the exact bytes.
            val credentials = { DiagnosticCredentials("a".repeat(43)) }
            assertEquals(UploadOutcome.RETRY, DiagnosticUploadRunner(DiagnosticUploadQueue(dir, { 1 }), credentials) { r, _ -> sends.add(r.bytes); UploadOutcome.RETRY }.run())
            assertArrayEquals(bytes, file.readBytes())
            // Simulated restart: a new queue over the same directory still holds the batch.
            val restarted = DiagnosticUploadQueue(dir, { 1 })
            assertEquals(mapOf("12345678-1234-4234-8234-123456789abc" to "retrying"), restarted.states())
            assertEquals(1, restarted.bodies().size)
            var presentDuringSend = false
            val outcome = DiagnosticUploadRunner(restarted, credentials) { r, _ ->
                presentDuringSend = file.isFile; sends.add(r.bytes); UploadOutcome.SENT
            }.run()
            assertEquals(UploadOutcome.SENT, outcome)
            assertTrue("body is removed only after the send succeeds", presentDuringSend)
            assertEquals(2, sends.size); sends.forEach { assertArrayEquals(bytes, it) }
            assertFalse(file.exists()); assertTrue(restarted.bodies().isEmpty()); assertNotNull(restarted.lastSuccess())
        } finally { dir.deleteRecursively() }
    }
    @Test fun cancelledUploadKeepsExactBytesAndReleasesLeaseSoReplacementWorkCanSendThem() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("diagnostic-cancel").toFile()
        try {
            val bytes = org.json.JSONObject(javaClass.getResource("/diagnostics/receiver-vectors.json")!!.readText()).getString("acceptedBatchJson").toByteArray()
            val queue = DiagnosticUploadQueue(dir, { 1 }); assertTrue(queue.commit(bytes))
            val credentials = { DiagnosticCredentials("a".repeat(43)) }
            try {
                DiagnosticUploadRunner(queue, credentials) { _, _ -> throw kotlinx.coroutines.CancellationException() }.run()
                fail("cancellation must propagate")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(1, queue.bodies().size); assertArrayEquals(bytes, queue.bodies().single())
            val sent = mutableListOf<ByteArray>()
            assertEquals(UploadOutcome.SENT, DiagnosticUploadRunner(queue, credentials) { r, _ -> sent.add(r.bytes); UploadOutcome.SENT }.run())
            assertEquals(1, sent.size); assertArrayEquals(bytes, sent.single()); assertTrue(queue.bodies().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun runnerRetainsBlockedTerminalAndReleasesOnCancellation()= kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("diagnostic-worker").toFile()
        try {
            val bytes = org.json.JSONObject(javaClass.getResource("/diagnostics/receiver-vectors.json")!!.readText()).getString("acceptedBatchJson").toByteArray()
            val queue = DiagnosticUploadQueue(dir, { 1 }); queue.commit(bytes)
            val credentials = { DiagnosticCredentials("a".repeat(43)) }
            DiagnosticUploadRunner(queue, credentials) { _, _ -> UploadOutcome.BLOCKED }.run()
            assertNull(queue.lease()); queue.resumeBlocked()
            try {
                DiagnosticUploadRunner(queue, credentials) { _, _ -> throw kotlinx.coroutines.CancellationException() }.run()
                fail("cancellation must propagate")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            val leased = queue.lease()!!; queue.release(leased.batchId)
            DiagnosticUploadRunner(queue, credentials) { _, _ -> UploadOutcome.TERMINAL }.run()
            assertNull(queue.lease()); assertEquals(1, queue.bodies().size)
        } finally { dir.deleteRecursively() }
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class DiagnosticCaptureAndSchedulerTest {
    @Test fun failureBoundaryCommitsOnlyPrivateTypedFacts() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = java.io.File(context.filesDir, "diagnostic-upload")
        directory.deleteRecursively()
        val publicLog = java.io.File(context.filesDir, "manual-log.txt").apply { writeText("SECRET_PUBLIC_LOG") }
        val journal = java.io.File(context.filesDir, TraceJournal.FILE_NAME).apply { writeText("SECRET_TRACE") }
        val queue = DiagnosticUploadQueue(directory)
        val scheduler = io.mockk.mockk<DiagnosticUploadScheduler>(relaxed = true)
        val sent = java.util.concurrent.CountDownLatch(1)
        io.mockk.every { scheduler.enqueue() } answers { sent.countDown(); Unit }
        val capture = DiagnosticLogCapture(context, queue, scheduler)
        try {
            capture.start()
            DiagnosticEmitters.voiceFailure("failed", IllegalStateException("SECRET_ERROR"))
            assertTrue("failure boundary must commit", sent.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val text = queue.bodies().single().toString(Charsets.UTF_8)
            assertFalse(text.contains("SECRET")); assertTrue(text.contains("voice.failure"))
            assertEquals("SECRET_PUBLIC_LOG", publicLog.readText()); assertEquals("SECRET_TRACE", journal.readText())
            capture.stop()
        } finally { capture.stop(); directory.deleteRecursively(); publicLog.delete(); journal.delete() }
    }

    @Test fun schedulerUsesUniqueKeepAndConnectedPeriodicRecovery() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = io.mockk.mockk<androidx.work.WorkManager>(relaxed = true)
        io.mockk.mockkStatic(androidx.work.WorkManager::class)
        try {
            io.mockk.every { androidx.work.WorkManager.getInstance(context) } returns manager
            val scheduler = DiagnosticUploadScheduler(context)
            scheduler.recover(); scheduler.enqueue()
            val immediate = mutableListOf<androidx.work.OneTimeWorkRequest>()
            val periodic = io.mockk.slot<androidx.work.PeriodicWorkRequest>()
            io.mockk.verify(exactly = 2) { manager.enqueueUniqueWork(DiagnosticUploadScheduler.IMMEDIATE,
                androidx.work.ExistingWorkPolicy.KEEP, capture(immediate)) }
            io.mockk.verify(exactly = 1) { manager.enqueueUniquePeriodicWork(DiagnosticUploadScheduler.RECOVERY,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP, capture(periodic)) }
            assertEquals(androidx.work.NetworkType.CONNECTED, immediate.first().workSpec.constraints.requiredNetworkType)
            assertEquals(androidx.work.NetworkType.CONNECTED, periodic.captured.workSpec.constraints.requiredNetworkType)
            assertEquals(15 * 60 * 1000L, periodic.captured.workSpec.intervalDuration)
        } finally { io.mockk.unmockkStatic(androidx.work.WorkManager::class) }
    }
}
