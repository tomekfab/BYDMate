package com.bydmate.app.navdata

import com.bydmate.app.diagnostics.TraceRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Sticky first-live-owner: a live route keeps the HUD against another app's reads. Liveness is
 * [NavGuidanceHub.Snapshot.lastUpdateMs] against the owner's timeout (Yandex 90 s, others 30 s,
 * live at exactly the timeout). The tests never call `snapshot()` between steps - it would expire
 * the owner and hide the very case under test (`active` stays true after silence) - they read the
 * stored snapshot through [peek] instead.
 */
class NavGuidanceHubStickyOwnerTest {

    @get:Rule val trace = TraceRecorder()

    @Before fun before() = NavGuidanceHub.reset()

    @After fun after() = NavGuidanceHub.reset()

    private val a11y = NavGuidanceHub.Source.A11Y

    /** The stored snapshot as it is: no expiry applied, nothing written. */
    private fun peek(): NavGuidanceHub.Snapshot {
        val field = NavGuidanceHub::class.java.getDeclaredField("current")
        field.isAccessible = true
        return field.get(null) as NavGuidanceHub.Snapshot
    }

    private fun hud() = trace.events().filter { it.startsWith("hud") }.map { it.substringAfter("hud").trim() }

    private fun conflicts() = hud().filter { it.startsWith("owner-conflict") }

    private fun ownerChanges() = hud().filter { it.startsWith("guidance-owner-change") }

    private fun publish(app: NavAppId, read: NavRead, nowMs: Long) = NavGuidanceHub.publishForTest(app, read, nowMs)

    private val yandexRead = NavRead(maneuverGaode = 2, distanceMeters = 120, road = "Old road",
        etaSeconds = 600, totalDistMeters = 4_000, speedLimit = 70)
    private val wazeRead = NavRead(maneuverGaode = 3, distanceMeters = 80)
    private val wazeFullRead = NavRead(maneuverGaode = 3, distanceMeters = 200, road = "Waze road",
        etaSeconds = 900, totalDistMeters = 9_000, speedLimit = 50)

    @Test fun `a live Yandex route is not preempted by a Waze read`() {
        publish(NavAppId.YANDEX, yandexRead, 1_000)
        val before = peek()
        assertEquals(NavAppId.YANDEX, before.owner)

        publish(NavAppId.WAZE, wazeRead, 60_000)

        val after = peek()
        assertEquals(NavAppId.YANDEX, after.owner)
        assertEquals(before, after)
        assertEquals(1_000L, after.lastUpdateMs)
        assertEquals(1L, NavGuidanceHub.foreignReadsDropped)
    }

    @Test fun `the Yandex boundary is live at ninety seconds and expired a millisecond later`() {
        publish(NavAppId.YANDEX, yandexRead, 1_000)
        assertEquals(NavAppId.YANDEX, peek().owner)

        publish(NavAppId.WAZE, wazeRead, 1_000 + NavGuidanceHub.ACTIVE_TIMEOUT_MS)
        assertEquals(NavAppId.YANDEX, peek().owner)
        assertEquals(1L, NavGuidanceHub.foreignReadsDropped)
        assertEquals(1_000L, peek().lastUpdateMs)

        publish(NavAppId.WAZE, wazeRead, 1_000 + NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1)
        val after = peek()
        assertEquals(NavAppId.WAZE, after.owner)
        assertEquals(3, after.maneuverGaode)
        assertEquals(80, after.distanceMeters)
        assertEquals("", after.road)
        assertEquals(0, after.etaSeconds)
        assertEquals(0, after.totalDistMeters)
        assertEquals(0, after.speedLimit)
        assertEquals(91_001L, after.lastUpdateMs)
        assertEquals(1L, NavGuidanceHub.foreignReadsDropped)
        assertEquals(1, ownerChanges().size)
        assertTrue(ownerChanges().single(), ownerChanges().single().startsWith("guidance-owner-change from=YANDEX to=WAZE"))
    }

    @Test fun `a live Waze route is not preempted by a Yandex a11y read`() {
        publish(NavAppId.WAZE, wazeFullRead, 1_000)
        val before = peek()
        val summary = NavGuidanceHub.routeSummary(10_000)
        assertEquals(NavAppId.WAZE, before.owner)

        NavGuidanceHub.update(NavGuidance(maneuverGaode = 4, distanceMeters = 300, road = "Yandex road"), a11y, 10_000)

        assertEquals(NavAppId.WAZE, peek().owner)
        assertEquals(before, peek())
        assertEquals(summary, NavGuidanceHub.routeSummary(10_000))
        assertEquals(1L, NavGuidanceHub.foreignReadsDropped)
    }

    @Test fun `a live Waze route ignores Yandex notifications and an expired one does not block them`() {
        publish(NavAppId.WAZE, wazeFullRead, 1_000)
        val before = peek()
        val summary = NavGuidanceHub.routeSummary(10_000)
        assertEquals(NavAppId.WAZE, before.owner)

        NavGuidanceHub.updateFromNotification(
            NavGuidanceHub.RichUpdate(maneuverGaode = 5, distanceMeters = 250, road = "Yandex road"), 10_000)

        assertEquals(NavAppId.WAZE, peek().owner)
        assertEquals(before, peek())
        assertEquals(summary, NavGuidanceHub.routeSummary(10_000))
        assertEquals(1L, NavGuidanceHub.foreignReadsDropped)

        // Empty, road-only and camera-only posts are silent no-ops: no count, no state, no counters.
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(), 10_500)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(road = "Only road"), 10_600)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(cameraAlert = "camera"), 10_700)
        assertEquals(before, peek())
        assertEquals(summary, NavGuidanceHub.routeSummary(10_000))
        assertEquals(1L, NavGuidanceHub.foreignReadsDropped)

        // Waze last read at 1,000: still `active` at 31,001 (no snapshot() ran) but no longer live.
        assertTrue(NavGuidanceHub.isActiveNow())
        assertEquals(NavAppId.WAZE, peek().owner)
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(maneuverGaode = 5, distanceMeters = 250), 31_001)

        val after = peek()
        assertEquals(NavAppId.YANDEX, after.owner)
        assertTrue(after.active)
        assertEquals(5, after.maneuverGaode)
        assertEquals(31_001L, after.maneuverGaodeMs)
        assertEquals(250, after.distanceMeters)
        assertEquals(31_001L, after.lastUpdateMs)
        // Nothing of the expired Waze route survives under the new owner.
        assertEquals("", after.road)
        assertEquals(0, after.etaSeconds)
        assertEquals(0, after.totalDistMeters)
        assertEquals(0, after.speedLimit)
        assertEquals(0L, after.speedLimitMs)
        assertEquals("", after.cameraAlert)
        assertEquals(0, after.cameraDistanceMeters)
        assertNull(after.cameraIconPng)
        assertNull(after.maneuverPng)
        assertEquals(1L, NavGuidanceHub.foreignReadsDropped)
        assertEquals(1, ownerChanges().size)
        assertTrue(ownerChanges().single(), ownerChanges().single().startsWith("guidance-owner-change from=WAZE to=YANDEX"))
        val fresh = NavGuidanceHub.routeSummary(31_001)
        assertTrue(fresh, fresh.contains(" dur_s=0 ") && fresh.contains(" notif_rich=1 ") && fresh.contains(" notif_empty=0 "))
    }

    @Test fun `an expired Waze route is taken over by a Yandex a11y read as a whole`() {
        publish(NavAppId.WAZE, wazeFullRead, 1_000)
        assertEquals(NavAppId.WAZE, peek().owner)

        NavGuidanceHub.update(NavGuidance(maneuverGaode = 4, distanceMeters = 150), a11y, 31_001)

        val after = peek()
        assertEquals(NavAppId.YANDEX, after.owner)
        assertEquals(4, after.maneuverGaode)
        assertEquals(150, after.distanceMeters)
        assertEquals("", after.road)
        assertEquals(0, after.etaSeconds)
        assertEquals(0, after.totalDistMeters)
        assertEquals(0, after.speedLimit)
        assertEquals(0L, NavGuidanceHub.foreignReadsDropped)
        assertEquals(1, ownerChanges().size)
    }

    @Test fun `a live Google Maps route is not preempted by Waze and the thirty second boundary holds`() {
        publish(NavAppId.GOOGLE_MAPS, wazeFullRead, 1_000)
        val before = peek()
        assertEquals(NavAppId.GOOGLE_MAPS, before.owner)

        publish(NavAppId.WAZE, wazeRead, 20_000)
        assertEquals(before, peek())
        assertEquals(1L, NavGuidanceHub.foreignReadsDropped)

        publish(NavAppId.WAZE, wazeRead, 31_000)
        assertEquals(NavAppId.GOOGLE_MAPS, peek().owner)
        assertEquals(2L, NavGuidanceHub.foreignReadsDropped)

        publish(NavAppId.WAZE, wazeRead, 31_001)
        val after = peek()
        assertEquals(NavAppId.WAZE, after.owner)
        assertEquals("", after.road)
        assertEquals(80, after.distanceMeters)
        assertEquals(2L, NavGuidanceHub.foreignReadsDropped)
    }

    @Test fun `same owner reads refresh and empty or road-only foreign reads never count`() {
        publish(NavAppId.WAZE, wazeFullRead, 1_000)
        assertEquals(NavAppId.WAZE, peek().owner)

        publish(NavAppId.WAZE, NavRead(maneuverGaode = 2, distanceMeters = 90), 5_000)
        assertEquals(NavAppId.WAZE, peek().owner)
        assertEquals(90, peek().distanceMeters)
        assertEquals(5_000L, peek().lastUpdateMs)
        assertEquals(0L, NavGuidanceHub.foreignReadsDropped)

        val before = peek()
        publish(NavAppId.GOOGLE_MAPS, NavRead(road = "Other", etaSeconds = 100), 6_000)
        publish(NavAppId.YANDEX, NavRead(road = "Other", etaSeconds = 100), 6_500)
        NavGuidanceHub.update(NavGuidance(road = "Only road"), a11y, 7_000)
        assertEquals(before, peek())
        assertEquals(0L, NavGuidanceHub.foreignReadsDropped)
        assertTrue(conflicts().isEmpty())
    }

    @Test fun `an expired owner is reset by the snapshot and the next app starts a normal route`() {
        publish(NavAppId.WAZE, wazeRead, 1_000)
        assertEquals(NavAppId.WAZE, peek().owner)
        assertFalse(NavGuidanceHub.snapshot(1_000 + 30_001).active)
        assertEquals(NavAppId.NONE, peek().owner)

        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 120, road = "Main"), a11y, 32_000)

        assertEquals(NavAppId.YANDEX, peek().owner)
        assertEquals("Main", peek().road)
        assertEquals(0L, NavGuidanceHub.foreignReadsDropped)
        assertTrue(hud().first().startsWith("guidance-on"))
    }

    @Test fun `one conflict trace is written per owner stint however many reads are dropped`() {
        publish(NavAppId.YANDEX, yandexRead, 1_000)
        assertEquals(NavAppId.YANDEX, peek().owner)

        publish(NavAppId.WAZE, wazeRead, 2_000)
        publish(NavAppId.WAZE, wazeRead, 3_000)
        publish(NavAppId.GOOGLE_MAPS, wazeRead, 4_000)
        assertEquals(3L, NavGuidanceHub.foreignReadsDropped)
        assertEquals(1, conflicts().size)
        assertTrue(conflicts().single(), conflicts().single().startsWith("owner-conflict owner=YANDEX dropped=WAZE"))

        // The Yandex route lapses; Waze starts a stint of its own and meets a conflict of its own.
        publish(NavAppId.WAZE, wazeRead, 100_000)
        assertEquals(NavAppId.WAZE, peek().owner)
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 120), a11y, 110_000)
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 110), a11y, 111_000)

        assertEquals(NavAppId.WAZE, peek().owner)
        assertEquals(5L, NavGuidanceHub.foreignReadsDropped)
        assertEquals(2, conflicts().size)
        assertTrue(conflicts().last(), conflicts().last().startsWith("owner-conflict owner=WAZE dropped=YANDEX"))
    }

    @Test fun `reset clears the drop count and the conflict stint`() {
        publish(NavAppId.YANDEX, yandexRead, 1_000)
        publish(NavAppId.WAZE, wazeRead, 2_000)
        assertEquals(1L, NavGuidanceHub.foreignReadsDropped)

        NavGuidanceHub.reset()
        assertEquals(0L, NavGuidanceHub.foreignReadsDropped)

        publish(NavAppId.YANDEX, yandexRead, 3_000)
        publish(NavAppId.WAZE, wazeRead, 4_000)
        assertEquals(1L, NavGuidanceHub.foreignReadsDropped)
        assertEquals(2, conflicts().size)
    }

    // A foreign route that ended (silence, end, notification grace) leaves nothing for the next
    // owner's partial read to merge into.

    private val foreignApps = listOf(NavAppId.WAZE, NavAppId.GOOGLE_MAPS)

    private fun assertNoRouteFields(s: NavGuidanceHub.Snapshot) {
        assertFalse(s.active)
        assertEquals(NavAppId.NONE, s.owner)
        assertEquals(0, s.maneuverGaode)
        assertEquals(0L, s.maneuverGaodeMs)
        assertEquals(0, s.distanceMeters)
        assertEquals("", s.road)
        assertEquals(0, s.etaSeconds)
        assertEquals(0, s.totalDistMeters)
        assertEquals(0, s.speedLimit)
        assertEquals(0L, s.speedLimitMs)
        assertEquals("", s.cameraAlert)
        assertEquals(0, s.cameraDistanceMeters)
        assertNull(s.cameraIconPng)
        assertNull(s.maneuverPng)
        assertEquals("", s.maneuverSource)
        assertEquals("", s.maneuverRaw)
    }

    /** The new Yandex route holds only what its own partial read carried. */
    private fun assertOnlyYandexManeuver(s: NavGuidanceHub.Snapshot, maneuver: Int, readMs: Long) {
        assertTrue(s.active)
        assertEquals(NavAppId.YANDEX, s.owner)
        assertEquals(maneuver, s.maneuverGaode)
        assertEquals(readMs, s.maneuverGaodeMs)
        assertEquals(readMs, s.lastUpdateMs)
        assertEquals(0, s.distanceMeters)
        assertEquals("", s.road)
        assertEquals(0, s.etaSeconds)
        assertEquals(0, s.totalDistMeters)
        assertEquals(0, s.speedLimit)
        assertEquals(0L, s.speedLimitMs)
        assertEquals("", s.cameraAlert)
        assertNull(s.cameraIconPng)
        assertNull(s.maneuverPng)
    }

    /** Only the lines the new route wrote: those after [boundary], the old route's lines excluded. */
    private fun assertNewRouteTrace(boundary: Int, source: String) {
        val lines = hud().drop(boundary)
        assertEquals(lines.toString(), 1, lines.size)
        assertTrue(lines.single(), lines.single().startsWith("guidance-on src=$source"))
        assertTrue(ownerChanges().isEmpty())
        assertTrue(conflicts().isEmpty())
        assertEquals(0L, NavGuidanceHub.foreignReadsDropped)
    }

    @Test fun `a foreign route expired by the snapshot leaves no field for a Yandex a11y start`() {
        for (app in foreignApps) {
            NavGuidanceHub.reset()
            publish(app, NavRead(3, 200, "Foreign road", 900, 9_000, 50), 1_000)
            assertEquals(app, peek().owner)

            val expired = NavGuidanceHub.snapshot(31_001)
            assertNoRouteFields(expired)
            assertEquals("$app", 1_000L, expired.lastUpdateMs)
            assertEquals(expired, peek())
            val boundary = hud().size

            NavGuidanceHub.update(NavGuidance(maneuverGaode = 4), a11y, 32_000)

            assertOnlyYandexManeuver(peek(), 4, 32_000)
            assertNewRouteTrace(boundary, "a11y")
            assertTrue(NavGuidanceHub.routeSummary(32_000), NavGuidanceHub.routeSummary(32_000).contains(" dur_s=0 "))
        }
    }

    @Test fun `a foreign route expired by the snapshot leaves no field for a Yandex notification start`() {
        for (app in foreignApps) {
            NavGuidanceHub.reset()
            publish(app, NavRead(3, 200, "Foreign road", 900, 9_000, 50), 1_000)
            assertEquals(app, peek().owner)

            assertNoRouteFields(NavGuidanceHub.snapshot(31_001))
            assertEquals(1_000L, peek().lastUpdateMs)
            val boundary = hud().size

            NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(maneuverGaode = 5), 32_000)

            assertOnlyYandexManeuver(peek(), 5, 32_000)
            assertNewRouteTrace(boundary, "notification")
            val summary = NavGuidanceHub.routeSummary(32_000)
            assertTrue(summary, summary.contains(" dur_s=0 ") && summary.contains(" notif_rich=1 "))
        }
    }

    @Test fun `an ended or removed foreign route leaves no field for the next Yandex read`() {
        for (app in foreignApps) {
            NavGuidanceHub.reset()
            publish(app, NavRead(3, 200, "Foreign road", 900, 9_000, 50), 1_000)
            assertEquals(app, peek().owner)
            NavGuidanceHub.end(app, 5_000)
            assertNoRouteFields(peek())
            assertEquals(1_000L, peek().lastUpdateMs)
            var boundary = hud().size
            NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(maneuverGaode = 5), 6_000)
            assertOnlyYandexManeuver(peek(), 5, 6_000)
            assertNewRouteTrace(boundary, "notification")

            NavGuidanceHub.reset()
            publish(app, NavRead(3, 200, "Foreign road", 900, 9_000, 50), 1_000)
            assertEquals(app, peek().owner)
            NavGuidanceHub.deactivateFromNotificationGrace(app, 31_000)
            assertNoRouteFields(peek())
            assertEquals(1_000L, peek().lastUpdateMs)
            boundary = hud().size
            NavGuidanceHub.update(NavGuidance(maneuverGaode = 4), a11y, 32_000)
            assertOnlyYandexManeuver(peek(), 4, 32_000)
            assertNewRouteTrace(boundary, "a11y")
        }
    }
}
