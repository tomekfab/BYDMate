package com.bydmate.app.navdata

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavCaptureFileSafetyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory get() = File(context.filesDir, "nav-capture")
    private val capture get() = File(directory, "capture.jsonl")
    private val output get() = File(context.cacheDir, "capture-export-test.jsonl")
    private lateinit var backend: DefaultNavCaptureBackend

    @Before
    fun setUp() {
        directory.deleteRecursively()
        output.delete()
        backend = DefaultNavCaptureBackend()
    }

    @After
    fun tearDown() {
        backend.stop()
        backend.flushForTest()
        directory.deleteRecursively()
        output.delete()
    }

    private fun recordedSession() {
        assertTrue(backend.start("com.waze", context))
        backend.notification(NavCaptureRecord.notification("com.waze", "Private street"))
        backend.flushForTest()
        assertEquals(1, backend.snapshot().eventCount)
    }

    @Test
    fun `failed deletion preserves evidence and never reports deleted`() {
        recordedSession()
        backend.stop()
        // A nonempty directory deterministically makes File.delete return false.
        assertTrue(capture.delete())
        assertTrue(capture.mkdir())
        val retained = File(capture, "private-route").apply { writeText("Private street") }

        backend.delete()

        assertTrue(retained.exists())
        assertEquals(1, backend.snapshot().eventCount)
        assertNotEquals(NavCaptureStopReason.DELETED, backend.snapshot().stopReason)
        assertEquals(NavCaptureError.DELETE_FAILED, backend.snapshot().error)
        assertFalse(backend.snapshot().recording)
        // Removing the obstruction lets the driver retry deletion.
        assertTrue(retained.delete())
        backend.delete()
        assertFalse(capture.exists())
        assertEquals(NavCaptureStopReason.DELETED, backend.snapshot().stopReason)
    }

    @Test
    fun `failed reset refuses recording and preserves prior session evidence`() {
        recordedSession()
        backend.stop()
        assertTrue(capture.delete())
        assertTrue(capture.mkdir())
        val retained = File(capture, "private-route").apply { writeText("Private street") }

        assertFalse(backend.start("com.google.android.apps.maps", context))

        assertFalse(backend.snapshot().recording)
        assertEquals(1, backend.snapshot().eventCount)
        assertEquals(NavCaptureError.RESET_FAILED, backend.snapshot().error)
        assertEquals("Private street", retained.readText())
        assertTrue(retained.delete())
        assertTrue(capture.delete())
        assertTrue(backend.start("com.google.android.apps.maps", context))
        assertEquals(0, backend.snapshot().eventCount)
    }

    @Test
    fun `active recording cannot open or overwrite an export destination`() {
        recordedSession()
        output.writeText("existing destination")

        assertFalse(backend.saveTo(context, Uri.fromFile(output)))

        assertEquals("existing destination", output.readText())
        backend.stop()
        backend.flushForTest()
        assertTrue(backend.saveTo(context, Uri.fromFile(output)))
        assertEquals(capture.readText(), output.readText())
        assertEquals(1, output.readLines().size)
        assertEquals("Private street", org.json.JSONObject(output.readLines().single()).getString("text"))
    }
}
