package com.bydmate.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

/** Rename-crash windows of the package publication: a new manager over the same directory (a restart
 *  after the crash) must restore or keep a verified install and never delete the only valid copy. */
@RunWith(RobolectricTestRunner::class)
class PolishModelManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val contents = mapOf(
        "encoder.int8.onnx" to "encoder-bytes",
        "decoder.int8.onnx" to "decoder-bytes",
        "joiner.int8.onnx" to "joiner-bytes",
        "tokens.txt" to "tokens-bytes",
    )

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private val pkg = PolishModelPackage(
        id = "test-package",
        kind = PolishModelKind.ASR,
        downloads = contents.map { (name, text) ->
            PolishDownload("https://example.invalid/$name", text.length.toLong(), sha256(text), name)
        },
        files = contents.map { (name, text) -> PolishModelFile(name, text.length.toLong(), sha256(text)) },
        requiredTrees = emptyList(),
        licenseNotes = "test",
    )

    private val root get() = File(context.filesDir, "asr/pl")
    private val current get() = File(root, pkg.id)
    private val previous get() = File(root, ".previous-${pkg.id}")
    private val staging get() = File(root, ".staging-${pkg.id}")

    @Before fun cleanDisk() {
        context.filesDir.resolve("asr").deleteRecursively()
    }

    private fun writeVerified(dir: File) {
        dir.mkdirs()
        contents.forEach { (name, text) -> File(dir, name).writeText(text) }
    }

    /** Same size as the pinned file, wrong bytes: only the hash can tell it from a good copy. */
    private fun writeCorrupt(dir: File) {
        dir.mkdirs()
        contents.forEach { (name, text) -> File(dir, name).writeText("x".repeat(text.length)) }
    }

    private fun isVerified(dir: File) = dir.isDirectory && PolishModelManifest.verifyInstalled(dir, pkg).isEmpty()

    private val passThrough = object : NativeAssetCoordinator {
        override suspend fun <T> withAssetsReleased(language: VoiceLanguage, block: suspend () -> T): T = block()
    }

    private val readyVad = object : SharedVadAsset {
        override fun vadPath() = "vad.onnx"
        override fun isVadReady() = true
        override suspend fun ensureVad(onProgress: (Int) -> Unit) = Result.success(Unit)
    }

    /** A fresh instance, as after a process restart: no in-memory state survives the crash. */
    private fun restartedManager() = PolishModelManager(
        context, OkHttpClient(), readyVad, passThrough, pkg, usableSpace = { Long.MAX_VALUE },
    )

    @Test fun `crash after current moved to previous restores the verified previous`() = runBlocking {
        // publish(): target.renameTo(previous) done, staging.renameTo(target) not yet.
        writeVerified(previous)
        writeVerified(staging)
        assertFalse(current.exists())

        val problems = restartedManager().verifyAsr()

        assertEquals(emptyList<String>(), problems)
        assertTrue("current install must be back and verified", isVerified(current))
        assertFalse("the backup is only dropped after a verified current is visible", previous.exists())
    }

    @Test fun `crash after current moved to previous with a half written staging still restores the previous`() = runBlocking {
        writeVerified(previous)
        writeCorrupt(staging)

        val problems = restartedManager().verifyAsr()

        assertEquals(emptyList<String>(), problems)
        assertTrue("the only valid copy must be restored as current", isVerified(current))
    }

    @Test fun `isReady after that crash recovers the previous too`() {
        writeVerified(previous)
        writeVerified(staging)

        assertTrue(restartedManager().isReady())
        assertTrue(isVerified(current))
    }

    @Test fun `crash after staging became current keeps current and drops the stale previous`() = runBlocking {
        // publish(): staging.renameTo(target) done, previous.deleteRecursively() not yet.
        writeVerified(current)
        writeVerified(previous)
        assertFalse(staging.exists())

        val problems = restartedManager().verifyAsr()

        assertEquals(emptyList<String>(), problems)
        assertTrue(isVerified(current))
        assertFalse(previous.exists())
    }

    @Test fun `a corrupt current never makes recovery delete the only verified previous`() = runBlocking {
        // The rename reached flash but the data did not: current is garbage, previous is the only valid copy.
        writeCorrupt(current)
        writeVerified(previous)

        val problems = restartedManager().verifyAsr()

        assertTrue("a corrupt current must be reported", problems.isNotEmpty())
        assertTrue("the only verified copy was deleted by recovery", isVerified(previous))
    }
}
