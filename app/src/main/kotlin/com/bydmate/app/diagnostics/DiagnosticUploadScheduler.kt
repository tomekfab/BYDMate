package com.bydmate.app.diagnostics

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticUploadScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val recovering = ConcurrentHashMap.newKeySet<String>()

    /** Ordinary batch commit: dedupes against queued, running and backed-off work. */
    fun enqueue() {
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP, immediateRequest())
    }
    /**
     * Genuine startup/recovery/manual send. KEEP leaves work that WorkManager retried waiting out its
     * backoff (up to 5 h), so waiting work is restarted. Observed running uploads stay untouched.
     */
    fun recover() {
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork(RECOVERY, ExistingPeriodicWorkPolicy.KEEP, periodicRequest())
        enqueue()
        restartBackedOff(manager, IMMEDIATE) {
            manager.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.REPLACE, immediateRequest())
        }
        restartBackedOff(manager, RECOVERY) {
            // The immediate work covers "now"; the replacement periodic work first runs one period later.
            manager.enqueueUniquePeriodicWork(RECOVERY, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                periodicRequest(initialDelayMinutes = PERIOD_MINUTES))
        }
    }
    // ENQUEUED with earlier attempts = waiting in backoff. RUNNING also has attempts > 0 and stays untouched.
    private fun restartBackedOff(manager: WorkManager, name: String, restart: () -> Operation) {
        if (!recovering.add(name)) return
        try {
            val lookup = manager.getWorkInfosForUniqueWork(name)
            lookup.addListener({
                restartObservedBackoff(name, restart) {
                    lookup.get().any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
                }
            }, { it.run() })
        } catch (_: Exception) {
            recovering.remove(name)
        }
    }
    private fun restartObservedBackoff(name: String, restart: () -> Operation, backedOff: () -> Boolean) {
        try {
            if (backedOff()) {
                // Keep the observation exclusive until WorkManager commits the replacement.
                restart().result.addListener({ recovering.remove(name) }, { it.run() })
            } else {
                recovering.remove(name)
            }
        } catch (_: Exception) {
            recovering.remove(name)
        }
    }
    private fun immediateRequest() = OneTimeWorkRequestBuilder<DiagnosticUploadWorker>().setConstraints(constraints()).build()
    private fun periodicRequest(initialDelayMinutes: Long = 0) =
        PeriodicWorkRequestBuilder<DiagnosticUploadWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints()).setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES).build()
    private fun constraints(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    companion object {
        const val IMMEDIATE = "diagnostic-upload-immediate"
        const val RECOVERY = "diagnostic-upload-recovery"
        private const val PERIOD_MINUTES = 15L
    }
}
