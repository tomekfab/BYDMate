package com.bydmate.app.navdata.waze

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@Suppress("DEPRECATION") // recycle() is the pooling contract these tests assert
@RunWith(RobolectricTestRunner::class)
class WazeNavBarReaderTest {
    private val distanceId = "com.waze:id/navBarDistance"
    private val streetId = "com.waze:id/navBarStreetLine"

    private fun textNode(text: String?): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text } returns text
        return node
    }

    private fun root(pkg: String?): AccessibilityNodeInfo {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns pkg
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        return root
    }

    @Test fun `null root reads nothing`() {
        assertNull(WazeNavBarReader.read(null))
    }

    @Test fun `other package returns null and makes no find call`() {
        val root = root("ru.yandex.yandexnavi")
        assertNull(WazeNavBarReader.read(root))
        verify(exactly = 0) { root.findAccessibilityNodeInfosByViewId(any()) }
        verify(exactly = 0) { root.recycle() }
    }

    @Test fun `both nodes are read and recycled once, the root never`() {
        val dist = textNode("530 m")
        val street = textNode("S0")
        val root = root("com.waze")
        every { root.findAccessibilityNodeInfosByViewId(distanceId) } returns listOf(dist)
        every { root.findAccessibilityNodeInfosByViewId(streetId) } returns listOf(street)
        assertEquals(WazeNavBarReader.Texts("530 m", "S0"), WazeNavBarReader.read(root))
        verify(exactly = 1) { root.findAccessibilityNodeInfosByViewId(distanceId) }
        verify(exactly = 1) { root.findAccessibilityNodeInfosByViewId(streetId) }
        verify(exactly = 1) { dist.recycle() }
        verify(exactly = 1) { street.recycle() }
        verify(exactly = 0) { root.recycle() }
    }

    @Test fun `empty lists give no texts`() {
        val root = root("com.waze")
        assertEquals(WazeNavBarReader.Texts(null, null), WazeNavBarReader.read(root))
    }

    @Test fun `first blank node is skipped and every node is recycled`() {
        val blank = textNode("  ")
        val empty = textNode(null)
        val good = textNode("12 m")
        val extra = textNode("99 m")
        val root = root("com.waze")
        every { root.findAccessibilityNodeInfosByViewId(distanceId) } returns listOf(blank, empty, good, extra)
        assertEquals(WazeNavBarReader.Texts("12 m", null), WazeNavBarReader.read(root))
        for (n in listOf(blank, empty, good, extra)) verify(exactly = 1) { n.recycle() }
    }

    @Test fun `a throwing find gives no texts`() {
        val root = root("com.waze")
        every { root.findAccessibilityNodeInfosByViewId(any()) } throws IllegalStateException("stale")
        assertEquals(WazeNavBarReader.Texts(null, null), WazeNavBarReader.read(root))
    }

    @Test fun `a throwing text read still recycles the node`() {
        val bad = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { bad.text } throws IllegalStateException("stale")
        val root = root("com.waze")
        every { root.findAccessibilityNodeInfosByViewId(distanceId) } returns listOf(bad)
        assertEquals(WazeNavBarReader.Texts(null, null), WazeNavBarReader.read(root))
        verify(exactly = 1) { bad.recycle() }
    }

    @Test fun `activeRoot keeps a Waze root unrecycled`() {
        val waze = root("com.waze")
        assertSame(waze, WazeNavBarReader.activeRoot(waze))
        verify(exactly = 0) { waze.recycle() }
        assertNotNull(WazeNavBarReader.activeRoot(waze))
    }

    @Test fun `activeRoot recycles another package once and returns null`() {
        val other = root("ru.yandex.yandexnavi")
        assertNull(WazeNavBarReader.activeRoot(other))
        verify(exactly = 1) { other.recycle() }
        assertNull(WazeNavBarReader.activeRoot(null))
    }
}
