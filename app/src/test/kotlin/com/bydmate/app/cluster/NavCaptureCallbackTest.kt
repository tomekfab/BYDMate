package com.bydmate.app.cluster

import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.bydmate.app.navdata.NavCaptureBackend
import com.bydmate.app.navdata.NavCaptureBridge
import com.bydmate.app.navdata.NavCaptureRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavCaptureCallbackTest {
    private val records = mutableListOf<NavCaptureRecord>()

    @After
    fun tearDown() = NavCaptureBridge.resetForTest()

    @Test
    fun `a11y capture runs before Yandex filtering and ignores unsupported packages`() {
        NavCaptureBridge.replaceForTest(recordingBackend())
        val service = SteeringWheelKeyService()

        service.onAccessibilityEvent(event("com.google.android.apps.maps"))
        service.onAccessibilityEvent(event("com.spotify.music"))

        assertEquals(listOf("com.google.android.apps.maps"), records.map { it.packageName })
    }

    @Test
    fun `capture failure cannot change wheel-key classification`() {
        NavCaptureBridge.replaceForTest(object : NavCaptureBackend by recordingBackend() {
            override fun accessibility(record: NavCaptureRecord) = error("broken capture")
        })
        val service = SteeringWheelKeyService()

        service.onAccessibilityEvent(event("com.waze"))
        assertTrue(isTraceableKey(KeyEvent.KEYCODE_VOLUME_UP))
    }

    private fun recordingBackend() = object : NavCaptureBackend {
        override fun notification(record: NavCaptureRecord) { records += record }
        override fun accessibility(record: NavCaptureRecord) { records += record }
        override fun captureActivityIntent(context: android.content.Context) = null
    }

    private fun event(pkg: String): AccessibilityEvent = AccessibilityEvent.obtain().apply {
        eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        packageName = pkg
        className = "TestNavigation"
    }
}
