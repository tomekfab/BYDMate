package com.bydmate.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.Operation
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.SettableFuture
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.ExecutionException

/**
 * WorkManager keeps a retried (backed-off) unique work waiting up to 5 hours, and `KEEP` does not
 * touch it. A genuine recovery (service start, imported access, manual send) must restart that wait;
 * an ordinary batch-commit `enqueue()` must keep deduping; a running upload must never be cancelled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticUploadSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = mockk<WorkManager>(relaxed = true)
    private val scheduler = DiagnosticUploadScheduler(context)

    @Before fun mockWorkManager() {
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(context) } returns manager
        lookup(immediate = emptyList(), recovery = emptyList())
    }

    @After fun unmockWorkManager() { unmockkStatic(WorkManager::class) }

    private fun info(state: WorkInfo.State, runAttemptCount: Int) =
        WorkInfo(UUID.randomUUID(), state, emptySet(), Data.EMPTY, Data.EMPTY, runAttemptCount)

    /** A completed future; `addListener` runs the listener at once, like a direct executor would. */
    private fun done(infos: List<WorkInfo>): ListenableFuture<List<WorkInfo>> = mockk {
        every { get() } returns infos
        every { addListener(any(), any()) } answers { firstArg<Runnable>().run() }
    }

    private fun lookup(immediate: List<WorkInfo>, recovery: List<WorkInfo>) {
        every { manager.getWorkInfosForUniqueWork(DiagnosticUploadScheduler.IMMEDIATE) } returns done(immediate)
        every { manager.getWorkInfosForUniqueWork(DiagnosticUploadScheduler.RECOVERY) } returns done(recovery)
    }

    private fun verifyNothingReplaced() {
        verify(exactly = 0) { manager.enqueueUniqueWork(any(), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>()) }
        verify(exactly = 0) { manager.enqueueUniquePeriodicWork(any(), ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, any()) }
        verify(exactly = 0) { manager.cancelUniqueWork(any()) }
    }

    @Test fun recoverRestartsBackedOffImmediateUpload() {
        lookup(immediate = listOf(info(WorkInfo.State.ENQUEUED, runAttemptCount = 4)), recovery = emptyList())
        scheduler.recover()
        verify(exactly = 1) { manager.enqueueUniqueWork(DiagnosticUploadScheduler.IMMEDIATE, ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>()) }
    }

    @Test fun recoverRestartsBackedOffPeriodicRecoveryWithoutRunningItTwiceNow() {
        lookup(immediate = emptyList(), recovery = listOf(info(WorkInfo.State.ENQUEUED, runAttemptCount = 6)))
        scheduler.recover()
        val request = slot<PeriodicWorkRequest>()
        verify(exactly = 1) { manager.enqueueUniquePeriodicWork(DiagnosticUploadScheduler.RECOVERY, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, capture(request)) }
        // The immediate upload covers "now"; the replacement periodic work first runs one period later.
        assertEquals(15 * 60 * 1000L, request.captured.workSpec.initialDelay)
        assertEquals(15 * 60 * 1000L, request.captured.workSpec.intervalDuration)
    }

    @Test fun recoverNeverCancelsAnUploadThatIsRunning() {
        // A running retry attempt also reports runAttemptCount > 0; only ENQUEUED means "waiting in backoff".
        lookup(immediate = listOf(info(WorkInfo.State.RUNNING, runAttemptCount = 2)),
            recovery = listOf(info(WorkInfo.State.RUNNING, runAttemptCount = 1)))
        scheduler.recover()
        verifyNothingReplaced()
    }

    @Test fun recoverLeavesFreshOrWaitingForNetworkWorkAlone() {
        // runAttemptCount 0 = never retried: it waits for the network constraint, not for a backoff.
        lookup(immediate = listOf(info(WorkInfo.State.ENQUEUED, runAttemptCount = 0)),
            recovery = listOf(info(WorkInfo.State.ENQUEUED, runAttemptCount = 0)))
        scheduler.recover()
        verifyNothingReplaced()
    }

    @Test fun recoverStillEnqueuesKeepWhenTheStateLookupFails() {
        val failed = mockk<ListenableFuture<List<WorkInfo>>> {
            every { get() } throws ExecutionException(IllegalStateException("db closed"))
            every { addListener(any(), any()) } answers { firstArg<Runnable>().run() }
        }
        every { manager.getWorkInfosForUniqueWork(any()) } returns failed
        scheduler.recover()
        verify(exactly = 1) { manager.enqueueUniqueWork(DiagnosticUploadScheduler.IMMEDIATE, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
        verify(exactly = 1) { manager.enqueueUniquePeriodicWork(DiagnosticUploadScheduler.RECOVERY, ExistingPeriodicWorkPolicy.KEEP, any()) }
        verifyNothingReplaced()
    }

    @Test fun overlappingRecoveryDoesNotReplaceTheSameObservedGenerationTwice() {
        val listener = slot<Runnable>()
        val pending = mockk<ListenableFuture<List<WorkInfo>>> {
            every { get() } returns listOf(info(WorkInfo.State.ENQUEUED, 4))
            every { addListener(capture(listener), any()) } answers { }
        }
        every { manager.getWorkInfosForUniqueWork(DiagnosticUploadScheduler.IMMEDIATE) } returns pending
        scheduler.recover()
        scheduler.recover()
        verify(exactly = 1) { manager.getWorkInfosForUniqueWork(DiagnosticUploadScheduler.IMMEDIATE) }
        listener.captured.run()
        scheduler.recover()
        verify(exactly = 1) {
            manager.enqueueUniqueWork(DiagnosticUploadScheduler.IMMEDIATE, ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
    }

    @Test fun recoveryCanObserveFreshWorkAfterReplacementCommits() {
        val committed = SettableFuture.create<Operation.State.SUCCESS>()
        val replacement = mockk<Operation> { every { result } returns committed }
        every {
            manager.enqueueUniqueWork(DiagnosticUploadScheduler.IMMEDIATE, ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        } returns replacement
        lookup(immediate = listOf(info(WorkInfo.State.ENQUEUED, 4)), recovery = emptyList())
        scheduler.recover()
        scheduler.recover()
        verify(exactly = 1) { manager.getWorkInfosForUniqueWork(DiagnosticUploadScheduler.IMMEDIATE) }
        lookup(immediate = listOf(info(WorkInfo.State.RUNNING, 0)), recovery = emptyList())
        committed.set(Operation.SUCCESS)
        scheduler.recover()
        verify(exactly = 2) { manager.getWorkInfosForUniqueWork(DiagnosticUploadScheduler.IMMEDIATE) }
        verify(exactly = 1) {
            manager.enqueueUniqueWork(DiagnosticUploadScheduler.IMMEDIATE, ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
    }

    @Test fun ordinaryEnqueueDedupesEvenWhileBackedOff() {
        lookup(immediate = listOf(info(WorkInfo.State.ENQUEUED, runAttemptCount = 4)),
            recovery = listOf(info(WorkInfo.State.ENQUEUED, runAttemptCount = 6)))
        scheduler.enqueue(); scheduler.enqueue()
        verify(exactly = 2) { manager.enqueueUniqueWork(DiagnosticUploadScheduler.IMMEDIATE, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
        verify(exactly = 0) { manager.enqueueUniquePeriodicWork(any(), any(), any()) }
        verifyNothingReplaced()
    }
}
