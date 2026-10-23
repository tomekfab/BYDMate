package com.bydmate.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.diagnostics.DiagnosticLogCapture
import com.bydmate.app.diagnostics.DiagnosticUploadScheduler
import com.bydmate.app.ui.settings.RemoteDiagnosticsSettings
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TrackingServiceRemoteDiagnosticsTest {
    @get:org.junit.Rule val trace = com.bydmate.app.diagnostics.TraceRecorder()

    @Test fun manualSendWhileOffSnapshotsOnlyClosedSafeJournalWithoutStartingCapture() = kotlinx.coroutines.runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("step3-manual").toFile()
        val queue = com.bydmate.app.diagnostics.DiagnosticUploadQueue(directory)
        val realCapture = DiagnosticLogCapture(context, queue, scheduler)
        try {
            com.bydmate.app.diagnostics.Trace.event(com.bydmate.app.diagnostics.TraceArea.VOICE, "session-stop", "reason" to "stop")
            com.bydmate.app.diagnostics.Trace.event(com.bydmate.app.diagnostics.TraceArea.VOICE, "heard", "d" to "PRIVATE_TRANSCRIPT")
            trace.events()
            realCapture.sendNow()
            kotlinx.coroutines.withTimeout(5000) {
                while (queue.bodies().isEmpty()) kotlinx.coroutines.delay(10)
            }
            val bytes = queue.bodies().single()
            org.junit.Assert.assertTrue(com.bydmate.app.diagnostics.RemoteDiagnosticEventParser.validBatch(bytes))
            org.junit.Assert.assertFalse(bytes.toString(Charsets.UTF_8).contains("PRIVATE_TRANSCRIPT"))
            val body = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
            org.junit.Assert.assertEquals(0, body.getJSONObject("logcat").getJSONArray("entries").length())
            org.junit.Assert.assertEquals(1, body.getJSONArray("events").length())
            com.bydmate.app.diagnostics.DiagnosticEmitters.lifecycle("listening")
            kotlinx.coroutines.delay(100)
            realCapture.boundary()
            kotlinx.coroutines.delay(100)
            org.junit.Assert.assertEquals(1, queue.bodies().size)
        } finally { realCapture.stop(); directory.deleteRecursively() }
    }

    @Test fun realServiceStopDrainsSafeEventsWithoutEmptyBatch() = kotlinx.coroutines.runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("step3-stop").toFile()
        val queue = com.bydmate.app.diagnostics.DiagnosticUploadQueue(directory)
        val realCapture = DiagnosticLogCapture(context, queue, scheduler)
        val control = RemoteDiagnosticsServiceControl(context, realCapture, scheduler)
        try {
            enabled(true); control.start()
            com.bydmate.app.diagnostics.Trace.event(com.bydmate.app.diagnostics.TraceArea.VOICE, "session-stop", "reason" to "stop")
            trace.events()
            control.stop()
            kotlinx.coroutines.withTimeout(5000) {
                while (queue.bodies().isEmpty()) kotlinx.coroutines.delay(10)
            }
            org.junit.Assert.assertTrue(queue.bodies().all { com.bydmate.app.diagnostics.RemoteDiagnosticEventParser.validBatch(it) })
        } finally { control.stop(); directory.deleteRecursively() }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val capture = mockk<DiagnosticLogCapture>(relaxed = true)
    private val scheduler = mockk<DiagnosticUploadScheduler>(relaxed = true)
    private val prefs get() = context.getSharedPreferences(RemoteDiagnosticsSettings.PREFS, 0)
    @Before fun setup() { prefs.edit().clear().commit() }
    private fun enabled(value: Boolean) {
        prefs.edit().putBoolean(RemoteDiagnosticsSettings.AUTOMATIC, value).commit()
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun serviceStartRecoversWithoutStartingCaptureWhenDisabled() {
        val control = RemoteDiagnosticsServiceControl(context, capture, scheduler)
        control.start(); control.stop()
        verify(exactly = 1) { scheduler.recover() }
        verify(exactly = 0) { capture.start() }
    }
    @Test fun toggleStartsAndStopsLiveCaptureAndServiceStopDetachesListener() {
        val control = RemoteDiagnosticsServiceControl(context, capture, scheduler)
        control.start(); enabled(true); enabled(false); enabled(true); control.stop(); enabled(false); enabled(true)
        verifyOrder { capture.start(); capture.stop(); capture.start(); capture.stop() }
        verify(exactly = 2) { capture.start() }
    }
    @Test fun serviceStopUsesSafeCaptureDrainWithoutStartingAnotherCapture() {
        enabled(true)
        val control = RemoteDiagnosticsServiceControl(context, capture, scheduler)
        control.start(); control.stop()
        verifyOrder { capture.start(); capture.boundary(); capture.stop() }
        verify(exactly = 1) { capture.start() }
    }
}
