package com.bydmate.app.diagnostics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

class DiagnosticBody internal constructor(val batchId: String, val createdAtMs: Long, bytes: ByteArray) {
    private val content = bytes.copyOf()
    val bytes: ByteArray get() = content.copyOf()
    val digest: String = diagnosticDigest(content)
    fun expired(now: Long): Boolean = now - createdAtMs >= DiagnosticUploadQueue.TTL_MS
}

internal fun diagnosticDigest(bytes: ByteArray): String = "sha256=" +
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Bodies are authoritative; mutable state never changes their bytes. All disk methods run on IO. */
@Singleton
class DiagnosticUploadQueue internal constructor(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "diagnostic-upload"))

    private val files = DiagnosticQueueFiles(directory)
    private val leases = mutableSetOf<String>()
    var queueDropped: Long = 0
        private set
    var storageFailures: Long = 0
        private set

    @Synchronized fun commit(bytes: ByteArray): Boolean = runCatching {
        require(RemoteDiagnosticEventParser.validBatch(bytes))
        directory.mkdirs()
        val incoming = files.body(bytes)
        val target = files.bodyFile(incoming.batchId)
        if (target.exists()) return target.readBytes().contentEquals(bytes)
        recover()
        if (incoming.expired(clock())) return false
        val existing = files.records().toMutableList()
        while (existing.size >= MAX_BATCHES || existing.sumOf { it.bytes.size.toLong() } + bytes.size > MAX_BYTES) {
            val victim = existing.firstOrNull { it.batchId !in leases } ?: run { queueDropped = bounded(queueDropped + 1); return false }
            files.remove(victim.batchId)
            existing.remove(victim)
            queueDropped = bounded(queueDropped + 1)
        }
        atomicWrite(target, bytes)
        true
    }.getOrElse { storageFailures = bounded(storageFailures + 1); false }

    @Synchronized fun recover() {
        directory.mkdirs()
        directory.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        directory.listFiles().orEmpty().filter { it.extension == "json" }.forEach { candidate ->
            val valid = runCatching {
                require(candidate.length() <= MAX_BATCH_BYTES)
                val bytes = candidate.readBytes()
                require(RemoteDiagnosticEventParser.validBatch(bytes))
                val record = files.body(bytes)
                require(candidate.name == "${record.batchId}.json")
                if (record.expired(clock()) && record.batchId !in leases) files.remove(record.batchId)
            }.isSuccess
            if (!valid && candidate.nameWithoutExtension !in leases) {
                files.remove(candidate.nameWithoutExtension)
                queueDropped = bounded(queueDropped + 1)
            }
        }
        // A crash after last-success persistence but before deletion must not upload again.
        lastSuccess()?.let { success ->
            files.records().firstOrNull { it.batchId == success.optString("batchId") && it.digest == success.optString("digest") }
                ?.let { files.remove(it.batchId) }
        }
        var all = files.records()
        while (all.size > MAX_BATCHES || all.sumOf { it.bytes.size.toLong() } > MAX_BYTES) {
            val victim = all.firstOrNull { it.batchId !in leases } ?: break
            files.remove(victim.batchId)
            queueDropped = bounded(queueDropped + 1)
            all = all - victim
        }
    }

    @Synchronized fun bodies(): List<ByteArray> { recover(); return files.records().map { it.bytes } }
    @Synchronized fun lease(): DiagnosticBody? {
        recover()
        val next = files.records().firstOrNull { it.batchId !in leases && files.state(it.batchId) in setOf("pending", "retrying") } ?: return null
        leases.add(next.batchId)
        return next
    }
    @Synchronized fun release(id: String) { leases.remove(id) }
    @Synchronized fun retain(id: String, outcome: UploadOutcome) {
        require(outcome != UploadOutcome.SENT)
        atomicWrite(File(directory, "$id.state"), (if (outcome == UploadOutcome.RETRY) "retrying" else outcome.name.lowercase()).toByteArray())
    }
    @Synchronized fun resumeBlocked() {
        directory.listFiles().orEmpty().filter { it.extension == "state" && it.readText() == "blocked" }.forEach { it.delete() }
    }
    @Synchronized fun acknowledge(record: DiagnosticBody) {
        val value = JSONObject().put("batchId", record.batchId).put("digest", record.digest).put("atMs", bounded(clock()))
        atomicWrite(File(directory, "last-success"), value.toString().toByteArray())
        files.remove(record.batchId)
    }
    @Synchronized fun lastSuccess(): JSONObject? = runCatching { JSONObject(File(directory, "last-success").readText()) }.getOrNull()
    @Synchronized fun states(): Map<String, String> { recover(); return files.records().associate { it.batchId to if (it.batchId in leases) "sending" else files.state(it.batchId) } }


    companion object {
        const val MAX_BATCHES = 96
        const val MAX_BYTES = 6 * 1024 * 1024
        const val TTL_MS = 7 * 24 * 60 * 60 * 1000L
    }
}

internal fun atomicWrite(target: File, bytes: ByteArray) {
    val temporary = File(target.parentFile, "${target.name}.tmp")
    FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

private class DiagnosticQueueFiles(private val directory: File) {
    fun state(id: String): String = File(directory, "$id.state").takeIf { it.exists() }?.readText() ?: "pending"
    fun bodyFile(id: String): File = File(directory, "$id.json")
    fun remove(id: String) { check(!bodyFile(id).exists() || bodyFile(id).delete()); File(directory, "$id.state").delete() }
    fun body(bytes: ByteArray): DiagnosticBody {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        return DiagnosticBody(json.getString("batchId"), json.getLong("createdAtMs"), bytes)
    }
    fun records(): List<DiagnosticBody> = directory.listFiles().orEmpty().filter { it.extension == "json" }
        .mapNotNull { runCatching { body(it.readBytes()) }.getOrNull() }.sortedWith(compareBy({ it.createdAtMs }, { it.batchId }))

}
