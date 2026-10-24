package com.bydmate.app.navdata.waze

import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.navdata.NavA11yFeed
import com.bydmate.app.navdata.NavAppId
import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavRead
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION", "LargeClass", "TooManyFunctions") // recycle() is the pooling contract asserted here
@RunWith(RobolectricTestRunner::class)
class WazeHudFeedTest {
    private val t0 = 1_000_000L
    private val g1 = WazeParse.Guidance(530, "S0")
    private val g2 = WazeParse.Guidance(500, "S0")

    @Before fun setUp() {
        NavGuidanceHub.reset()
        WazeHudFeed.resetForTest()
    }

    @After fun tearDown() {
        WazeHudFeed.setEnabled(false)
        WazeHudFeed.resetForTest()
        NavGuidanceHub.reset()
    }

    private fun ticketOrFail(): Long = WazeHudFeed.ticket() ?: throw AssertionError("feed has no ticket")

    private fun ticketOn(): Long {
        WazeHudFeed.setEnabled(true)
        return ticketOrFail()
    }

    private fun wazeRoot(distance: String?, street: String? = "S0", pkg: String = "com.waze"): AccessibilityNodeInfo {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns pkg
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        distance?.let { every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance") } returns listOf(textNode(it)) }
        street?.let { every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarStreetLine") } returns listOf(textNode(it)) }
        return root
    }

    private fun textNode(text: String): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text } returns text
        return node
    }

    private fun event(pkg: String, type: Int = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED): AccessibilityEvent {
        val e = mockk<AccessibilityEvent>(relaxed = true)
        every { e.packageName } returns pkg
        every { e.eventType } returns type
        return e
    }

    // 1. Unchanged reads are not freshness evidence ------------------------------------------

    @Test fun `unchanged reads for 40 s renew nothing and never reacquire`() {
        val t = ticketOn()
        assertTrue(WazeHudFeed.commit(t, g1, t0))
        assertEquals(t0, NavGuidanceHub.snapshot(t0).lastUpdateMs)
        assertEquals(NavAppId.WAZE, NavGuidanceHub.snapshot(t0).owner)

        for (s in 1..9) assertFalse(WazeHudFeed.commit(t, g1, t0 + s * 1000L))
        assertTrue(NavGuidanceHub.snapshot(t0 + 10_000).showsGuidance())
        assertEquals(530, NavGuidanceHub.snapshot(t0 + 10_000).distanceMeters)
        assertFalse(NavGuidanceHub.snapshot(t0 + 10_001).showsGuidance())

        for (s in 10..12) assertFalse(WazeHudFeed.commit(t, g1, t0 + s * 1000L))
        assertFalse(NavGuidanceHub.snapshot(t0 + 12_000).showsGuidance())
        assertTrue(NavGuidanceHub.snapshot(t0 + 30_000).active)
        assertFalse(NavGuidanceHub.snapshot(t0 + 30_001).active)

        for (s in 31..40) {
            assertFalse(WazeHudFeed.commit(t, g1, t0 + s * 1000L))
            assertFalse(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))
        }
        assertEquals(t0, NavGuidanceHub.snapshot(t0 + 40_000).lastUpdateMs)
        assertFalse(NavGuidanceHub.snapshot(t0 + 40_000).active)
    }

    @Test fun `a changed observation after expiry starts a new route and last survived the expiry`() {
        val t = ticketOn()
        assertTrue(WazeHudFeed.commit(t, g1, t0))
        assertFalse(NavGuidanceHub.snapshot(t0 + 30_001).active)
        assertFalse(WazeHudFeed.commit(t, g1, t0 + 31_000))   // last survived expiry
        assertFalse(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))

        assertTrue(WazeHudFeed.commit(t, g2, t0 + 32_000))
        val s = NavGuidanceHub.snapshot(t0 + 32_000)
        assertTrue(s.active)
        assertEquals(NavAppId.WAZE, s.owner)
        assertEquals(500, s.distanceMeters)
        assertEquals(t0 + 32_000, s.lastUpdateMs)
    }

    @Test fun `toggle off and on with the same screen does not republish, a changed value does`() {
        val t = ticketOn()
        assertTrue(WazeHudFeed.commit(t, g1, t0))
        WazeHudFeed.setEnabled(false)
        WazeHudFeed.setEnabled(true)
        val t2 = ticketOrFail()
        assertFalse(WazeHudFeed.commit(t2, g1, t0 + 1000))
        assertFalse(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))
        assertTrue(WazeHudFeed.commit(t2, g2, t0 + 2000))
        assertEquals(500, NavGuidanceHub.snapshot(t0 + 2000).distanceMeters)
    }

    // 2. Attempts are not evidence -----------------------------------------------------------

    @Test fun `timer and event attempts raise lastAttemptMs only, zero and format reads change nothing`() {
        val t = ticketOn()
        assertTrue(WazeHudFeed.commit(t, g1, t0))
        WazeHudFeed.clock = { t0 + 3000 }

        WazeHudFeed.rootProvider = { wazeRoot("0 m") }
        WazeHudFeed.onTimer(t, t0 + 2000)
        assertEquals(t0 + 2000, WazeHudFeed.lastAttemptMs)
        assertEquals(t0, NavGuidanceHub.snapshot(t0 + 2000).lastUpdateMs)

        WazeHudFeed.rootProvider = { wazeRoot("5.6km") }
        WazeHudFeed.onEvent(event("com.waze"))
        assertEquals(t0 + 3000, WazeHudFeed.lastAttemptMs)
        assertEquals(t0, NavGuidanceHub.snapshot(t0 + 3000).lastUpdateMs)
        assertEquals(530, NavGuidanceHub.snapshot(t0 + 3000).distanceMeters)

        // An identical valid read is an attempt too; it neither renews nor republishes.
        WazeHudFeed.rootProvider = { wazeRoot("530 m", "S0") }
        WazeHudFeed.onTimer(t, t0 + 4000)
        assertEquals(t0 + 4000, WazeHudFeed.lastAttemptMs)
        assertEquals(t0, NavGuidanceHub.snapshot(t0 + 4000).lastUpdateMs)

        // last was never replaced by the ignored reads: g1 is still "unchanged", g2 is a change.
        assertFalse(WazeHudFeed.commit(t, g1, t0 + 5000))
        assertTrue(WazeHudFeed.commit(t, g2, t0 + 6000))
    }

    @Test fun `a changed read through the event path reaches the hub with the real distance and street`() {
        ticketOn()
        WazeHudFeed.clock = { t0 }
        WazeHudFeed.rootProvider = { wazeRoot("5.6 km", "S0") }
        WazeHudFeed.onEvent(event("com.waze"))
        val s = NavGuidanceHub.snapshot(t0)
        assertEquals(NavAppId.WAZE, s.owner)
        assertEquals(5600, s.distanceMeters)
        assertEquals("S0", s.road)
        assertEquals(0, s.maneuverGaode)
    }

    // 3. Active root only --------------------------------------------------------------------

    @Test fun `no active Waze root means no hub call from event or timer`() {
        val t = ticketOn()
        val calls = AtomicInteger()
        WazeHudFeed.rootProvider = { calls.incrementAndGet(); null }
        WazeHudFeed.clock = { t0 }

        WazeHudFeed.onEvent(event("com.waze"))
        WazeHudFeed.onTimer(t, t0 + 2000)

        assertEquals(2, calls.get())
        assertFalse(NavGuidanceHub.isActiveNow())
        assertEquals(0L, NavGuidanceHub.foreignReadsDropped)
    }

    @Test fun `an event from Yandex is ignored and the root is never fetched`() {
        ticketOn()
        val calls = AtomicInteger()
        WazeHudFeed.rootProvider = { calls.incrementAndGet(); wazeRoot("530 m") }
        WazeHudFeed.clock = { t0 }
        WazeHudFeed.onEvent(event("ru.yandex.yandexnavi"))
        assertEquals(0, calls.get())
        assertFalse(NavGuidanceHub.isActiveNow())
    }

    @Test fun `Yandex feed does not process a Waze event`() {
        assertFalse(NavA11yFeed.shouldProcess("com.waze", 2048, 10_000L, 0L))
    }

    // 4. Fence -------------------------------------------------------------------------------

    @Test fun `a ticket taken before disable cannot commit`() {
        val t = ticketOn()
        WazeHudFeed.setEnabled(false)
        assertFalse(WazeHudFeed.commit(t, g1, t0))
        assertFalse(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))
        assertNull(WazeHudFeed.ticket())
    }

    @Test fun `a ticket taken before off and on is rejected, a fresh one commits`() {
        val old = ticketOn()
        WazeHudFeed.setEnabled(false)
        WazeHudFeed.setEnabled(true)
        assertFalse(WazeHudFeed.commit(old, g1, t0))
        assertFalse(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))
        val fresh = ticketOrFail()
        assertTrue(fresh != old)
        assertTrue(WazeHudFeed.commit(fresh, g1, t0))
    }

    @Test fun `disable ends a live Waze route with no road left, a late commit is refused`() {
        val t = ticketOn()
        assertTrue(WazeHudFeed.commit(t, g1, t0))
        assertTrue(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))

        WazeHudFeed.setEnabled(false)

        assertFalse(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))
        val s = NavGuidanceHub.snapshot(t0 + 1)
        assertFalse(s.active)
        assertEquals("", s.road)
        assertFalse(WazeHudFeed.commit(t, g2, t0 + 2))
        assertFalse(NavGuidanceHub.isActiveNow())
    }

    @Test fun `a provider that disables the feed mid-read leaves the hub untouched`() {
        val t = ticketOn()
        WazeHudFeed.clock = { t0 }
        WazeHudFeed.rootProvider = {
            WazeHudFeed.setEnabled(false)
            wazeRoot("530 m")
        }
        WazeHudFeed.onTimer(t, t0)
        assertFalse(NavGuidanceHub.isActiveNow())
        assertEquals(NavAppId.NONE, NavGuidanceHub.snapshot(t0).owner)
    }

    @Test fun `disable returns while a reader is parked inside the provider, and the late read stays out`() {
        val t = ticketOn()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        WazeHudFeed.rootProvider = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            wazeRoot("530 m")
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reader = pool.submit { WazeHudFeed.onTimer(t, t0) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            pool.submit { WazeHudFeed.setEnabled(false) }.get(3, TimeUnit.SECONDS)   // no lock held over the foreign call
            assertFalse(WazeHudFeed.enabled)

            release.countDown()
            reader.get(5, TimeUnit.SECONDS)
            assertFalse(NavGuidanceHub.isActiveNow())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `a disabled timer runs no read, and off then on does not double the chain`() {
        val calls = AtomicInteger()
        WazeHudFeed.rootProvider = { calls.incrementAndGet(); null }
        WazeHudFeed.setEnabled(true)
        WazeHudFeed.setEnabled(false)
        shadowOf(Looper.getMainLooper()).idle(10, TimeUnit.SECONDS)
        assertEquals(0, calls.get())

        WazeHudFeed.setEnabled(true)
        shadowOf(Looper.getMainLooper()).idle(6, TimeUnit.SECONDS)
        assertEquals(3, calls.get())
    }

    @Test fun `a read racing a live Yandex route is rejected and counted, Yandex stays owner`() {
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300, road = "Y"), NavGuidanceHub.Source.A11Y, t0)
        val t = ticketOn()
        val dropped = NavGuidanceHub.foreignReadsDropped

        assertFalse(WazeHudFeed.commit(t, g1, t0 + 1000))

        assertEquals(dropped + 1, NavGuidanceHub.foreignReadsDropped)
        assertEquals(NavAppId.YANDEX, NavGuidanceHub.snapshot(t0 + 1000).owner)
    }

    // 5. shouldProcess and recycling ---------------------------------------------------------

    @Test fun `shouldProcess truth table`() {
        val content = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        val state = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        assertTrue(WazeHudFeed.shouldProcess("com.waze", content, 1500, 1000))
        assertTrue(WazeHudFeed.shouldProcess("com.waze", state, 1500, 1000))
        assertFalse(WazeHudFeed.shouldProcess("com.waze", AccessibilityEvent.TYPE_VIEW_CLICKED, 9999, 0))
        assertFalse(WazeHudFeed.shouldProcess("ru.yandex.yandexnavi", content, 9999, 0))
        assertFalse(WazeHudFeed.shouldProcess("com.waze.other", content, 9999, 0))
        assertFalse(WazeHudFeed.shouldProcess(null, content, 9999, 0))
        assertFalse(WazeHudFeed.shouldProcess("com.waze", content, 1499, 1000))
        assertTrue(WazeHudFeed.shouldProcess("com.waze", content, 1500, 1000))
    }

    @Test fun `the root is recycled once per attempt`() {
        val t = ticketOn()
        val root = wazeRoot("530 m")
        WazeHudFeed.rootProvider = { root }
        WazeHudFeed.onTimer(t, t0)
        verify(exactly = 1) { root.recycle() }

        val ignored = wazeRoot("0 m")
        WazeHudFeed.rootProvider = { ignored }
        WazeHudFeed.onTimer(t, t0 + 2000)
        verify(exactly = 1) { ignored.recycle() }
    }

    // 6. Hub admission is atomic, last moves only on acceptance -------------------------------

    @Test fun `Yandex rejection, release, same observation is accepted once and then expires normally`() {
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300, road = "Y"), NavGuidanceHub.Source.A11Y, t0)
        val t = ticketOn()
        val dropped = NavGuidanceHub.foreignReadsDropped

        assertFalse(WazeHudFeed.commit(t, g1, t0 + 1000))
        assertEquals(NavAppId.YANDEX, NavGuidanceHub.snapshot(t0 + 1000).owner)
        assertEquals(dropped + 1, NavGuidanceHub.foreignReadsDropped)
        assertFalse(WazeHudFeed.commit(t, g1, t0 + 2000))   // last was not set: still a change, still counted
        assertEquals(dropped + 2, NavGuidanceHub.foreignReadsDropped)

        NavGuidanceHub.end(NavAppId.YANDEX, t0 + 3000)
        val t1 = t0 + 4000
        assertTrue(WazeHudFeed.commit(t, g1, t1))
        val s = NavGuidanceHub.snapshot(t1)
        assertEquals(NavAppId.WAZE, s.owner)
        assertEquals(t1, s.lastUpdateMs)
        assertEquals(530, s.distanceMeters)
        assertEquals("S0", s.road)

        for (sec in 1..9) assertFalse(WazeHudFeed.commit(t, g1, t1 + sec * 1000L))
        assertEquals(t1, NavGuidanceHub.snapshot(t1 + 9000).lastUpdateMs)
        assertTrue(NavGuidanceHub.snapshot(t1 + 10_000).showsGuidance())
        assertFalse(NavGuidanceHub.snapshot(t1 + 10_001).showsGuidance())
        for (sec in 11..30) assertFalse(WazeHudFeed.commit(t, g1, t1 + sec * 1000L))
        assertTrue(NavGuidanceHub.snapshot(t1 + 30_000).active)
        assertFalse(NavGuidanceHub.snapshot(t1 + 30_001).active)
        assertFalse(WazeHudFeed.commit(t, g1, t1 + 31_000))
        assertFalse(WazeHudFeed.commit(t, g1, t1 + 32_000))
        assertEquals(NavAppId.NONE, NavGuidanceHub.snapshot(t1 + 32_000).owner)
        for (sec in 33..40) assertFalse(WazeHudFeed.commit(t, g1, t1 + sec * 1000L))
        assertEquals(t1, NavGuidanceHub.snapshot(t1 + 40_000).lastUpdateMs)

        assertTrue(WazeHudFeed.commit(t, g2, t1 + 33_000))
        assertEquals(500, NavGuidanceHub.snapshot(t1 + 33_000).distanceMeters)
    }

    @Test fun `a rejection does not overwrite a prior accepted value`() {
        val g0 = WazeParse.Guidance(900, "S9")
        val t = ticketOn()
        assertTrue(WazeHudFeed.commit(t, g0, t0))
        // g0 expired; Yandex takes over, g1 is rejected.
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300, road = "Y"), NavGuidanceHub.Source.A11Y, t0 + 40_000)
        assertFalse(WazeHudFeed.commit(t, g1, t0 + 41_000))
        assertEquals(NavAppId.YANDEX, NavGuidanceHub.snapshot(t0 + 41_000).owner)

        NavGuidanceHub.end(NavAppId.YANDEX, t0 + 42_000)
        assertTrue(WazeHudFeed.commit(t, g1, t0 + 43_000))   // g1 != g0
        assertTrue(WazeHudFeed.commit(t, g0, t0 + 44_000))   // g0 != last == g1
        assertEquals(900, NavGuidanceHub.snapshot(t0 + 44_000).distanceMeters)
    }

    @Test fun `a hub rejection for zero maneuver and zero distance leaves last unchanged`() {
        val t = ticketOn()
        assertTrue(WazeHudFeed.commit(t, g1, t0))
        assertFalse(WazeHudFeed.commit(t, WazeParse.Guidance(0, ""), t0 + 1000))
        assertFalse(WazeHudFeed.commit(t, g1, t0 + 2000))    // last is still g1
        assertEquals(t0, NavGuidanceHub.snapshot(t0 + 2000).lastUpdateMs)
        assertTrue(WazeHudFeed.commit(t, g2, t0 + 3000))
    }

    // 6(d). publishAccepted unit cases -------------------------------------------------------

    private val read = NavRead(maneuverGaode = 0, distanceMeters = 530, road = "S0")

    @Test fun `publishAccepted is true for an accepted Waze or Maps read and replaces state as publish does`() {
        for (owner in listOf(NavAppId.WAZE, NavAppId.GOOGLE_MAPS)) {
            NavGuidanceHub.reset()
            assertTrue(NavGuidanceHub.publishAccepted(owner, read, t0))
            val viaAccepted = NavGuidanceHub.snapshot(t0)
            NavGuidanceHub.reset()
            NavGuidanceHub.publish(owner, read, t0)
            assertEquals(viaAccepted, NavGuidanceHub.snapshot(t0))
            assertEquals(owner, viaAccepted.owner)
            assertEquals(530, viaAccepted.distanceMeters)
            assertEquals(t0, viaAccepted.lastUpdateMs)
        }
    }

    @Test fun `publishAccepted is false under a live foreign owner and handles state and counter as publish`() {
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300, road = "Y"), NavGuidanceHub.Source.A11Y, t0)
        val before = NavGuidanceHub.snapshot(t0 + 1000)
        val dropped = NavGuidanceHub.foreignReadsDropped

        assertFalse(NavGuidanceHub.publishAccepted(NavAppId.WAZE, read, t0 + 1000))

        assertEquals(before, NavGuidanceHub.snapshot(t0 + 1000))
        assertEquals(dropped + 1, NavGuidanceHub.foreignReadsDropped)
        // Same as publish on an identical hub.
        NavGuidanceHub.publish(NavAppId.WAZE, read, t0 + 1000)
        assertEquals(before, NavGuidanceHub.snapshot(t0 + 1000))
        assertEquals(dropped + 2, NavGuidanceHub.foreignReadsDropped)
    }

    @Test fun `publishAccepted is false for NONE, YANDEX and the zero read and touches nothing`() {
        assertFalse(NavGuidanceHub.publishAccepted(NavAppId.NONE, read, t0))
        assertFalse(NavGuidanceHub.publishAccepted(NavAppId.YANDEX, read, t0))
        assertFalse(NavGuidanceHub.publishAccepted(NavAppId.WAZE, NavRead(), t0))
        assertFalse(NavGuidanceHub.isActiveNow())
        assertEquals(0L, NavGuidanceHub.foreignReadsDropped)
        assertNotNull(NavGuidanceHub.snapshot(t0))
        assertEquals(NavAppId.NONE, NavGuidanceHub.snapshot(t0).owner)
    }
}
