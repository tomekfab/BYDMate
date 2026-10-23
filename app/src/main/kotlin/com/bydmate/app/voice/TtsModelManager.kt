package com.bydmate.app.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File

/** Downloads and unpacks the pinned Gosia TTS archive under filesDir/tts/gosia. */
class TtsModelManager(
    private val context: Context,
    private val http: OkHttpClient,
    /** The pinned package behind every Polish voice (Gosia); injectable so tests need not build
     *  the real 21 MB archive. */
    private val polishVoicePackage: PolishModelPackage = PolishModelManifest.GOSIA_MEDIUM_INT8,
) {

    private fun baseDir(modelDirId: String) = File(context.filesDir, "tts/$modelDirId")

    private fun stagingDir(modelDirId: String) = File(context.filesDir, "tts/.staging-$modelDirId")

    fun modelDirPath(modelDirId: String): String = baseDir(modelDirId).absolutePath

    /** The archive's .onnx filename is not hardcoded — find the single model file.
     *  AppleDouble sidecar files (._*.onnx) are skipped; they crash sherpa-onnx. */
    fun onnxFile(modelDirId: String): File? =
        baseDir(modelDirId).listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".onnx") && !it.name.startsWith("._") }

    private fun packageFor(@Suppress("UNUSED_PARAMETER") voice: TtsVoice): PolishModelPackage = polishVoicePackage

    /** Cheap, main-thread safe: every pinned file exists with its pinned size and every pinned
     *  tree has its file count. It cannot see same-size corruption; [verifyVoice] hashes. */
    private fun hasPinnedFiles(dir: File, pkg: PolishModelPackage): Boolean =
        pkg.files.all { f -> File(dir, f.relativePath).let { it.isFile && it.length() == f.sizeBytes } } &&
            pkg.requiredTrees.all { t -> File(dir, t.relativePath).walkTopDown().count { it.isFile } == t.fileCount }

    fun isReady(voice: TtsVoice): Boolean {
        val pkg = packageFor(voice)
        return hasPinnedFiles(baseDir(voice.modelDirId), pkg)
    }

    /** Full check of an installed voice; empty means usable. A Polish voice is hashed against its
     *  pin (hundreds of KB to tens of MB: call off the main thread); a Russian one is checked by
     *  layout, as before. Runs under the disk lock so a delete cannot interleave. */
    suspend fun verifyVoice(voice: TtsVoice): List<String> = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            PolishModelManifest.verifyInstalled(baseDir(voice.modelDirId), packageFor(voice))
        }
    }

    /** Readiness of the Polish voice for [VoiceProfileStore.activate]: Gosia hashes clean. */
    suspend fun isPolishVoiceVerified(): Boolean =
        verifyVoice(TtsVoiceCatalog.defaultFor()).isEmpty()

    /** Serializes all disk mutations of the model dir: a delete can never
     *  interleave with download's commit (unpack) section, so a cancelled
     *  download cannot recreate a dir the user just deleted. */
    internal val diskMutex = Mutex()

    suspend fun delete(modelDirId: String) {
        diskMutex.withLock {
            baseDir(modelDirId).deleteRecursively()
            stagingDir(modelDirId).deleteRecursively()
        }
    }

    suspend fun download(voice: TtsVoice, onProgress: (Int) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(context.cacheDir, "tts-${voice.modelDirId}.tar.bz2")
                val pkg = packageFor(voice)
                val pinned = pkg.downloads.single()
                try {
                    fetchArchive(voice, tmp, pinned, onProgress)
                    diskMutex.withLock {
                        // Checked under the lock: a delete that cancelled us has
                        // either finished (we bail here) or runs strictly after
                        // this whole commit section (and removes its result).
                        ensureActive()
                        val staging = stagingDir(voice.modelDirId)
                        val target = baseDir(voice.modelDirId)
                        staging.deleteRecursively()
                        staging.mkdirs()
                        try {
                            untarFlatten(tmp, staging)
                            val problems = PolishModelManifest.verifyInstalled(staging, pkg)
                            check(problems.isEmpty()) { "unpacked voice failed verification: ${problems.joinToString("; ")}" }
                            // Atomic publish: the final dir only ever appears as a
                            // fully verified unpack, so a process kill mid-unpack can
                            // never leave a dir that isReady() accepts.
                            target.deleteRecursively()
                            check(staging.renameTo(target)) { "failed to publish staged model" }
                        } catch (t: Throwable) {
                            staging.deleteRecursively()
                            throw t
                        }
                    }
                    // Cancelled between commit and return: don't report success —
                    // the serialized delete() removes the dir, state must not flip.
                    ensureActive()
                } finally {
                    tmp.delete()
                }
            }.onFailure { if (it is CancellationException) throw it }
        }

    private suspend fun fetchArchive(
        voice: TtsVoice,
        tmp: File,
        pinned: PolishDownload,
        onProgress: (Int) -> Unit,
    ) {
        require(voice.url.startsWith("https://")) { "voice download is not https" }
        http.newCall(Request.Builder().url(voice.url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            check(resp.request.url.isHttps) { "download ended on a non-https url" }
            val body = resp.body ?: error("empty response body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    copyArchive(input, out, pinned, total, onProgress)
                }
            }
        }
        // A pinned voice is verified before the live install is touched: size and
        // SHA-256 of the archive here, every file again after unpacking below.
        check(tmp.length() == pinned.sizeBytes) { "archive size ${tmp.length()} != ${pinned.sizeBytes}" }
        check(sha256(tmp) == pinned.sha256) { "archive sha256 does not match the pin" }
    }

    private suspend fun copyArchive(
        input: java.io.InputStream,
        out: java.io.OutputStream,
        pinned: PolishDownload,
        total: Long,
        onProgress: (Int) -> Unit,
    ) {
        var read = 0L
        val buf = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf); if (n < 0) break
            out.write(buf, 0, n); read += n
            check(read <= pinned.sizeBytes) { "download is larger than the pinned size" }
            if (total > 0) onProgress(((read * 100) / total).toInt())
        }
    }

    /** Archive has a single top-level folder; flatten it into [target].
     *  Tar Slip guard mirrors VoiceModelManager.unzipFlatten's Zip Slip guard. */
    internal fun untarFlatten(archive: File, target: File) {
        val canonicalTarget = target.canonicalFile
        BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val rel = entry.name.substringAfter('/')
                    if (isJunkPath(rel)) { entry = tar.nextEntry; continue }
                    if (rel.isNotBlank()) {
                        val outFile = File(target, rel)
                        val canonicalOut = outFile.canonicalFile
                        if (canonicalOut.path != canonicalTarget.path &&
                            !canonicalOut.path.startsWith(canonicalTarget.path + File.separator)) {
                            entry = tar.nextEntry; continue
                        }
                        if (entry.isDirectory) outFile.mkdirs()
                        else { outFile.parentFile?.mkdirs(); outFile.outputStream().use { tar.copyTo(it) } }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_VOICE_ID = "gosia"

        /** macOS tar pollutes archives with AppleDouble (._*) and Finder junk; commons-compress
         *  faithfully extracts them, and a ._*.onnx picked up by onnxFile() crashes sherpa-onnx
         *  (field defect: the Artem voice went silent, 2026-07-08). */
        internal fun isJunkPath(path: String): Boolean =
            path.split('/').any { it.startsWith("._") || it == ".DS_Store" || it == "__MACOSX" }
    }
}
