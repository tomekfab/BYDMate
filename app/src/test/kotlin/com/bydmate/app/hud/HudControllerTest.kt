package com.bydmate.app.hud

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.HudNaviReply
import com.bydmate.app.diagnostics.TraceRecorder
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.navdata.NavAppId
import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavA11yFeed
import com.bydmate.app.navdata.waze.WazeHudFeed
import com.bydmate.app.navdata.waze.WazeParse
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

// Lifecycle scenarios share the controller fixture and its controlled coroutine lane.
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
class HudControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val helperClient = mockk<HelperClient>(relaxed = true)
    private val helperBootstrap = mockk<HelperBootstrap>(relaxed = true)

    @get:Rule val trace = TraceRecorder()

    private fun controller(bridge: HudSomeIpBridge? = null): HudController =
        HudController(context, helperClient, helperBootstrap).apply {
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            if (bridge != null) bridgeFactory = { _, _ -> bridge }
        }

    private fun installSomeIp() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.ts.car.someip.service" })
    }

    private fun connectedBridge(): HudSomeIpBridge {
        val bridge = mockk<HudSomeIpBridge>(relaxed = true)
        coEvery { bridge.bind() } returns true
        every { bridge.startService(any()) } returns 0
        return bridge
    }

    @After fun resetWaze() {
        WazeHudFeed.setEnabled(false)
        WazeHudFeed.resetForTest()
        NavGuidanceHub.reset()
    }

    @Before fun reset() {
        NavGuidanceHub.reset()
        NavA11yFeed.enabled = false
        WazeHudFeed.setEnabled(false)
        WazeHudFeed.resetForTest()
        context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun `unsupported head unit makes zero helper calls and stays inert`() {
        val c = controller()
        c.setEnabled(true)
        assertEquals(HudController.Status.UNSUPPORTED, c.status.value)
        assertFalse(NavA11yFeed.enabled)
        assertFalse(c.requiresA11y())
        assertFalse(context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(HudController.KEY_SUPPORTED, true))
        verify { helperClient wasNot Called }
        verify { helperBootstrap wasNot Called }
    }

    @Test fun `supported path binds starts service and enables feed`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = controller(bridge)
        c.setEnabled(true)
        assertEquals(HudController.Status.ON, c.status.value)
        assertTrue(NavA11yFeed.enabled)
        assertTrue(c.requiresA11y())
        coVerify { helperClient.enableAccessibilityService() }
        verify { bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI) }
        c.setEnabled(false)   // stop the push loop so it does not leak into other tests
    }

    @Test fun `stop sends clear frame before stopService and unbind`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = controller(bridge)
        c.setEnabled(true)
        c.setEnabled(false)
        verifyOrder {
            bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, any())
            bridge.stopService(HudSomeIpBridge.SERVICE_ID_NAVI)
            bridge.unbind()
        }
        assertFalse(NavA11yFeed.enabled)
        assertEquals(HudController.Status.OFF, c.status.value)
    }

    @Test fun `toggle off during slow bind cancels start and unbinds`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = mockk<HudSomeIpBridge>(relaxed = true)
        coEvery { bridge.bind() } coAnswers { delay(30_000); true }
        val c = HudController(context, helperClient, helperBootstrap).apply {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            bridgeFactory = { _, _ -> bridge }
        }
        c.setEnabled(true)
        awaitTrue { c.status.value == HudController.Status.CONNECTING }
        c.setEnabled(false)
        awaitTrue { runCatching { verify { bridge.unbind() } }.isSuccess }
        awaitTrue { c.status.value == HudController.Status.OFF }
    }

    @Test fun `speed sign pref default on and persists`() {
        val c = controller()
        assertTrue(c.isSpeedSignEnabled())
        c.setSpeedSignEnabled(false)
        assertFalse(c.isSpeedSignEnabled())
    }

    @Test fun `requiresA11y false when disabled`() {
        assertFalse(controller().requiresA11y())
    }

    @Test fun `connection lost cleans up so service restart can recover`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        var onLost: () -> Unit = {}
        val c = HudController(context, helperClient, helperBootstrap).apply {
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            bridgeFactory = { _, lost -> onLost = lost; bridge }
        }
        c.setEnabled(true)
        assertEquals(HudController.Status.ON, c.status.value)
        onLost()   // gateway binding died
        awaitTrue { c.status.value == HudController.Status.BIND_FAILED }
        assertFalse(NavA11yFeed.enabled)
        c.startIfEnabled()   // TrackingService restart on next ignition
        awaitTrue { c.status.value == HudController.Status.ON }
        verify(exactly = 2) { bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI) }
        c.setEnabled(false)
    }

    @Test fun `service restart stop then start lands ON with ordered default scope`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = HudController(context, helperClient, helperBootstrap).apply {
            bridgeFactory = { _, _ -> bridge }   // scope left at its ordered default
        }
        c.setEnabled(true)
        awaitTrue { c.status.value == HudController.Status.ON }
        c.stop()             // TrackingService.onDestroy
        c.startIfEnabled()   // new service instance onCreate
        awaitTrue { c.status.value == HudController.Status.ON }
        c.setEnabled(false)
        awaitTrue { c.status.value == HudController.Status.OFF }
    }

    @Test fun `refused arming writes never stop the frames`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        coEvery { helperClient.hudNaviStatus(any()) } throws SecurityException("[setInt] permission deny!")
        coEvery { helperClient.writeStatus(any(), any(), any(), any()) } returns -10011
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300), NavGuidanceHub.Source.A11Y)
        val bridge = connectedBridge()
        val c = controller(bridge)
        c.setMode(HudController.MODE_NAVI_STATUS)   // only mode 2 arms
        c.setEnabled(true)
        awaitTrue { runCatching { coVerify { helperClient.hudNaviStatus(2) } }.isSuccess }
        awaitTrue { runCatching { verify(atLeast = 5) { bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, any()) } }.isSuccess }
        assertEquals(HudController.Status.ON, c.status.value)
        c.setEnabled(false)
    }

    @Test fun `HUD on without a guided route writes and reads nothing in the car`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = controller(bridge)
        c.setEnabled(true)
        assertEquals(HudController.Status.ON, c.status.value)
        Thread.sleep(2_500)   // the arming loop looks at the route three times
        c.setEnabled(false)
        coVerify(exactly = 0) { helperClient.writeStatus(any(), any(), any(), any()) }
        coVerify(exactly = 0) { helperClient.hudNaviStatus(any()) }
        coVerify(exactly = 0) { helperClient.writeBufferStatus(any(), any(), any()) }
        coVerify(exactly = 0) { helperClient.readBatch(any()) }
    }

    /** The car after a process killed mid-route: our values up, the layout kept in prefs, the
     *  HUD switched on. Reads answer the state, writes land in it. */
    private fun leftoverCar(): MutableMap<Pair<Int, Int>, Int> {
        context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(HudController.KEY_ENABLED, true).putInt(HudArming.KEY_AS_FOUND, 1).commit()
        val state = mutableMapOf(
            HudArming.NAVI to 2, HudArming.SCREEN to 3, HudArming.CLUSTER to 0,
            HudArming.CAN_NAVI to 1, HudArming.ISA to 1,
        )
        coEvery { helperClient.readBatch(any()) } answers {
            firstArg<List<BatchReadItem>>().map { 0 to (state[it.dev to it.fid] ?: 0) }
        }
        coEvery { helperClient.writeStatus(any(), any(), any(), any()) } answers {
            state[arg<Int>(0) to arg<Int>(1)] = arg(2)
            1
        }
        coEvery { helperClient.hudNaviStatus(any()) } answers {
            state[HudArming.NAVI] = firstArg()
            HudNaviReply(HelperBinderProtocol.HUD_NAVI_CALLED, 0)
        }
        return state
    }

    @Test fun `service start with a leftover and no route undoes it once`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val state = leftoverCar()
        val c = controller(connectedBridge())
        c.startIfEnabled()
        assertEquals(HudController.Status.ON, c.status.value)
        coVerify(exactly = 1) { helperClient.hudNaviStatus(4) }
        assertEquals(1, state[HudArming.SCREEN])
        assertEquals(0, state[HudArming.CAN_NAVI])
        assertEquals(0, state[HudArming.ISA])
        assertFalse(context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)
            .contains(HudArming.KEY_AS_FOUND))
        c.setEnabled(false)
        coVerify(exactly = 1) { helperClient.hudNaviStatus(4) }   // nothing armed, nothing more to undo
    }

    @Test fun `a leftover with a guided route is left to the arming loop in mode 2`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        leftoverCar()
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300), NavGuidanceHub.Source.A11Y)
        val c = controller(connectedBridge())
        c.setMode(HudController.MODE_NAVI_STATUS)
        c.startIfEnabled()
        coVerify(exactly = 1) { helperClient.hudNaviStatus(2) }
        coVerify(exactly = 0) { helperClient.hudNaviStatus(4) }
        c.setEnabled(false)
    }

    // --- status lines (trace audit) ---

    @Test fun `a failed gateway start is a status line with its step and rc`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = mockk<HudSomeIpBridge>(relaxed = true)
        coEvery { bridge.bind() } returns true
        every { bridge.startService(any()) } returns -2
        val c = controller(bridge)
        c.setEnabled(true)
        assertEquals(HudController.Status.BIND_FAILED, c.status.value)
        val status = trace.events().filter { it.contains(" status ") }
        assertEquals(2, status.size)
        assertTrue(status[0], status[0].contains("status to=connecting #"))
        assertTrue(status[1], status[1].contains("status to=bind_failed step=start_service rc=-2 #"))
    }

    @Test fun `the output on and off are status lines, the way goes to the route summary`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val c = controller(connectedBridge())
        c.setEnabled(true)
        assertEquals(HudController.MODE_GLASS_ONLY, NavGuidanceHub.hudWay)
        c.setEnabled(false)
        awaitTrue { c.status.value == HudController.Status.OFF }
        assertEquals(0, NavGuidanceHub.hudWay)
        val status = trace.events().filter { it.contains(" status ") }.map { it.substringAfter("status ").substringBefore(" #") }
        assertEquals(listOf("to=connecting", "to=on rc=0", "to=off"), status)
    }

    @Test fun `a service stop with the output already off writes no status line`() {
        controller().stop()
        assertTrue(trace.events().none { it.contains(" status ") })
    }

    // --- #301: the cluster card through the Amap adapter, only without the gateway ---

    private val shadowApp get() = shadowOf(ApplicationProvider.getApplicationContext<Application>())
    private fun hudPrefs() = context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)
    private fun broadcasts(): List<Intent> = shadowApp.broadcastIntents.toList()
    private fun kills() = broadcasts().filter { it.action == HudAmapClusterLoop.ACTION_KILL }

    private fun installPkg(pkg: String) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = pkg })
    }

    private fun guidedRoute() {
        NavGuidanceHub.updateFromNotification(NavGuidanceHub.RichUpdate(maneuverGaode = 2, distanceMeters = 300, road = "Birch Street"))
    }

    @Test fun `a car with the gateway keeps the TYPE 8 path and never gets the cluster loop`() {
        installSomeIp()
        installPkg("com.example.amapservice")
        installPkg("com.byd.amapservice")
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        guidedRoute()
        val c = controller(bridge)
        c.setEnabled(true)
        assertEquals(HudController.Status.ON, c.status.value)
        verify { bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI) }
        assertNull(c.amapCluster)
        val amap = broadcasts().filter { it.action == HudAmapBroadcaster.ACTION }
        assertTrue(amap.isNotEmpty())
        assertTrue(amap.all { it.getIntExtra("TYPE", -99) == 8 })
        assertTrue(kills().isEmpty())
        c.setEnabled(false)
        awaitTrue { c.status.value == HudController.Status.OFF }
        assertTrue(kills().isEmpty())
        assertTrue(broadcasts().none { it.getIntExtra("TYPE", -99) == 0 })
    }

    @Test fun `a car without the gateway and with the adapter runs the cluster path with zero helper calls`() {
        installPkg("com.example.amapservice")
        var bridgeBuilt = false
        val c = controller().apply { bridgeFactory = { _, _ -> bridgeBuilt = true; connectedBridge() } }
        c.setEnabled(true)
        assertEquals(HudController.Status.CLUSTER_ONLY, c.status.value)
        verify { helperClient wasNot Called }
        verify { helperBootstrap wasNot Called }
        assertFalse(bridgeBuilt)
        assertFalse(hudPrefs().getBoolean(HudController.KEY_SUPPORTED, true))
        assertFalse(c.requiresA11y())
        assertFalse(NavA11yFeed.enabled)
        assertEquals(NavGuidanceHub.WAY_AMAP_CLUSTER, NavGuidanceHub.hudWay)
        c.setEnabled(false)
    }

    @Test fun `the switch off with no leftover broadcasts nothing`() {
        installPkg("com.example.amapservice")
        val c = controller()
        c.startIfEnabled()
        assertEquals(HudController.Status.OFF, c.status.value)
        assertTrue(broadcasts().isEmpty())
    }

    @Test fun `switching off mid route is one KILL, switching off with no route is none`() {
        installPkg("com.example.amapservice")
        val idle = controller()
        idle.setEnabled(true)
        idle.setEnabled(false)
        assertEquals(HudController.Status.OFF, idle.status.value)
        assertTrue(broadcasts().isEmpty())

        guidedRoute()
        val c = controller()
        c.setEnabled(true)
        assertEquals(0, broadcasts().first().getIntExtra("TYPE", -99))
        c.setEnabled(false)
        assertEquals(HudController.Status.OFF, c.status.value)
        assertEquals(1, kills().size)
        assertEquals("com.example.amapservice", kills().single().`package`)
    }

    @Test fun `a service stop mid route is one KILL`() {
        installPkg("com.example.amapservice")
        guidedRoute()
        val c = controller()
        c.setEnabled(true)
        c.stop()
        assertEquals(1, kills().size)
    }

    private fun leftoverKey(enabled: Boolean) {
        hudPrefs().edit().putBoolean(HudController.KEY_ENABLED, enabled)
            .putLong(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT, 1L).commit()
    }

    @Test fun `a cluster leftover with no route is one KILL when the cluster path starts`() {
        installPkg("com.example.amapservice")
        leftoverKey(enabled = true)
        val c = controller()
        c.startIfEnabled()
        assertEquals(1, kills().size)
        assertFalse(hudPrefs().contains(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT))
        c.stop()
        assertEquals(1, kills().size)
    }

    @Test fun `a cluster leftover with the switch off is one KILL even while a route is guided`() {
        installPkg("com.example.amapservice")
        guidedRoute()
        leftoverKey(enabled = false)
        controller().startIfEnabled()
        assertEquals(1, kills().size)
        assertEquals("com.example.amapservice", kills().single().`package`)
        assertFalse(hudPrefs().contains(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT))
    }

    @Test fun `a leftover key on a car with the gateway is dropped without a KILL`() {
        installSomeIp()
        installPkg("com.example.amapservice")
        leftoverKey(enabled = false)
        controller().startIfEnabled()
        assertTrue(broadcasts().isEmpty())
        assertFalse(hudPrefs().contains(HudAmapClusterLoop.KEY_AMAP_CLUSTER_LEFT))
    }

    /** The application context with a package manager whose probes answer [gateway] and [adapter]:
     *  null = installed, else the throwable getPackageInfo throws. */
    private fun probing(gateway: Throwable?, adapter: Throwable?): Context {
        val pm = mockk<PackageManager>()
        for ((pkg, t) in listOf("com.ts.car.someip.service" to gateway, "com.example.amapservice" to adapter)) {
            if (t == null) every { pm.getPackageInfo(pkg, any<Int>()) } returns PackageInfo()
            else every { pm.getPackageInfo(pkg, any<Int>()) } throws t
        }
        return object : ContextWrapper(context) {
            override fun getPackageManager(): PackageManager = pm
        }
    }

    @Test fun `a gateway probe that fails for another reason never starts the cluster path`() {
        for (probe in listOf(
            probing(gateway = RuntimeException("package manager died"), adapter = null),
            probing(gateway = PackageManager.NameNotFoundException(), adapter = RuntimeException("package manager died")),
        )) {
            guidedRoute()
            val c = HudController(probe, helperClient, helperBootstrap).apply { scope = CoroutineScope(Dispatchers.Unconfined) }
            c.setEnabled(true)
            assertEquals(HudController.Status.UNSUPPORTED, c.status.value)
            assertNull(c.amapCluster)
            assertTrue(broadcasts().isEmpty())
            c.setEnabled(false)
        }
    }

    @Test fun `a failed re-probe keeps the running cluster loop and its status`() {
        var gatewayProbe: Throwable = PackageManager.NameNotFoundException()
        val pm = mockk<PackageManager>()
        every { pm.getPackageInfo("com.ts.car.someip.service", any<Int>()) } answers { throw gatewayProbe }
        every { pm.getPackageInfo("com.example.amapservice", any<Int>()) } returns PackageInfo()
        val probe = object : ContextWrapper(context) {
            override fun getPackageManager(): PackageManager = pm
        }
        guidedRoute()
        val test = TestScope(StandardTestDispatcher())
        val c = HudController(probe, helperClient, helperBootstrap).apply { scope = test }
        c.setEnabled(true)
        test.testScheduler.runCurrent()
        val loop = c.amapCluster
        assertEquals(HudController.Status.CLUSTER_ONLY, c.status.value)
        gatewayProbe = SecurityException("package manager refused")
        c.startIfEnabled()
        test.testScheduler.runCurrent()
        test.testScheduler.advanceTimeBy(2_500)   // frames at 1 and 2 s
        assertEquals(HudController.Status.CLUSTER_ONLY, c.status.value)
        assertTrue(loop != null && c.amapCluster === loop)
        assertEquals(3, broadcasts().count { it.action == HudAmapBroadcaster.ACTION })
        c.setEnabled(false)
        test.testScheduler.runCurrent()
    }

    @Test fun `the controller's own loop sends one frame a second and one KILL when the route ends`() {
        installPkg("com.example.amapservice")
        guidedRoute()
        val test = TestScope(StandardTestDispatcher())
        val c = HudController(context, helperClient, helperBootstrap).apply { scope = test }
        c.setEnabled(true)
        test.testScheduler.runCurrent()
        assertEquals(HudController.Status.CLUSTER_ONLY, c.status.value)
        test.testScheduler.advanceTimeBy(4_500)   // frames at 0, 1, 2, 3 and 4 s
        NavGuidanceHub.reset()                    // the route ends
        test.testScheduler.advanceTimeBy(5_000)
        val guide = broadcasts().filter { it.action == HudAmapBroadcaster.ACTION }
        assertEquals(5, guide.size)
        assertTrue(guide.all { it.getIntExtra("TYPE", -99) == 0 })
        assertEquals(1, kills().size)
        c.setEnabled(false)
        test.testScheduler.runCurrent()
        assertEquals(1, kills().size)
        assertEquals(HudController.Status.OFF, c.status.value)
    }

    @Test fun `a gateway car without the leftover key broadcasts nothing at start`() {
        installSomeIp()
        installPkg("com.example.amapservice")
        coEvery { helperBootstrap.ensureRunning() } returns true
        controller(connectedBridge()).startIfEnabled()
        assertTrue(broadcasts().isEmpty())
        hudPrefs().edit().putBoolean(HudController.KEY_ENABLED, true).commit()
        val c = controller(connectedBridge())
        c.startIfEnabled()
        assertEquals(HudController.Status.ON, c.status.value)
        assertTrue(broadcasts().isEmpty())
        c.setEnabled(false)
    }

    // --- Waze HUD toggle: the feed is on iff the pref is on, an output runs and the start was authorized ---

    private val g1 = WazeParse.Guidance(530, "S0")

    private fun wazePref(on: Boolean) {
        hudPrefs().edit().putBoolean(HudController.KEY_WAZE, on).commit()
    }

    private fun gatewayCar(): HudSomeIpBridge {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        return connectedBridge()
    }

    private fun clusterCar() = installPkg("com.example.amapservice")

    private var routeSeq = 0

    /** A live Waze route through the feed; returns the ticket it was committed with. Each call reads a
     *  DIFFERENT screen: the feed keeps its last observation across toggles (an unchanged one never publishes). */
    private fun liveWazeRoute(): Long {
        val t = WazeHudFeed.ticket() ?: throw AssertionError("feed is off")
        assertTrue(WazeHudFeed.commit(t, WazeParse.Guidance(500 + routeSeq++, "S0"), System.currentTimeMillis()))
        assertTrue(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))
        return t
    }

    private fun wazeRoot(): AccessibilityNodeInfo {
        fun text(value: String) = mockk<AccessibilityNodeInfo>(relaxed = true).also { every { it.text } returns value }
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.waze"
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance") } returns listOf(text("400 m"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarStreetLine") } returns listOf(text("S9"))
        return root
    }

    @Test fun `the Waze pref is off by default`() {
        assertFalse(controller().isWazeEnabled())
    }

    @Test fun `the pref on with the output not running leaves the feed off`() {
        val c = controller()
        c.setWazeEnabled(true)
        assertTrue(c.isWazeEnabled())
        assertFalse(WazeHudFeed.enabled)
        assertEquals(0L, WazeHudFeed.generationForTest)
    }

    @Test fun `a gateway start with the pref on turns the feed on, with it off it stays off`() {
        val bridge = gatewayCar()
        wazePref(true)
        val on = controller(bridge)
        on.setEnabled(true)
        assertEquals(HudController.Status.ON, on.status.value)
        assertTrue(WazeHudFeed.enabled)
        on.setEnabled(false)
        assertFalse(WazeHudFeed.enabled)

        wazePref(false)
        val off = controller(bridge)
        off.setEnabled(true)
        assertEquals(HudController.Status.ON, off.status.value)
        assertFalse(WazeHudFeed.enabled)
        off.setEnabled(false)
    }

    @Test fun `a cluster-only start with the pref on turns the feed on, with it off it stays off`() {
        clusterCar()
        wazePref(true)
        val on = controller()
        on.setEnabled(true)
        assertEquals(HudController.Status.CLUSTER_ONLY, on.status.value)
        assertTrue(WazeHudFeed.enabled)
        on.setEnabled(false)
        assertFalse(WazeHudFeed.enabled)

        wazePref(false)
        val off = controller()
        off.setEnabled(true)
        assertEquals(HudController.Status.CLUSTER_ONLY, off.status.value)
        assertFalse(WazeHudFeed.enabled)
        off.setEnabled(false)
    }

    @Test fun `HUD off and a service stop turn the feed off and end a live Waze route`() {
        val bridge = gatewayCar()
        wazePref(true)
        val c = controller(bridge)
        c.setEnabled(true)
        liveWazeRoute()
        c.setEnabled(false)
        assertFalse(WazeHudFeed.enabled)
        assertFalse(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))

        c.setEnabled(true)
        assertTrue(WazeHudFeed.enabled)
        liveWazeRoute()
        c.stop()
        assertFalse(WazeHudFeed.enabled)
        assertFalse(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))
        c.setEnabled(false)
    }

    @Test fun `a lost gateway binding turns the feed off and ends the Waze route`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        var onLost: () -> Unit = {}
        val c = HudController(context, helperClient, helperBootstrap).apply {
            scope = CoroutineScope(Dispatchers.Unconfined)
            bridgeFactory = { _, lost -> onLost = lost; bridge }
        }
        wazePref(true)
        c.setEnabled(true)
        assertTrue(WazeHudFeed.enabled)
        liveWazeRoute()
        onLost()
        assertFalse(WazeHudFeed.enabled)
        assertFalse(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))
        awaitTrue { c.status.value == HudController.Status.BIND_FAILED }
        c.setEnabled(false)
    }

    @Test fun `stop then start is one generation bump per transition and an old ticket is rejected`() {
        val bridge = gatewayCar()
        wazePref(true)
        val c = controller(bridge)
        c.setEnabled(true)
        assertEquals(1L, WazeHudFeed.generationForTest)
        val old = WazeHudFeed.ticket() ?: throw AssertionError("feed is off")
        c.stop()
        assertEquals(2L, WazeHudFeed.generationForTest)
        c.startIfEnabled()
        assertEquals(3L, WazeHudFeed.generationForTest)
        assertFalse(WazeHudFeed.commit(old, g1, System.currentTimeMillis()))
        val fresh = WazeHudFeed.ticket() ?: throw AssertionError("feed is off")
        assertTrue(WazeHudFeed.commit(fresh, g1, System.currentTimeMillis()))
        c.setEnabled(false)
    }

    @Test fun `switching Waze off ends a Waze route but a Yandex route is never ended by a revoke`() {
        val bridge = gatewayCar()
        wazePref(true)
        val c = controller(bridge)
        c.setEnabled(true)
        liveWazeRoute()
        c.setWazeEnabled(false)
        assertFalse(WazeHudFeed.enabled)
        assertFalse(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))
        assertFalse(c.isWazeEnabled())

        NavGuidanceHub.reset()
        c.setWazeEnabled(true)
        awaitTrue { WazeHudFeed.enabled }
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300, road = "Y"), NavGuidanceHub.Source.A11Y)
        c.setWazeEnabled(false)
        assertFalse(WazeHudFeed.enabled)
        assertTrue(NavGuidanceHub.isOwnedBy(NavAppId.YANDEX))

        c.setWazeEnabled(true)
        awaitTrue { WazeHudFeed.enabled }
        c.stop()
        assertFalse(WazeHudFeed.enabled)
        assertTrue(NavGuidanceHub.isOwnedBy(NavAppId.YANDEX))
        c.setEnabled(false)
    }

    // --- authorization: queued work only compares, never grants (StandardTestDispatcher: runs at runCurrent) ---

    private class Car(val c: HudController, val test: TestScope, val outputAlive: () -> Boolean)

    private fun queuedCar(gateway: Boolean): Car {
        val test = TestScope(StandardTestDispatcher())
        return if (gateway) {
            val bridge = gatewayCar()
            val c = HudController(context, helperClient, helperBootstrap).apply {
                scope = test
                bridgeFactory = { _, _ -> bridge }
            }
            Car(c, test) { c.diag() != null }
        } else {
            clusterCar()
            val c = HudController(context, helperClient, helperBootstrap).apply { scope = test }
            Car(c, test) { c.amapCluster != null }
        }
    }

    private fun Car.run() = test.testScheduler.runCurrent()

    /** Pref on, HUD enabled, the start run to completion, a Waze route live; returns the live ticket. */
    private fun Car.startedWithLiveRoute(): Long {
        wazePref(true)
        c.setEnabled(true)
        run()
        assertTrue(outputAlive())
        assertTrue(WazeHudFeed.enabled)
        return liveWazeRoute()
    }

    private fun queuedSyncAfterEagerStop(gateway: Boolean, stopWith: (Car) -> Unit) {
        val car = queuedCar(gateway)
        val liveTicket = car.startedWithLiveRoute()
        car.c.setWazeEnabled(false)
        car.run()
        assertFalse(WazeHudFeed.enabled)
        val gate = CompletableDeferred<Unit>()
        car.c.teardownGateForTest = { gate.await() }
        car.c.setWazeEnabled(true)                     // queues syncWaze(e) with the authorized epoch, not run yet

        stopWith(car)                                  // synchronous revoke, before any runCurrent()
        assertFalse(WazeHudFeed.enabled)
        assertFalse(WazeHudFeed.commit(liveTicket, g1, System.currentTimeMillis()))
        assertFalse(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))
        val generation = WazeHudFeed.generationForTest

        car.run()                                      // the queued sync runs, then the teardown parks at the gate
        assertFalse(WazeHudFeed.enabled)
        assertNull(WazeHudFeed.ticket())
        assertEquals(generation, WazeHudFeed.generationForTest)
        assertTrue("teardown must still be queued (the window under test)", car.outputAlive())
        WazeHudFeed.rootProvider = { wazeRoot() }
        WazeHudFeed.onEvent(mockk<AccessibilityEvent>(relaxed = true).also {
            every { it.packageName } returns "com.waze"
            every { it.eventType } returns AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        })
        assertFalse(WazeHudFeed.commit(liveTicket, g1, System.currentTimeMillis()))
        assertFalse(NavGuidanceHub.isActiveNow())

        car.c.setWazeEnabled(true)                     // captured epoch is -1: refuses
        car.run()
        assertFalse(WazeHudFeed.enabled)

        gate.complete(Unit)
        car.run()
        assertFalse(car.outputAlive())
        assertFalse(WazeHudFeed.enabled)
        assertFalse(NavGuidanceHub.isActiveNow())
        assertEquals(generation, WazeHudFeed.generationForTest)

        // The authorized restart is the only way back.
        car.c.setEnabled(true)
        car.run()
        assertTrue(car.outputAlive())
        assertTrue(WazeHudFeed.enabled)
        assertTrue(WazeHudFeed.generationForTest > generation)
        assertFalse(WazeHudFeed.commit(liveTicket, g1, System.currentTimeMillis()))
        val fresh = WazeHudFeed.ticket() ?: throw AssertionError("feed is off")
        assertTrue(WazeHudFeed.commit(fresh, WazeParse.Guidance(777, "S7"), System.currentTimeMillis()))
        car.c.setEnabled(false)
        car.run()
    }

    @Test fun `a queued sync after an eager stop cannot re-enable the feed while the teardown is suspended, gateway car`() =
        queuedSyncAfterEagerStop(gateway = true) { it.c.stop() }

    @Test fun `a queued sync after an eager stop cannot re-enable the feed while the teardown is suspended, cluster car`() =
        queuedSyncAfterEagerStop(gateway = false) { it.c.stop() }

    @Test fun `a queued sync after HUD disable cannot re-enable the feed, gateway car`() =
        queuedSyncAfterEagerStop(gateway = true) { it.c.setEnabled(false) }

    @Test fun `a queued sync after HUD disable cannot re-enable the feed, cluster car`() =
        queuedSyncAfterEagerStop(gateway = false) { it.c.setEnabled(false) }

    @Test fun `a queued sync after a lost binding cannot re-enable the feed, only a new start does`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        var onLost: () -> Unit = {}
        val test = TestScope(StandardTestDispatcher())
        val c = HudController(context, helperClient, helperBootstrap).apply {
            scope = test
            bridgeFactory = { _, lost -> onLost = lost; bridge }
        }
        wazePref(true)
        c.setEnabled(true)
        test.testScheduler.runCurrent()
        assertTrue(WazeHudFeed.enabled)
        val liveTicket = liveWazeRoute()
        c.setWazeEnabled(false)
        test.testScheduler.runCurrent()
        c.setWazeEnabled(true)                         // queued sync(e)

        onLost()                                       // synchronous revoke
        assertFalse(WazeHudFeed.enabled)
        assertFalse(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))
        assertFalse(WazeHudFeed.commit(liveTicket, g1, System.currentTimeMillis()))
        val generation = WazeHudFeed.generationForTest

        test.testScheduler.runCurrent()                // the sync refuses, the teardown runs
        assertFalse(WazeHudFeed.enabled)
        assertEquals(generation, WazeHudFeed.generationForTest)
        c.setWazeEnabled(true)
        test.testScheduler.runCurrent()
        assertFalse(WazeHudFeed.enabled)

        c.startIfEnabled()                             // a new start request is the only way back
        test.testScheduler.runCurrent()
        assertTrue(WazeHudFeed.enabled)
        assertTrue(WazeHudFeed.generationForTest > generation)
        c.setEnabled(false)
        test.testScheduler.runCurrent()
    }

    private fun staleStart(restart: (HudController) -> Unit) {
        val car = queuedCar(gateway = false)
        wazePref(true)
        restart(car.c)                                 // epoch e1 captured, start queued, not run
        car.c.stop()                                   // revoke
        car.run()                                      // the start runs as today, its sync refuses; the teardown follows
        assertFalse(WazeHudFeed.enabled)
        assertEquals(0L, WazeHudFeed.generationForTest)
        assertFalse(car.outputAlive())
    }

    @Test fun `a stale setEnabled start callback cannot grant itself permission`() = staleStart { it.setEnabled(true) }

    @Test fun `a stale startIfEnabled callback cannot grant itself permission`() = staleStart {
        hudPrefs().edit().putBoolean(HudController.KEY_ENABLED, true).commit()
        it.startIfEnabled()
    }

    private fun laterAuthorizationSurvives(gateway: Boolean) {
        val car = queuedCar(gateway)
        car.startedWithLiveRoute()
        car.c.stop()
        car.c.setEnabled(true)                         // a new epoch while the teardown waits in the lane
        car.run()                                      // the queued teardown runs first and does not revoke
        assertTrue(car.outputAlive())
        assertTrue(WazeHudFeed.enabled)
        car.c.setEnabled(false)
        car.run()
    }

    @Test fun `a later authorization survives an earlier teardown, gateway car`() = laterAuthorizationSurvives(gateway = true)

    @Test fun `a later authorization survives an earlier teardown, cluster car`() = laterAuthorizationSurvives(gateway = false)

    @Test fun `stop returns while a reader is parked inside the root provider, so no lock is held over a foreign call`() {
        val bridge = gatewayCar()
        wazePref(true)
        val c = controller(bridge)
        c.setEnabled(true)
        val ticket = liveWazeRoute()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        WazeHudFeed.rootProvider = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            wazeRoot()
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reader = pool.submit { WazeHudFeed.onTimer(ticket, System.currentTimeMillis()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            pool.submit { c.stop() }.get(3, TimeUnit.SECONDS)       // returns while the reader is parked
            assertFalse(WazeHudFeed.enabled)
            assertFalse(NavGuidanceHub.isOwnedBy(NavAppId.WAZE))

            release.countDown()
            reader.get(5, TimeUnit.SECONDS)
            assertFalse(NavGuidanceHub.isActiveNow())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        c.setEnabled(false)
    }

    private fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(50)
        }
        assertTrue(cond())
    }
}
