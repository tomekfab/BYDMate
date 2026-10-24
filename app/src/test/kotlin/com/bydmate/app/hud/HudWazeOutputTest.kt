package com.bydmate.app.hud

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.diagnostics.TraceRecorder
import com.bydmate.app.navdata.NavAppId
import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavRead
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What a Waze route puts on each output: the real distance and street, no arrow, a real clear 10 s after the
 *  last change, no "11 m" floor with the old street, and nothing on the Amap parallel channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HudWazeOutputTest {

    private class FakeSink : HudEventSink {
        val events = mutableListOf<Pair<Long, ByteArray>>()
        override fun fireEvent(topic: Long, payload: ByteArray): Int {
            events.add(topic to payload)
            return 0
        }
    }

    /** Minimal protobuf wire reader (copied from HudProtobufBuilderTest, which stays as it is). */
    private class ProtoReader(private val bytes: ByteArray) {
        var pos = 0
        val fields = mutableMapOf<Int, MutableList<Any>>()

        fun parse(): Map<Int, List<Any>> {
            while (pos < bytes.size) {
                val tag = readVarint().toInt()
                val fieldNo = tag ushr 3
                when (tag and 7) {
                    0 -> fields.getOrPut(fieldNo) { mutableListOf() }.add(readVarint())
                    1 -> fields.getOrPut(fieldNo) { mutableListOf() }.add(readFixed64())
                    2 -> {
                        val len = readVarint().toInt()
                        fields.getOrPut(fieldNo) { mutableListOf() }.add(bytes.copyOfRange(pos, pos + len))
                        pos += len
                    }
                    else -> error("unsupported wire type")
                }
            }
            return fields
        }

        private fun readVarint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
        }

        private fun readFixed64(): Long {
            var v = 0L
            repeat(8) { i -> v = v or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i)) }
            pos += 8
            return v
        }
    }

    private fun unwrap(payload: ByteArray): Map<Int, List<Any>> {
        assertEquals(0x0A, payload[0].toInt())
        var pos = 1; var len = 0L; var shift = 0
        while (true) {
            val b = payload[pos++].toInt() and 0xFF
            len = len or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) break
            shift += 7
        }
        assertEquals(len.toInt(), payload.size - pos)
        return ProtoReader(payload.copyOfRange(pos, payload.size)).parse()
    }

    private fun Map<Int, List<Any>>.varint(field: Int): Long? = this[field]?.firstOrNull() as? Long
    private fun Map<Int, List<Any>>.text(field: Int): String? = (this[field]?.firstOrNull() as? ByteArray)?.toString(Charsets.UTF_8)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shadowApp = shadowOf(ApplicationProvider.getApplicationContext<Application>())
    private val prefs = context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)
    private val t0 = 1_000_000L
    private var clock = t0

    @get:Rule val trace = TraceRecorder()

    @Before fun setUp() {
        NavGuidanceHub.reset()
        prefs.edit().clear().commit()
        shadowApp.clearBroadcastIntents()
    }

    @After fun tearDown() = NavGuidanceHub.reset()

    private fun wazeRoute(distance: Int = 300, road: String = "S0", at: Long = clock) =
        NavGuidanceHub.publish(NavAppId.WAZE, NavRead(distanceMeters = distance, road = road), at)

    // HudPushLoop (SOME/IP gateway path) ----------------------------------------------------

    @Test fun `push loop sends the real distance and street, no icon, f28 zero, then one clear and nothing after`() {
        wazeRoute(distance = 300, road = "S0", at = t0)
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { clock })

        clock = t0
        assertTrue(loop.tick(false))
        val f = unwrap(sink.events.single().second)
        assertEquals(300L, f.varint(9))
        assertEquals("S0", f.text(10))
        assertEquals(0L, f.varint(28))
        assertNull(f[8])

        clock = t0 + 10_000
        assertTrue(loop.tick(true))
        assertEquals(2, sink.events.size)

        clock = t0 + 10_001
        assertFalse(loop.tick(true))
        assertEquals(3, sink.events.size)
        val clear = unwrap(sink.events.last().second)
        assertEquals(255L, clear.varint(6))
        assertEquals(1L, clear.varint(16))

        clock = t0 + 20_000
        assertFalse(loop.tick(false))
        clock = t0 + 31_000
        assertFalse(loop.tick(false))
        assertEquals(3, sink.events.size)

        // No frame after the expiry decodes to the 11 m floor or the old street.
        for ((_, payload) in sink.events.drop(2)) {
            val d = unwrap(payload)
            assertTrue(d.varint(9) != 11L)
            assertTrue(d.text(10) != "S0")
        }
    }

    @Test fun `a changed read after the blank shows guidance again with no clear owed`() {
        wazeRoute(distance = 300, road = "S0", at = t0)
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { clock })
        clock = t0
        assertTrue(loop.tick(false))
        clock = t0 + 10_001
        assertFalse(loop.tick(true))                 // the clear frame
        assertEquals(2, sink.events.size)

        clock = t0 + 12_000
        wazeRoute(distance = 250, road = "S0", at = clock)
        assertTrue(loop.tick(false))
        assertEquals(3, sink.events.size)
        val f = unwrap(sink.events.last().second)
        assertEquals(250L, f.varint(9))
        assertEquals("S0", f.text(10))
        assertNull(f.varint(6)?.takeIf { it == 255L })
    }

    @Test fun `a Yandex route with no maneuver and no distance still draws as before`() {
        NavGuidanceHub.update(NavGuidance(road = "Lenina"), NavGuidanceHub.Source.A11Y, t0)
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { t0 })
        assertTrue(loop.tick(false))
        assertEquals(11L, unwrap(sink.events.single().second).varint(9))
    }

    // HudAmapClusterLoop (gateway-less path) ------------------------------------------------

    @Test fun `cluster loop sends one TYPE 0 without an icon, then one KILL and no 11 m or old street`() {
        wazeRoute(distance = 300, road = "S0", at = t0)
        val loop = HudAmapClusterLoop(context, prefs, nowMsProvider = { clock })

        clock = t0
        loop.tick()
        val first = shadowApp.broadcastIntents.single()
        assertEquals(0, first.getIntExtra("TYPE", -99))
        assertEquals(0, first.getIntExtra("NEW_ICON", -99))
        assertEquals(300, first.getIntExtra("SEG_REMAIN_DIS", -99))
        assertEquals("S0", first.getStringExtra("NEXT_ROAD_NAME"))

        shadowApp.clearBroadcastIntents()
        clock = t0 + 10_001
        loop.tick()
        val sent: List<Intent> = shadowApp.broadcastIntents
        assertEquals(1, sent.size)
        assertEquals(HudAmapClusterLoop.ACTION_KILL, sent.single().action)
        assertTrue(sent.none { it.getIntExtra("SEG_REMAIN_DIS", -99) == 11 })
        assertTrue(sent.none { it.getStringExtra("NEXT_ROAD_NAME") == "S0" })

        shadowApp.clearBroadcastIntents()
        clock = t0 + 31_000
        loop.tick()
        assertEquals(0, shadowApp.broadcastIntents.size)
    }

    // HudAmapBroadcaster (parallel channel) -------------------------------------------------

    private fun installAmapService() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.byd.amapservice" })
    }

    private fun snapshot(owner: NavAppId, distance: Int = 300, maneuver: Int = 0) = NavGuidanceHub.Snapshot(
        active = true, maneuverGaode = maneuver, distanceMeters = distance, road = "S0", owner = owner,
    )

    @Test fun `a Waze snapshot sends no Amap broadcast`() {
        installAmapService()
        val b = HudAmapBroadcaster(context)
        b.onSnapshot(snapshot(NavAppId.WAZE))
        b.onSnapshot(snapshot(NavAppId.WAZE, distance = 250))
        assertTrue(b.capable)
        assertEquals(0, shadowApp.broadcastIntents.size)
        assertEquals(0L, b.framesSent)
        assertEquals(0L, b.stopsSent)
    }

    @Test fun `a Yandex snapshot then a Waze snapshot sends exactly one stop`() {
        installAmapService()
        val b = HudAmapBroadcaster(context)
        b.onSnapshot(snapshot(NavAppId.YANDEX, maneuver = 2))
        assertEquals(1L, b.framesSent)
        shadowApp.clearBroadcastIntents()

        b.onSnapshot(snapshot(NavAppId.WAZE))
        b.onSnapshot(snapshot(NavAppId.WAZE))

        assertEquals(1L, b.stopsSent)
        val sent = shadowApp.broadcastIntents
        assertTrue(sent.isNotEmpty())
        assertTrue(sent.all { it.getIntExtra("TYPE", -99) == 9 })
        assertEquals(1L, b.framesSent)
    }

    @Test fun `a Yandex snapshot alone still sends the guide extras`() {
        installAmapService()
        val b = HudAmapBroadcaster(context)
        b.onSnapshot(snapshot(NavAppId.YANDEX, distance = 500, maneuver = 2))
        val sent = shadowApp.broadcastIntents
        assertEquals(3, sent.size)
        assertTrue(sent.all { it.getIntExtra("TYPE", -99) == 8 })
        assertTrue(sent.all { it.getIntExtra("SEG_REMAIN_DIS", -99) == 500 })
        assertTrue(sent.all { it.getIntExtra("NEW_ICON", -99) == 3 })
    }
}
