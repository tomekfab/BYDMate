package com.bydmate.app.navdata

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [NavGuidanceHub.Snapshot.showsGuidance]: what the output may draw. */
class NavGuidanceHubWazeOutputTest {
    private val t0 = 1_000_000L

    @Before fun setUp() = NavGuidanceHub.reset()
    @After fun tearDown() = NavGuidanceHub.reset()

    @Test fun `an inactive snapshot shows nothing`() {
        assertFalse(NavGuidanceHub.Snapshot().showsGuidance())
        assertFalse(NavGuidanceHub.snapshot(t0).showsGuidance())
    }

    @Test fun `an active Yandex snapshot with no maneuver and no distance but a road still shows, as before`() {
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 0, distanceMeters = 0, road = "Lenina"), NavGuidanceHub.Source.A11Y, t0)
        val s = NavGuidanceHub.snapshot(t0)
        assertTrue(s.active)
        assertTrue(s.owner == NavAppId.YANDEX)
        assertTrue(s.showsGuidance())
    }

    @Test fun `a Waze snapshot with a distance shows`() {
        NavGuidanceHub.publish(NavAppId.WAZE, NavRead(distanceMeters = 300, road = "S0"), t0)
        assertTrue(NavGuidanceHub.snapshot(t0).showsGuidance())
        assertTrue(NavGuidanceHub.snapshot(t0 + 10_000).showsGuidance())
    }

    @Test fun `a Waze snapshot after the 10 s detail expiry does not show though the route is active`() {
        NavGuidanceHub.publish(NavAppId.WAZE, NavRead(distanceMeters = 300, road = "S0"), t0)
        val s = NavGuidanceHub.snapshot(t0 + 10_001)
        assertTrue(s.active)
        assertFalse(s.showsGuidance())
        assertTrue(NavGuidanceHub.snapshot(t0 + 30_000).active)
        assertFalse(NavGuidanceHub.snapshot(t0 + 30_001).active)
    }

    @Test fun `Google Maps keeps its meaning, a Maps snapshot with a distance shows`() {
        NavGuidanceHub.publish(NavAppId.GOOGLE_MAPS, NavRead(distanceMeters = 300), t0)
        assertTrue(NavGuidanceHub.snapshot(t0).showsGuidance())
    }
}
