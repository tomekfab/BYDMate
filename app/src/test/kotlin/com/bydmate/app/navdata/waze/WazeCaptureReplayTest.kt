package com.bydmate.app.navdata.waze

import com.bydmate.app.navdata.NavAppId
import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The recorded Waze route (sanitized: distance text and street ids only) through parser, feed and hub on an
 *  injected clock. The oracle is computed from the TSV alone: time since the last changed observation. */
class WazeCaptureReplayTest {
    private class Row(val tMs: Long, val distance: String, val street: String?)

    private val t0 = 5_000_000L
    private val rows: List<Row> by lazy {
        requireNotNull(javaClass.classLoader?.getResource("nav/waze/waze-guiding-polish.tsv")).readText().lines()
            .drop(1).filter { it.isNotBlank() }
            .map { it.split("\t") }
            .map { Row(it[0].toLong(), it[1], it[2].takeUnless { s -> s == "-" }) }
    }

    @Before fun setUp() {
        NavGuidanceHub.reset()
        WazeHudFeed.resetForTest()
    }

    @After fun tearDown() {
        WazeHudFeed.setEnabled(false)
        WazeHudFeed.resetForTest()
        NavGuidanceHub.reset()
    }

    // Keep the chronological replay and its independent oracle together for failure diagnosis.
    @Suppress("LongMethod", "NestedBlockDepth")
    @Test fun `the whole route replays with the oracle from the TSV`() {
        assertEquals(619, rows.size)
        WazeHudFeed.setEnabled(true)
        val ticket = WazeHudFeed.ticket() ?: throw AssertionError("feed has no ticket")

        var oracleLast: WazeParse.Guidance? = null
        var lastChangeT = Long.MIN_VALUE
        var changedRows = 0
        var longestGap = 0L
        var blankRows = 0
        var zeroRows = 0
        var maxActiveManeuver = 0

        for (row in rows) {
            val now = t0 + row.tMs
            if (row.street == null) blankRows++
            val parsed = WazeGuidanceParser.parse(row.distance, row.street)
            if (parsed is WazeParse.Ignore) {
                assertEquals(row.distance, "0 m", row.distance)
                assertEquals(WazeParse.Ignore(WazeIgnoreReason.ZERO), parsed)
                zeroRows++
            } else {
                val guidance = parsed as WazeParse.Guidance
                val changed = guidance != oracleLast
                assertEquals("row ${row.tMs}", changed, WazeHudFeed.commit(ticket, guidance, now))
                if (changed) {
                    changedRows++
                    if (lastChangeT != Long.MIN_VALUE) longestGap = maxOf(longestGap, now - lastChangeT)
                    lastChangeT = now
                    oracleLast = guidance
                }
            }

            val snap = NavGuidanceHub.snapshot(now)
            val sinceChange = now - lastChangeT
            assertEquals("shows at ${row.tMs}", sinceChange <= 10_000L, snap.showsGuidance())
            assertEquals("active at ${row.tMs}", sinceChange <= 30_000L, snap.active)
            if (snap.active) {
                assertEquals(NavAppId.WAZE, snap.owner)
                assertEquals(0, snap.maneuverGaode)
                maxActiveManeuver = maxOf(maxActiveManeuver, snap.maneuverGaode)
            }
            when (row.tMs) {
                0L -> {
                    assertEquals(NavAppId.WAZE, snap.owner)
                    assertEquals(530, snap.distanceMeters)
                    assertEquals("", snap.road)
                }
                52719L -> {
                    assertEquals(5600, snap.distanceMeters)
                    assertEquals("S0", snap.road)
                }
                406750L -> {
                    assertEquals(2700, snap.distanceMeters)
                    assertEquals("S1", snap.road)
                }
            }
        }

        assertEquals(2, zeroRows)
        assertEquals(0, maxActiveManeuver)
        val end = t0 + rows.last().tMs
        assertFalse(NavGuidanceHub.snapshot(end + 11_000).showsGuidance())
        assertFalse(NavGuidanceHub.snapshot(end + 31_000).active)
        assertTrue(changedRows > 0)
        println("WAZE-REPLAY rows=${rows.size} changedRows=$changedRows longestGapBetweenChangesMs=$longestGap " +
            "blankStreetRows=$blankRows zeroRows=$zeroRows")
    }

    @Test fun `the two 0 m rows are ignored and never reach the feed`() {
        val zeros = rows.filter { it.distance == "0 m" }
        assertEquals(listOf(51715L, 405747L), zeros.map { it.tMs })
        for (row in zeros) {
            assertEquals(WazeParse.Ignore(WazeIgnoreReason.ZERO), WazeGuidanceParser.parse(row.distance, row.street))
        }
    }
}
