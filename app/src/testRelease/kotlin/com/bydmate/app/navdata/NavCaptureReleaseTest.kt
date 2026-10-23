package com.bydmate.app.navdata

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavCaptureReleaseTest {
    @Test
    fun `release bridge cannot open or record a capture`() {
        NavCaptureBridge.resetForTest()
        assertNull(NavCaptureBridge.captureActivityIntent(ApplicationProvider.getApplicationContext()))
        assertFalse(NavCaptureBridge.start("com.google.android.apps.maps"))
        NavCaptureNotificationCallbacks.onPosted(NavCaptureNotification(
            packageName = "com.google.android.apps.maps", id = 1, channelId = null,
            title = "route", text = "street", extras = emptyMap(),
        ))
        assertFalse(NavCaptureBridge.snapshot().recording)
        assertEquals(0, NavCaptureBridge.snapshot().eventCount)
        recordNavCaptureMarker("com.google.android.apps.maps", NavCaptureMarker.TURN)
        assertEquals(0, NavCaptureBridge.snapshot().eventCount)
    }
}
