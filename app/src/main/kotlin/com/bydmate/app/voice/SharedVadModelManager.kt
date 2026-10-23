package com.bydmate.app.voice

import android.content.Context
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Owns the integrity-checked Silero voice-activity model used by speech recognition.
 *
 * Transfers are serialized before the temporary file is opened. Concurrent setup calls use
 * the same temporary and published paths.
 */
class SharedVadModelManager internal constructor(
    private val context: Context,
    private val http: OkHttpClient,
    private val expectedSha256: String,
) : SharedVadAsset {
    constructor(context: Context, http: OkHttpClient) : this(context, http, VAD_SHA256)

    private val vadFile get() = File(context.filesDir, "asr/silero_vad.onnx")
    private val fileLock = Any()
    private val transferMutex = Mutex()
    private val publicationMutex = Mutex()

    override fun vadPath(): String = vadFile.absolutePath

    override fun isVadReady(): Boolean = synchronized(fileLock) {
        val file = vadFile
        if (!file.isFile) return@synchronized false
        if (runCatching { sha256(file) == expectedSha256 }.getOrDefault(false)) {
            true
        } else {
            // Never pass an unverified model to the native loader, even if deletion fails.
            file.delete()
            false
        }
    }

    /** Fetches VAD only when no verified file exists; publishes only matching bytes. */
    override suspend fun ensureVad(onProgress: (Int) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                transferMutex.withLock {
                    if (isVadReady()) return@withLock
                    val temporary = File(context.cacheDir, "silero_vad.onnx.tmp")
                    temporary.delete()
                    try {
                        downloadToFile(VAD_URL, temporary, onProgress)
                        coroutineContext.ensureActive()
                        publicationMutex.withLock {
                            coroutineContext.ensureActive()
                            check(sha256(temporary) == expectedSha256) {
                                "downloaded VAD sha256 does not match the pin"
                            }
                            synchronized(fileLock) {
                                if (!isVadReady()) {
                                    vadFile.parentFile?.mkdirs()
                                    check(temporary.renameTo(vadFile)) { "failed to publish VAD file" }
                                }
                            }
                        }
                        onProgress(100)
                    } finally {
                        temporary.delete()
                    }
                }
            }.onFailure { if (it is CancellationException) throw it }
        }

    private suspend fun downloadToFile(url: String, destination: File, onProgress: (Int) -> Unit) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("empty response body")
            copyBodyToFile(body, destination, onProgress)
        }
    }

    private suspend fun copyBodyToFile(body: ResponseBody, destination: File, onProgress: (Int) -> Unit) {
        val total = body.contentLength()
        body.byteStream().use { input ->
            destination.outputStream().use { output ->
                copyInputToOutput(input, output, total, onProgress)
                output.fd.sync()
            }
        }
    }

    private suspend fun copyInputToOutput(
        input: InputStream,
        output: OutputStream,
        total: Long,
        onProgress: (Int) -> Unit,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var read = 0L
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) return read
            output.write(buffer, 0, count)
            read += count
            if (total > 0) onProgress(((read * 100) / total).toInt())
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        // Official GitHub release API digest for asset 271935959 (silero_vad.onnx).
        // Provenance: docs/voice/shared-vad-provenance.json. The mutable URL is pinned by bytes.
        const val VAD_SHA256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
        const val VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
    }
}
