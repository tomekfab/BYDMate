package com.bydmate.app.media

import android.service.notification.StatusBarNotification
import com.bydmate.app.navdata.NavCaptureBridge
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs in both variants: release and an idle debug recorder must never read content. */
@RunWith(RobolectricTestRunner::class)
class NavCaptureNotificationGateTest {
    @After
    fun tearDown() = NavCaptureBridge.resetForTest()

    @Test
    fun `inactive capture does not read posted or removed notification content`() {
        NavCaptureBridge.resetForTest()
        val service = MediaSessionListenerService()
        for (pkg in listOf("com.google.android.apps.maps", "com.waze", "com.spotify.music")) {
            val notification = mockk<StatusBarNotification>(relaxed = true)
            every { notification.packageName } returns pkg

            service.onNotificationPosted(notification)
            service.onNotificationRemoved(notification)

            verify(exactly = 0) { notification.notification }
        }
    }
}
