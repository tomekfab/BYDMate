package com.bydmate.app.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.bydmate.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Separate private typed ring. Never opens LogRecorder, logcat, or the journal file. */
@Singleton
class DiagnosticLogCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queue: DiagnosticUploadQueue,
    private val scheduler: DiagnosticUploadScheduler,
) {
    private var scope: CoroutineScope? = null
    private var events: Channel<suspend () -> Unit>? = null
    private var timer: Job? = null
    private var batcher: DiagnosticBatcher? = null

    @Synchronized fun start() {
        if (scope != null) return
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channel = Channel<suspend () -> Unit>(256)
        val ring = DiagnosticBatcher(metadata = { metadata() }, committed = { bytes ->
            queue.commit(bytes).also { if (it) scheduler.enqueue() }
        })
        batcher = ring; scope = worker; events = channel
        DiagnosticEmitters.install { event -> channel.trySend {
            ring.counters(DiagnosticEmitters.captureDropped(), queue.queueDropped, queue.storageFailures)
            ring.add(event)
            if (event.json().getString("type").endsWith("failure")) ring.boundary()
        }.isSuccess }
        worker.launch {
            try {
                for (action in channel) runCatching { action() }.onFailure { Trace.event(TraceArea.APP, "diagnostic-storage-failure") }
            } finally {
                runCatching { ring.closedJournal(Trace.lines()); ring.boundary() }
            }
        }.also { it.invokeOnCompletion { worker.cancel() } }
        timer = worker.launch { while (true) { delay(1000); channel.send { ring.tick() } } }
    }
    @Synchronized fun boundary() {
        val ring = batcher ?: return
        events?.trySend { ring.closedJournal(Trace.lines()); ring.boundary() }
    }
    @Synchronized fun stop() {
        DiagnosticEmitters.install(null)
        timer?.cancel(); timer = null
        events?.close()
        events = null; scope = null; batcher = null
    }
    @Synchronized fun sendNow() {
        if (batcher != null) {
            boundary()
            scheduler.recover()
        } else {
            // A one-shot closed-journal snapshot must never install an emitter sink or timer.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val snapshot = DiagnosticBatcher(metadata = { metadata() }, committed = queue::commit)
                    snapshot.closedJournal(Trace.lines())
                    snapshot.boundary()
                    scheduler.recover()
                } catch (_: java.io.IOException) {
                    Trace.event(TraceArea.APP, "diagnostic-storage-failure")
                } finally {
                    cancel()
                }
            }
        }
    }

    private fun metadata(): Pair<JSONObject, JSONObject> {
        val install = File(context.noBackupFilesDir, "diagnostic-install-id")
        if (!install.exists()) atomicWrite(install, UUID.randomUUID().toString().toByteArray())
        val app = JSONObject().put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
            .put("buildType", BuildConfig.BUILD_TYPE).put("installIdHash", diagnosticDigest(install.readBytes()).removePrefix("sha256="))
        val manufacturer = when (Build.MANUFACTURER.lowercase()) { "byd" -> "byd"; "google" -> "google"; else -> "other" }
        val model = when { manufacturer == "byd" -> "head_unit"; Build.FINGERPRINT.startsWith("generic") -> "emulator"; else -> "other" }
        val caps = JSONObject().put("microphone", context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            .put("overlay", Settings.canDrawOverlays(context)).put("notificationListener", notificationAccess())
        return app to JSONObject().put("apiLevel", Build.VERSION.SDK_INT.coerceIn(29, 100)).put("manufacturer", manufacturer)
            .put("model", model).put("capabilities", caps)
    }
    private fun notificationAccess(): Boolean = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?.split(':')?.any { it.substringBefore('/') == context.packageName } == true
}

/** Public callers use fixed templates; all parsing and capture delivery happens on IO. */
object DiagnosticEmitters {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class Emission(val value: JSONObject, val destination: (RemoteDiagnosticEvent) -> Boolean)
    private val pending = Channel<Emission>(256)
    private val dropped = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var sink: ((RemoteDiagnosticEvent) -> Boolean)? = null
    init { scope.launch {
        for (emission in pending) {
            val event = RemoteDiagnosticEventParser.parse(emission.value.toString())
            if (event == null || !emission.destination(event)) recordDrop()
        }
    } }
    internal fun install(value: ((RemoteDiagnosticEvent) -> Boolean)?) { sink = value }
    fun lifecycle(state: String, durationMs: Long = 0) {
        emit(JSONObject().put("type", "voice.lifecycle").put("state", state).put("durationMs", durationMs.coerceIn(0, 300_000)))
    }
    fun voiceFailure(stage: String, error: Throwable? = null, reason: String = "unknown") {
        emit(JSONObject().put("type", "voice.failure").put("stage", stage).put("reason", reason).put("exceptionClass", exceptionClass(error)))
    }
    fun request(backend: String, operation: String, durationMs: Long = 0, outcome: String = "success", httpStatus: Int? = null) {
        emit(JSONObject().put("type", "connection.request").put("backend", backend).put("operation", operation)
            .put("durationMs", durationMs.coerceIn(0, 120_000)).put("outcome", outcome).apply { if (httpStatus != null) put("httpStatus", httpStatus) })
    }
    fun failure(backend: String, error: Throwable? = null, reason: String = "transport", httpStatus: Int? = null) {
        emit(JSONObject().put("type", "connection.failure").put("backend", backend).put("phase", "request")
            .put("reason", reason).put("exceptionClass", exceptionClass(error)).put("retryable", true)
            .apply { if (httpStatus != null) put("httpStatus", httpStatus) })
    }
    private fun emit(value: JSONObject) {
        val destination = sink ?: return
        if (pending.trySend(Emission(value.put("atMs", bounded(System.currentTimeMillis())), destination)).isFailure) recordDrop()
    }
    private fun recordDrop() { dropped.updateAndGet { bounded(it + 1) } }
    internal fun captureDropped(): Long = dropped.get()
    internal fun exceptionClass(error: Throwable?): String = when (error?.javaClass) {
        null -> "none"
        java.net.SocketTimeoutException::class.java -> "SocketTimeoutException"
        javax.net.ssl.SSLException::class.java -> "SSLException"
        java.io.IOException::class.java -> "IOException"
        IllegalStateException::class.java -> "IllegalStateException"
        java.util.concurrent.CancellationException::class.java -> "CancellationException"
        else -> "Other"
    }
}
