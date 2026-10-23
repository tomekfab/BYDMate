package com.bydmate.app.navdata

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File

/** Debug implementation. The file stays app-private until the Activity receives a user-picked URI. */
class DefaultNavCaptureBackend : NavCaptureLifecycleBackend {
    private var context: Context? = null
    private var store = NavCaptureStore(clockMs = System::currentTimeMillis, sink = MemorySink())

    override fun captureActivityIntent(context: Context): Intent = Intent(context, NavCaptureActivity::class.java)

    override fun start(packageName: String, context: Context?): Boolean {
        context?.let { ensureStore(it.applicationContext) }
        return store.start(packageName)
    }

    private val lastA11yMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    override fun notification(record: NavCaptureRecord) { store.enqueue(enrich(record)) }
    override fun accessibility(record: NavCaptureRecord) {
        val now = System.currentTimeMillis()
        val debounceKey = "${record.packageName}/${record.kind}"
        if (now - (lastA11yMs[debounceKey] ?: 0L) < 250L) return
        lastA11yMs[debounceKey] = now
        store.enqueue(enrich(record))
    }
    override fun marker(packageName: String, marker: NavCaptureMarker) {
        store.enqueue(enrich(NavCaptureRecord(
            kind = "marker", packageName = packageName, timestampMs = System.currentTimeMillis(), marker = marker.wireName,
        )))
    }
    override fun stop() { store.stop() }
    override fun delete() { store.delete() }
    override fun snapshot(): NavCaptureSnapshot = store.snapshot()
    override fun flushForTest() = store.flushForTest()

    override fun privateCaptureFile(context: Context): File {
        ensureStore(context.applicationContext)
        return File(context.filesDir, "nav-capture/capture.jsonl")
    }

    override fun saveTo(context: Context, destination: android.net.Uri): Boolean = store.saveStopped {
        val source = File(this.context?.filesDir ?: return@saveStopped false, "nav-capture/capture.jsonl")
        source.inputStream().use { input ->
            context.contentResolver.openOutputStream(destination)?.use { output -> input.copyTo(output) } != null
        }
    }

    private fun ensureStore(value: Context) {
        if (context === value) return
        context = value
        val file = File(value.filesDir, "nav-capture/capture.jsonl")
        file.parentFile?.mkdirs()
        store = NavCaptureStore(clockMs = System::currentTimeMillis, sink = FileSink(file))
    }

    private fun enrich(record: NavCaptureRecord): NavCaptureRecord {
        val app = context ?: return record
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
            else @Suppress("DEPRECATION") app.packageManager.getInstallerPackageName(app.packageName)
        }.getOrNull().orEmpty()
        return record.copy(metadata = record.metadata + mapOf("appVersion" to version, "installSource" to installer))
    }
}

private class MemorySink : NavCaptureStore.Sink {
    override fun append(line: String) = true
    override fun delete() = true
}

private class FileSink(private val file: File) : NavCaptureStore.Sink {
    override fun append(line: String): Boolean = runCatching { file.appendText(line, Charsets.UTF_8); true }.getOrDefault(false)
    override fun delete(): Boolean = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
    override fun reset(): Boolean = runCatching { file.writeText("", Charsets.UTF_8); true }.getOrDefault(false)
}
