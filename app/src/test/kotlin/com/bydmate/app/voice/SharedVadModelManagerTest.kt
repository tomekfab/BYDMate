package com.bydmate.app.voice

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class SharedVadModelManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun manager(
        filesDir: File,
        cacheDir: File = filesDir,
        http: OkHttpClient,
        expectedSha256: String? = null,
    ): SharedVadModelManager {
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir
        // Only fixture success paths override the digest; corruption tests use the production pin.
        return if (expectedSha256 == null) SharedVadModelManager(context, http)
        else SharedVadModelManager(context, http, expectedSha256)
    }

    private fun fixtureSha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun response(request: okhttp3.Request, code: Int, body: ResponseBody): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body)
            .build()

    @Test
    fun `nonempty cached VAD with wrong digest is rejected and removed`() {
        val files = tmp.newFolder("corrupt-cache")
        val existing = File(files, "asr/silero_vad.onnx").apply {
            parentFile!!.mkdirs()
            writeText("corrupt nonempty model")
        }
        val vad = manager(files, http = OkHttpClient.Builder().addInterceptor {
            error("readiness must not make an HTTP request")
        }.build())

        assertFalse("nonempty bytes do not establish VAD integrity", vad.isVadReady())
        assertFalse("a rejected model must not remain installed", existing.exists())
    }

    @Test
    fun `nonempty transfer with wrong digest is rejected before publication`() = runBlocking {
        val files = tmp.newFolder("corrupt-transfer")
        val vad = manager(files, http = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 200, "corrupt nonempty model".toResponseBody())
        }.build())

        val result = vad.ensureVad()

        assertTrue("corrupt response must fail integrity verification", result.isFailure)
        assertFalse(File(vad.vadPath()).exists())
        assertFalse(File(files, "silero_vad.onnx.tmp").exists())
    }

    @Test
    fun `cached VAD is reused without HTTP and retained`() = runBlocking {
        val files = tmp.newFolder("files")
        val existing = File(files, "asr/silero_vad.onnx").apply {
            parentFile!!.mkdirs()
            writeText("known-good-vad")
        }
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            error("cached VAD must not make an HTTP request")
        }.build()
        val vad = manager(files, http = http, expectedSha256 = fixtureSha256(existing.readBytes()))

        val result = vad.ensureVad()

        assertTrue(result.isSuccess)
        assertEquals(0, calls.get())
        assertEquals("known-good-vad", existing.readText())
        assertTrue(vad.isVadReady())

        // A previous successful check must not hide a same-size replacement.
        val changed = existing.readBytes().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        existing.writeBytes(changed)
        assertFalse(vad.isVadReady())
        assertFalse(existing.exists())
    }

    @Test
    fun `simultaneous callers use one transfer and atomically publish one VAD`() = runBlocking {
        val files = tmp.newFolder("files")
        val transferStarted = CompletableDeferred<Unit>()
        val allowTransfer = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val bytes = "shared-vad".toByteArray()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            response(
                chain.request(),
                200,
                object : ResponseBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength() = bytes.size.toLong()
                    override fun source(): BufferedSource {
                        transferStarted.complete(Unit)
                        runBlocking { allowTransfer.await() }
                        return Buffer().write(bytes)
                    }
                },
            )
        }.build()
        val vad = manager(files, http = http, expectedSha256 = fixtureSha256(bytes))
        var first: Result<Unit>? = null
        var second: Result<Unit>? = null

        val firstJob = launch { first = vad.ensureVad() }
        withTimeout(5_000) { transferStarted.await() }
        val secondJob = launch { second = vad.ensureVad() }
        allowTransfer.complete(Unit)
        withTimeout(5_000) {
            firstJob.join()
            secondJob.join()
        }

        assertTrue("first failure: ${first?.exceptionOrNull()}", first?.isSuccess == true)
        assertTrue("second failure: ${second?.exceptionOrNull()}", second?.isSuccess == true)
        assertEquals(1, calls.get())
        assertEquals(bytes.toList(), File(vad.vadPath()).readBytes().toList())
        assertFalse(File(files, "silero_vad.onnx.tmp").exists())
    }

    @Test
    fun `failed and empty transfers do not publish a VAD or leave a temporary file`() = runBlocking {
        val failedFiles = tmp.newFolder("failed")
        val failed = manager(
            failedFiles,
            http = OkHttpClient.Builder().addInterceptor { chain ->
                response(chain.request(), 500, "failure".toResponseBody("text/plain".toMediaType()))
            }.build(),
        )
        assertTrue(failed.ensureVad().isFailure)
        assertFalse(File(failed.vadPath()).exists())
        assertFalse(File(failedFiles, "silero_vad.onnx.tmp").exists())

        val emptyFiles = tmp.newFolder("empty")
        val empty = manager(
            emptyFiles,
            http = OkHttpClient.Builder().addInterceptor { chain ->
                response(chain.request(), 200, ByteArray(0).toResponseBody("application/octet-stream".toMediaType()))
            }.build(),
        )
        assertTrue(empty.ensureVad().isFailure)
        assertFalse(File(empty.vadPath()).exists())
        assertFalse(File(emptyFiles, "silero_vad.onnx.tmp").exists())
    }

    @Test
    fun `cancellation propagates and cleans the temporary VAD`() = runBlocking {
        val files = tmp.newFolder("files")
        val vad = manager(
            files,
            http = OkHttpClient.Builder().addInterceptor { chain ->
                response(chain.request(), 200, "vad".toResponseBody("application/octet-stream".toMediaType()))
            }.build(),
        )

        try {
            vad.ensureVad { throw CancellationException("caller cancelled") }
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation is not converted to Result.failure.
        }

        assertFalse(File(vad.vadPath()).exists())
        assertFalse(File(files, "silero_vad.onnx.tmp").exists())
    }
}
