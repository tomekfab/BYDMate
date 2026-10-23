package com.bydmate.app.navdata

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import com.bydmate.app.media.MediaSessionListenerService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavCaptureNotificationIntegrationTest {
    private val records = mutableListOf<NavCaptureRecord>()

    private fun installBackend(recording: Boolean = true) {
        NavCaptureBridge.replaceForTest(object : NavCaptureLifecycleBackend {
            override fun snapshot() = NavCaptureSnapshot(recording = recording, packageName = "com.waze")
            override fun notification(record: NavCaptureRecord) { records += record }
            override fun accessibility(record: NavCaptureRecord) = Unit
            override fun captureActivityIntent(context: Context) = null
        })
    }

    @After
    fun tearDown() = NavCaptureBridge.resetForTest()

    @Test
    fun `active Waze capture does not read Maps or unrelated notification content`() {
        installBackend()
        val service = MediaSessionListenerService()
        for (pkg in listOf("com.google.android.apps.maps", "com.spotify.music")) {
            val notification = mockk<StatusBarNotification>(relaxed = true)
            every { notification.packageName } returns pkg

            service.onNotificationPosted(notification)
            service.onNotificationRemoved(notification)

            verify(exactly = 0) { notification.notification }
        }
        assertEquals(emptyList<NavCaptureRecord>(), records)
    }

    @Test
    fun `stopped capture with a retained selection does not read notification content`() {
        installBackend(recording = false)
        val notification = mockk<StatusBarNotification>(relaxed = true)
        every { notification.packageName } returns "com.waze"
        val service = MediaSessionListenerService()

        service.onNotificationPosted(notification)
        service.onNotificationRemoved(notification)

        verify(exactly = 0) { notification.notification }
    }

    @Test
    fun `selected active package captures posts and removals without framework objects`() {
        installBackend()
        val content = Notification().apply {
            extras.putString(Notification.EXTRA_TITLE, "Private street")
            extras.putInt("step", 2)
        }
        val notification = mockk<StatusBarNotification>(relaxed = true)
        every { notification.packageName } returns "com.waze"
        every { notification.notification } returns content
        every { notification.id } returns 7
        val service = MediaSessionListenerService()

        service.onNotificationPosted(notification)
        service.onNotificationRemoved(notification)

        assertEquals(listOf("posted", "removed"), records.map { it.kind })
        assertEquals(listOf("Private street", "Private street"), records.map { it.title })
        assertEquals(listOf("2", "2"), records.map { it.extras["step"] })
    }
}
