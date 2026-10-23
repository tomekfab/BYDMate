package com.bydmate.app.navdata

import com.bydmate.app.media.NaviNotificationLane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Baseline for Step 1. These are the Yandex-only fields that ownership must not change.
 * New-owner tests belong in NavSourceSelectorTest; do not widen this fixture's package set.
 */
class NavYandexCharacterizationTest {

    private class FakeExecutor : NaviNotificationLane.LaneExecutor {
        val scheduled = mutableListOf<Pair<Long, Runnable>>()
        override fun execute(task: Runnable) = task.run()
        override fun schedule(delayMs: Long, task: Runnable) { scheduled += delayMs to task }
        override fun shutdownNow() = Unit
        fun runNext() = scheduled.removeAt(0).second.run()
    }

    @Before fun reset() = NavGuidanceHub.reset()

    @Test fun `Yandex partial a11y and notification reads retain the known snapshot fields`() {
        val t0 = 1_000_000L
        NavGuidanceHub.update(
            NavGuidance(maneuverGaode = 2, distanceMeters = 300, road = "Main", etaSeconds = 600,
                totalDistMeters = 5_000, speedLimit = 50),
            NavGuidanceHub.Source.A11Y, nowMs = t0,
        )
        NavGuidanceHub.updateFromNotification(
            NavGuidanceHub.RichUpdate(distanceMeters = 250), nowMs = t0 + 11_000,
        )
        NavGuidanceHub.update(
            NavGuidance(speedLimit = 60), NavGuidanceHub.Source.A11Y, nowMs = t0 + 12_000,
        )

        val snapshot = NavGuidanceHub.snapshot(t0 + 12_000)
        assertEquals(2, snapshot.maneuverGaode)
        assertEquals(250, snapshot.distanceMeters)
        assertEquals("Main", snapshot.road)
        assertEquals(600, snapshot.etaSeconds)
        assertEquals(5_000, snapshot.totalDistMeters)
        assertEquals(60, snapshot.speedLimit)
        assertEquals(t0 + 12_000, snapshot.lastUpdateMs)
    }

    @Test fun `Yandex empty notification does not start or refresh a route`() {
        val t0 = 1_000_000L
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(), nowMs = t0)
        assertFalse(NavGuidanceHub.snapshot(t0).active)

        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 100),
            NavGuidanceHub.Source.A11Y, nowMs = t0 + 1_000)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(), nowMs = t0 + 2_000)
        assertEquals(t0 + 1_000, NavGuidanceHub.snapshot(t0 + 2_000).lastUpdateMs)
    }

    @Test fun `Yandex removal and repost preserve the current grace behavior`() {
        val t0 = 1_000_000L
        val clock = longArrayOf(t0)
        val executor = FakeExecutor()
        val lane = NaviNotificationLane(executor) { clock[0] }
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(road = "Main"), nowMs = t0)

        lane.onRemoved(Runnable { })
        lane.markGuidancePosted()
        clock[0] += NaviNotificationLane.DEACTIVATE_CHECK_MS
        executor.runNext()

        assertTrue(NavGuidanceHub.snapshot(clock[0]).active)
        assertTrue(executor.scheduled.isEmpty())
    }

    @Test fun `Yandex maneuver expires after an armed partial read while route lasts ninety seconds`() {
        val t0 = 1_000_000L
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300),
            NavGuidanceHub.Source.A11Y, nowMs = t0)
        NavGuidanceHub.update(NavGuidance(distanceMeters = 200),
            NavGuidanceHub.Source.A11Y, nowMs = t0 + 5_000)

        val afterManeuverExpiry = NavGuidanceHub.snapshot(t0 + NavGuidanceHub.MANEUVER_TIMEOUT_MS + 5_001)
        assertTrue(afterManeuverExpiry.active)
        assertEquals(0, afterManeuverExpiry.maneuverGaode)
        assertEquals(200, afterManeuverExpiry.distanceMeters)

        assertTrue(NavGuidanceHub.snapshot(t0 + 94_999).active)
        assertFalse(NavGuidanceHub.snapshot(t0 + 95_001).active)
    }
}
