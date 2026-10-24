package com.bydmate.app.cluster

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D2: only the ACTIVE window may be a Waze root; the window list is never read.
 *  SDK 34: `windowsOnAllDisplays` (API 30) must exist for its verify; the default SDK here is 29. */
@Suppress("DEPRECATION") // recycle() is the pooling contract these tests assert
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SteeringWheelKeyServiceWazeRootTest {
    private fun root(pkg: String): AccessibilityNodeInfo {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns pkg
        return root
    }

    @Test fun `another active app gives null, is recycled once and the window list is never read`() {
        val service = spyk(SteeringWheelKeyService())
        val other = root("ru.yandex.yandexnavi")
        every { service.rootInActiveWindow } returns other

        assertNull(service.findActiveWazeRoot())

        verify(exactly = 1) { other.recycle() }
        verify(exactly = 0) { service.windows }
        verify(exactly = 0) { service.windowsOnAllDisplays }
    }

    @Test fun `a Waze active root is returned unrecycled`() {
        val service = spyk(SteeringWheelKeyService())
        val waze = root("com.waze")
        every { service.rootInActiveWindow } returns waze

        assertSame(waze, service.findActiveWazeRoot())

        verify(exactly = 0) { waze.recycle() }
        verify(exactly = 0) { service.windows }
        verify(exactly = 0) { service.windowsOnAllDisplays }
    }

    @Test fun `no active window gives null`() {
        val service = spyk(SteeringWheelKeyService())
        every { service.rootInActiveWindow } returns null
        assertNull(service.findActiveWazeRoot())
        verify(exactly = 0) { service.windows }
        verify(exactly = 0) { service.windowsOnAllDisplays }
    }
}
