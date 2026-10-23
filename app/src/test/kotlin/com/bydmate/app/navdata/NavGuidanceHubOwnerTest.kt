package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * RED ownership contract for app-aware reads. Reflection keeps this suite runnable while the
 * Step 1 types do not exist. The production API is intentionally narrow: only test/internal
 * seams may publish Google Maps or Waze at this milestone.
 */
class NavGuidanceHubOwnerTest {

    @Before fun reset() = NavGuidanceHub.reset()

    private val hubClass get() = Class.forName("com.bydmate.app.navdata.NavGuidanceHub")
    private val hub get() = hubClass.getField("INSTANCE").get(null)

    private fun owner(name: String): Any {
        val type = Class.forName("com.bydmate.app.navdata.NavAppId")
        return type.enumConstants.single { it.toString() == name }
    }

    /** The test-only read is deliberately shaped like the existing unified guidance payload. */
    private class ReadSpec {
        var maneuver = 0
        var distance = 0
        var road = ""
        var eta = 0
        var total = 0
        var limit = 0
    }

    private fun read(configure: ReadSpec.() -> Unit = {}): Any {
        val spec = ReadSpec().apply(configure)
        val type = Class.forName("com.bydmate.app.navdata.NavRead")
        val ctor = type.declaredConstructors.single { it.parameterTypes.size == 6 }
        return ctor.newInstance(spec.maneuver, spec.distance, spec.road, spec.eta, spec.total, spec.limit)
    }

    private fun publish(app: String, data: Any, nowMs: Long) {
        val method = hubClass.methods.single { it.name == "publishForTest" && it.parameterTypes.size == 3 }
        method.invoke(hub, owner(app), data, nowMs)
    }

    private fun appKeepAlive(app: String, nowMs: Long) {
        val method = hubClass.methods.single { it.name == "keepAlive" && it.parameterTypes.size == 2 }
        method.invoke(hub, owner(app), nowMs)
    }

    private fun appDeactivate(app: String, nowMs: Long) {
        val method = hubClass.methods.single { it.name == "deactivateFromNotificationGrace" && it.parameterTypes.size == 2 }
        method.invoke(hub, owner(app), nowMs)
    }

    private fun field(name: String, nowMs: Long): Any? {
        val snapshot = NavGuidanceHub.snapshot(nowMs)
        val getter = snapshot.javaClass.methods.single { it.name == "get" + name.replaceFirstChar { it.uppercase() } }
        return getter.invoke(snapshot)
    }

    private fun ownerName(nowMs: Long): String = field("owner", nowMs).toString()

    @Test fun `unknown and road-only app reads neither start nor refresh a route`() {
        publish("GOOGLE_MAPS", read {
            road = "Main"
            eta = 600
            total = 2_000
        }, 1_000)
        assertFalse(NavGuidanceHub.snapshot(1_000).active)

        publish("WAZE", read {
            maneuver = 2
            distance = 100
        }, 2_000)
        val before = NavGuidanceHub.snapshot(2_000)
        publish("GOOGLE_MAPS", read { road = "Other" }, 3_000)
        assertEquals(before.lastUpdateMs, NavGuidanceHub.snapshot(3_000).lastUpdateMs)
        assertEquals("WAZE", ownerName(3_000))
    }

    @Test fun `app handover replaces the whole snapshot once the owner is no longer live`() {
        publish("YANDEX", read {
            maneuver = 2
            distance = 120
            road = "Old road"
            eta = 600
            total = 4_000
            limit = 70
        }, 1_000)
        publish("GOOGLE_MAPS", read {
            maneuver = 3
            distance = 80
        }, 1_000 + NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1)

        val snapshot = NavGuidanceHub.snapshot(1_000 + NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1)
        assertTrue(snapshot.active)
        assertEquals("GOOGLE_MAPS", ownerName(1_000 + NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1))
        assertEquals(3, snapshot.maneuverGaode)
        assertEquals(80, snapshot.distanceMeters)
        assertEquals("", snapshot.road)
        assertEquals(0, snapshot.etaSeconds)
        assertEquals(0, snapshot.totalDistMeters)
        assertEquals(0, snapshot.speedLimit)
        assertEquals(0L, NavGuidanceHub.foreignReadsDropped)
    }

    @Test fun `foreign keep alive and notification removal cannot mutate the selected owner`() {
        publish("GOOGLE_MAPS", read {
            maneuver = 2
            distance = 100
        }, 1_000)
        appKeepAlive("YANDEX", 20_000)
        appDeactivate("WAZE", 20_000)

        val snapshot = NavGuidanceHub.snapshot(20_000)
        assertTrue(snapshot.active)
        assertEquals("GOOGLE_MAPS", ownerName(20_000))
        assertEquals(1_000L, snapshot.lastUpdateMs)
    }

    @Test fun `new app arrow and distance expire after ten seconds while ownership holds to route expiry`() {
        val t0 = 1_000L
        publish("GOOGLE_MAPS", read {
            maneuver = 2
            distance = 120
            road = "Main"
        }, t0)

        val afterDetailExpiry = NavGuidanceHub.snapshot(t0 + 10_001)
        assertTrue(afterDetailExpiry.active)
        assertEquals("GOOGLE_MAPS", ownerName(t0 + 10_001))
        assertEquals(0, afterDetailExpiry.maneuverGaode)
        assertEquals(0, afterDetailExpiry.distanceMeters)
        assertEquals("Main", afterDetailExpiry.road)

        assertTrue(NavGuidanceHub.snapshot(t0 + 30_000).active)
        assertFalse(NavGuidanceHub.snapshot(t0 + 30_001).active)
        assertEquals("NONE", ownerName(t0 + 30_001))
    }

    @Test fun `Yandex keeps its existing ninety second lifetime`() {
        val t0 = 1_000L
        publish("YANDEX", read {
            maneuver = 2
            distance = 120
        }, t0)
        assertTrue(NavGuidanceHub.snapshot(t0 + NavGuidanceHub.ACTIVE_TIMEOUT_MS).active)
        assertFalse(NavGuidanceHub.snapshot(t0 + NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1).active)
        assertEquals("NONE", ownerName(t0 + NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1))
    }

    @Test fun `concurrent owners leave one complete snapshot rather than interleaved fields`() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        fun writer(app: String, data: Any) = Thread {
            ready.countDown()
            start.await()
            publish(app, data, 1_000)
            done.countDown()
        }
        writer("GOOGLE_MAPS", read {
            maneuver = 2
            distance = 111
            road = "Google"
            eta = 600
            total = 3_000
            limit = 50
        }).start()
        writer("WAZE", read {
            maneuver = 3
            distance = 222
            road = "Waze"
            eta = 700
            total = 4_000
            limit = 60
        }).start()
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))

        val snapshot = NavGuidanceHub.snapshot(1_000)
        assertEquals(1L, NavGuidanceHub.foreignReadsDropped)
        when (ownerName(1_000)) {
            "GOOGLE_MAPS" -> {
                assertEquals(2, snapshot.maneuverGaode)
                assertEquals(111, snapshot.distanceMeters)
                assertEquals("Google", snapshot.road)
                assertEquals(600, snapshot.etaSeconds)
                assertEquals(3_000, snapshot.totalDistMeters)
                assertEquals(50, snapshot.speedLimit)
            }
            "WAZE" -> {
                assertEquals(3, snapshot.maneuverGaode)
                assertEquals(222, snapshot.distanceMeters)
                assertEquals("Waze", snapshot.road)
                assertEquals(700, snapshot.etaSeconds)
                assertEquals(4_000, snapshot.totalDistMeters)
                assertEquals(60, snapshot.speedLimit)
            }
            else -> throw AssertionError("no selected test owner")
        }
    }
}
