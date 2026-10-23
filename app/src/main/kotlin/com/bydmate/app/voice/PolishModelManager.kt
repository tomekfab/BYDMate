package com.bydmate.app.voice

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Downloads, verifies, installs and deletes the Polish ASR package (Parakeet, by default the
 *  pinned [PolishModelManifest.PARAKEET_TDT_V3_INT8]) under filesDir/asr/pl/<package id>/.
 *
 *  Nothing here touches the Russian files or preferences. The silero VAD is shared with the
 *  Russian profile, so it is reached through [vad]: this class only asks for it when missing and
 *  never deletes it. Files are fetched over HTTPS into a staging directory and the package is
 *  published by rename only after every file matches its pinned size and SHA-256.
 *
 *  [coordinator] closes the native recognizer before files are replaced or deleted (supplied at
 *  integration, see [ProfileAsrEngine]); there is deliberately no default, because a no-op
 *  would let a delete run under a live model. */
class PolishModelManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val vad: SharedVadAsset,
    private val coordinator: NativeAssetCoordinator,
    private val pkg: PolishModelPackage = PolishModelManifest.PARAKEET_TDT_V3_INT8,
    /** Free bytes on the volume backing filesDir; injectable so tests never read the host disk. */
    private val usableSpace: () -> Long = { context.filesDir.usableSpace },
) : AsrAssets {

    enum class Phase { ASR, VAD }

    /** Thrown by [download] when the storage precheck refuses to start. */
    class InsufficientStorageException(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : IOException("needs $requiredBytes free bytes, only $availableBytes available")

    /** Crash-guard scope for this package's recognizer: language and model, so another model
     *  (or Russian) never inherits this one's crash evidence. */
    val guardScope: String = AsrLoadGuard.scopeFor(VoiceLanguage.PL, pkg.id)

    private val directories = PolishInstallDirectories(context.filesDir, pkg.id)
    fun installDir() = directories.install

    /** Serializes every mutation of the install, staging and previous directories. */
    internal val diskMutex = Mutex()
    private val downloadMutex = Mutex()
    // Only accessed under diskMutex. Readiness must not clean an in-flight download's stage.
    private var stagingInUse = false

    /** Normal probes only check pinned sizes and VAD. Interrupted publication state is recovered
     *  under diskMutex, including hash verification before restoring a backup. Prefer [verifyAsr]
     *  off-main for startup checks, since recovery can read the whole package. */
    override fun isReady(): Boolean {
        if (!diskMutex.tryLock()) return false
        return try {
            if (directories.previous.exists() || (!stagingInUse && directories.staging.exists())) {
                if (recover().isNotEmpty()) return false
            }
            pkg.hasPinnedSizes(installDir()) && vad.isVadReady()
        } finally {
            diskMutex.unlock()
        }
    }

    override fun vadPath(): String = vad.vadPath()

    /** Full SHA-256 verification of the installed files; empty means every file is intact.
     *  Reads the whole package (hundreds of MB): call off the main thread. */
    suspend fun verifyAsr(): List<String> = withContext(Dispatchers.IO) {
        diskMutex.withLock { recover() }
    }

    /** Readiness for [VoiceProfileStore.activate]: verified ASR, shared VAD, and the TTS check
     *  that integration supplies. */
    fun readiness(tts: suspend () -> Boolean): ProfileReadiness =
        ProfileReadiness(asr = { verifyAsr() }, vad = { vad.isVadReady() }, tts = tts)

    /** The recognizer configuration for the installed package: the pinned NeMo transducer
     *  settings with this install's paths. The transducer API has no language option. */
    internal fun asrRecognizerConfig(): RecognizerConfig.NemoTransducer {
        val s = PolishModelManifest.PARAKEET_SETTINGS
        val dir = installDir()
        return RecognizerConfig.NemoTransducer(
            encoder = File(dir, s.encoder).absolutePath,
            decoder = File(dir, s.decoder).absolutePath,
            joiner = File(dir, s.joiner).absolutePath,
            tokens = File(dir, s.tokens).absolutePath,
            modelType = s.modelType,
            featureDim = s.featureDim,
            numThreads = s.numThreads,
            provider = s.provider,
            decodingMethod = s.decodingMethod,
        )
    }

    /** Stages, verifies and installs the package, then makes sure the shared VAD exists.
     *  A failed or cancelled run leaves any previous install untouched. */
    suspend fun download(onProgress: (Phase, Int) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            downloadMutex.withLock {
                runCatching {
                    require(PolishModelManifest.validate(pkg).isEmpty()) {
                        "invalid package: ${PolishModelManifest.validate(pkg)}"
                    }
                    require(pkg.downloads.all { it.installPath != null }) { "archive packages are not supported here" }
                    diskMutex.withLock { recover() }
                    val totalBytes = pkg.downloads.sumOf { it.sizeBytes }
                    val needsVad = !vad.isVadReady()
                    val required = totalBytes + totalBytes / HEADROOM_DIVISOR + (if (needsVad) VAD_RESERVE_BYTES else 0L)
                    val free = usableSpace()
                    if (free < required) throw InsufficientStorageException(required, free)

                    try {
                        diskMutex.withLock {
                            directories.staging.deleteRecursively()
                            directories.staging.mkdirs()
                            stagingInUse = true
                        }
                        var done = 0L
                        for (d in pkg.downloads) {
                            val base = done
                            PolishPackageDownload(http).fetch(d, File(directories.staging, d.installPath!!)) { read ->
                                onProgress(Phase.ASR, (((base + read) * ASR_WEIGHT) / totalBytes).toInt())
                            }
                            done += d.sizeBytes
                        }
                        currentCoroutineContext().ensureActive()
                        // Verify before touching the live install or stopping a running session.
                        val problems = PolishModelManifest.validateNemoTransducerShape(pkg) +
                            PolishModelManifest.verifyInstalled(directories.staging, pkg)
                        check(problems.isEmpty()) { "download failed verification: ${problems.joinToString("; ")}" }
                        if (needsVad) {
                            vad.ensureVad { pct -> onProgress(Phase.VAD, ASR_WEIGHT + (pct * (100 - ASR_WEIGHT)) / 100) }
                                .getOrThrow()
                        }
                        // The old ASR install remains live until the shared VAD is ready. A
                        // failed or cancelled VAD transfer therefore cannot replace it with a
                        // new package that is unusable without VAD.
                        coordinator.withAssetsReleased(VoiceLanguage.PL) {
                            diskMutex.withLock {
                                currentCoroutineContext().ensureActive()
                                check(pkg.hasPinnedSizes(directories.staging)) { "staged files disappeared before install" }
                                publish()
                            }
                        }
                        onProgress(if (needsVad) Phase.VAD else Phase.ASR, 100)
                        // Cancelled between commit and return: the serialized delete() removes the
                        // files, so success must not be reported.
                        currentCoroutineContext().ensureActive()
                    } finally {
                        withContext(NonCancellable) {
                            diskMutex.withLock {
                                directories.staging.deleteRecursively()
                                stagingInUse = false
                            }
                        }
                    }
                }.onFailure { if (it is CancellationException) throw it }
            }
        }

    /** Must hold diskMutex. Never remove the backup until a verified current install is visible. */
    private fun recover(): List<String> {
        val target = installDir()
        if (!target.exists() && pkg.verified(directories.previous)) {
            check(directories.previous.renameTo(target)) { "failed to restore the previous install" }
        }
        val problems = PolishModelManifest.validateNemoTransducerShape(pkg) +
            PolishModelManifest.verifyInstalled(target, pkg)
        if (problems.isEmpty()) {
            directories.previous.deleteRecursively()
            if (!stagingInUse) directories.staging.deleteRecursively()
        }
        return problems
    }

    /** Staging was verified before this call. Keep a verified backup through both renames. */
    private fun publish() {
        val target = installDir()
        target.parentFile?.mkdirs()
        if (target.exists()) {
            if (pkg.verified(target)) {
                directories.previous.deleteRecursively()
                check(target.renameTo(directories.previous)) { "failed to set the previous install aside" }
            } else {
                // An invalid current must never overwrite a recoverable verified backup.
                check(target.deleteRecursively()) { "failed to remove the invalid install" }
            }
        }
        if (!directories.staging.renameTo(target)) {
            if (pkg.verified(directories.previous)) directories.previous.renameTo(target)
            error("failed to publish the staged package")
        }
        directories.previous.deleteRecursively()
    }

    /** Removes the Polish ASR files after the coordinator has closed their native users. The
     *  shared VAD and every Russian file are left alone. */
    suspend fun delete() {
        coordinator.withAssetsReleased(VoiceLanguage.PL) {
            diskMutex.withLock {
                recover()
                installDir().deleteRecursively()
                directories.staging.deleteRecursively()
                directories.previous.deleteRecursively()
            }
        }
    }

    private companion object {
        /** 10% headroom on top of the package size for filesystem overhead. */
        const val HEADROOM_DIVISOR = 10L
        const val VAD_RESERVE_BYTES = 8L * 1024 * 1024
        /** Share of the combined progress spent on the ASR files; the rest is the shared VAD. */
        const val ASR_WEIGHT = 95
    }
}

private class PolishInstallDirectories(filesDir: File, id: String) {
    private val root = File(filesDir, "asr/pl")
    val install = File(root, id)
    val staging = File(root, ".staging-$id")
    val previous = File(root, ".previous-$id")
}

private class PolishPackageDownload(private val http: OkHttpClient) {
    suspend fun fetch(d: PolishDownload, dest: File, onBytes: (Long) -> Unit) {
        http.newCall(Request.Builder().url(d.url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            check(resp.request.url.isHttps) { "download ended on a non-https url" }
            val body = resp.body ?: error("empty response body")
            val declared = body.contentLength()
            check(declared < 0 || declared == d.sizeBytes) { "size $declared != ${d.sizeBytes} for ${d.url}" }
            dest.parentFile?.mkdirs()
            val written = copy(d, dest, onBytes, body)
            check(written == d.sizeBytes) { "incomplete download: $written of ${d.sizeBytes} bytes for ${d.url}" }
        }
    }
    private suspend fun copy(d: PolishDownload, dest: File, onBytes: (Long) -> Unit, body: okhttp3.ResponseBody): Long {
        var written = 0L
        body.byteStream().use { input ->
            dest.outputStream().use { out ->
                written = transfer(d, input, out, onBytes)
                // fsync before the rename-publish: the head unit loses power with the car, and a
                // rename whose data never reached flash survives as a full-size file of garbage.
                out.fd.sync()
            }
        }
        return written
    }
    private suspend fun transfer(
        d: PolishDownload,
        input: java.io.InputStream,
        out: java.io.OutputStream,
        onBytes: (Long) -> Unit,
    ): Long {
        var written = 0L
        val buf = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            written += n
            check(written <= d.sizeBytes) { "download is larger than the pinned size for ${d.url}" }
            out.write(buf, 0, n)
            onBytes(written)
        }
        return written
    }

}

private fun PolishModelPackage.hasPinnedSizes(dir: File) = files.all { f ->
    File(dir, f.relativePath).let { it.isFile && it.length() == f.sizeBytes }
}

private fun PolishModelPackage.verified(dir: File): Boolean = dir.isDirectory &&
    PolishModelManifest.validateNemoTransducerShape(this).isEmpty() &&
    PolishModelManifest.verifyInstalled(dir, this).isEmpty()
