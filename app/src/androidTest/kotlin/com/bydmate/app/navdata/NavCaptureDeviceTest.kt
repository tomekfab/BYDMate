package com.bydmate.app.navdata

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the app-owned seam only; it makes no claim about Maps or Waze compatibility. */
@RunWith(AndroidJUnit4::class)
class NavCaptureDeviceTest {
    @Test
    fun explicitLifecycleWritesPrivateJsonlThroughSyntheticCaptureSeam() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        NavCaptureBridge.resetForTest()
        assertTrue(NavCaptureBridge.start("com.google.android.apps.maps", context))
        NavCaptureNotificationCallbacks.onPosted(NavCaptureNotification(
            packageName = "com.google.android.apps.maps", id = 4, channelId = "navigation",
            title = "synthetic route", text = "synthetic street", extras = mapOf("distance" to 300),
        ))
        flushNavCaptureForTest()
        assertTrue(NavCaptureBridge.snapshot().eventCount > 0)
        assertTrue(NavCaptureBridge.privateCaptureFileForTest(context).readText().contains("synthetic route"))
        NavCaptureBridge.stop()
        assertFalse(NavCaptureBridge.snapshot().recording)
    }

    @Test
    fun debugActivityIsNotExported() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, NavCaptureActivity::class.java),
            0,
        )
        assertFalse(info.exported)
    }
}
