package com.bydmate.app.diagnostics

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

@HiltWorker
class DiagnosticUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: DiagnosticUploadQueue,
    private val config: DiagnosticUploadConfig,
    private val client: DiagnosticUploadClient,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (uploadWorkOutcome(DiagnosticUploadRunner(queue, config::read, client::upload).run())) Result.retry() else Result.success()
    }
}

internal fun uploadWorkOutcome(outcome: UploadOutcome): Boolean = outcome == UploadOutcome.RETRY

internal class DiagnosticUploadRunner(
    private val queue: DiagnosticUploadQueue,
    private val credentials: () -> DiagnosticCredentials?,
    private val send: suspend (DiagnosticBody, DiagnosticCredentials) -> UploadOutcome,
) {
    suspend fun run(): UploadOutcome {
        val credential = credentials() ?: return UploadOutcome.BLOCKED
        return try {
            var record = queue.lease()
            while (record != null) {
                val current = record
                val outcome = try {
                    send(current, credential).also {
                        when (it) {
                            UploadOutcome.SENT -> queue.acknowledge(current)
                            UploadOutcome.BLOCKED, UploadOutcome.TERMINAL, UploadOutcome.RETRY -> queue.retain(current.batchId, it)
                        }
                    }
                } finally { queue.release(current.batchId) }
                if (outcome == UploadOutcome.RETRY || outcome == UploadOutcome.BLOCKED) return outcome
                record = queue.lease()
            }
            UploadOutcome.SENT
        } catch (_: IOException) { UploadOutcome.RETRY }
    }
}
