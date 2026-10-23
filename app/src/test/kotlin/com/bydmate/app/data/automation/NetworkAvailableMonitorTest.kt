package com.bydmate.app.data.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NetworkAvailableMonitorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val wifi: Network = ShadowNetwork.newInstance(101)
    private val cellular: Network = ShadowNetwork.newInstance(102)

    private fun caps(validated: Boolean): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Probes the test completes by hand, in start order. A stubborn probe ignores its cancellation. */
    private class Probes {
        val started = mutableListOf<CompletableDeferred<Boolean>>()
        var stubborn = false

        suspend fun run(@Suppress("UNUSED_PARAMETER") network: Network): Boolean {
            val result = CompletableDeferred<Boolean>()
            started += result
            return if (stubborn) withContext(NonCancellable) { result.await() } else result.await()
        }
    }

    private class Rig(scope: TestScope, connectivity: ConnectivityManager, context: Context) {
        val probes = Probes()
        val monitor = NetworkAvailableMonitor(
            context,
            reachabilityProbe = probes::run,
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime + 1_000L },
        )
        private val shadow = shadowOf(connectivity)

        /** The callback the system currently holds for the monitor. */
        fun registered(): ConnectivityManager.NetworkCallback = shadow.networkCallbacks.single()
    }

    private fun TestScope.rig() = Rig(this, connectivity, context)

    private fun ConnectivityManager.NetworkCallback.validated(network: Network) =
        onCapabilitiesChanged(network, caps(validated = true))

    private fun ConnectivityManager.NetworkCallback.validationLost(network: Network) =
        onCapabilitiesChanged(network, caps(validated = false))

    @Test fun `a failed probe releases its candidate so the same network probes again`() = runTest {
        val rig = rig()
        rig.monitor.start()
        rig.registered().validated(wifi)
        runCurrent()
        rig.probes.started.single().complete(false)
        runCurrent()
        assertFalse(rig.monitor.probePending)
        assertEquals(0L, rig.monitor.edges.value)

        rig.registered().validated(wifi)
        runCurrent()
        assertEquals("the same network gets a second probe", 2, rig.probes.started.size)
        assertTrue(rig.monitor.probePending)
        rig.probes.started[1].complete(true)
        runCurrent()

        assertEquals(1_000L, rig.monitor.edges.value)
        assertEquals(1_000L, rig.monitor.lastAvailableAt)
        assertFalse(rig.monitor.probePending)
    }

    @Test fun `an early failed probe and a later callback for the same network publish the late edge`() = runTest {
        val rig = rig()
        rig.monitor.start()
        rig.registered().validated(wifi) // the car's Wi-Fi is up, but no internet yet
        runCurrent()
        rig.probes.started.single().complete(false)
        runCurrent()

        advanceTimeBy(3 * 60_000L)
        rig.registered().validated(wifi) // a later capabilities change once the internet arrives
        runCurrent()
        rig.probes.started.last().complete(true)
        runCurrent()

        assertEquals(2, rig.probes.started.size)
        assertEquals(3 * 60_000L + 1_000L, rig.monitor.edges.value)
    }

    @Test fun `successful same-network callbacks deduplicate`() = runTest {
        val rig = rig()
        rig.monitor.start()
        rig.registered().validated(wifi)
        runCurrent()
        rig.probes.started.single().complete(true)
        runCurrent()
        val edge = rig.monitor.edges.value

        repeat(3) {
            advanceTimeBy(1_000)
            rig.registered().validated(wifi)
            runCurrent()
        }

        assertEquals(1, rig.probes.started.size)
        assertEquals(edge, rig.monitor.edges.value)
        assertFalse(rig.monitor.probePending)
    }

    @Test fun `stop clears pending state and a restart probes the same network again`() = runTest {
        val rig = rig()
        rig.monitor.start()
        rig.registered().validated(wifi)
        runCurrent()
        rig.probes.started.single().complete(true)
        runCurrent()

        rig.registered().validated(cellular)
        runCurrent()
        assertTrue(rig.monitor.probePending)
        rig.monitor.stop()
        assertFalse("stop resets the pending probe at once", rig.monitor.probePending)

        rig.monitor.start()
        rig.registered().validated(wifi) // the fresh registration replays the same network
        runCurrent()
        assertEquals(3, rig.probes.started.size)
        rig.probes.started.last().complete(true)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(rig.monitor.probePending)
    }

    @Test fun `late callbacks from a stopped registration cannot change its replacement`() = runTest {
        val rig = rig()
        rig.monitor.start()
        val old = rig.registered()
        rig.monitor.stop()
        rig.monitor.start()
        val current = rig.registered()

        old.validated(wifi)
        runCurrent()
        assertTrue("a stopped registration starts no probe", rig.probes.started.isEmpty())

        current.validated(wifi)
        runCurrent()
        assertEquals(1, rig.probes.started.size)
        old.validationLost(wifi)
        old.onLost(wifi)
        runCurrent()
        assertTrue("stale loss events keep the replacement's probe", rig.monitor.probePending)

        advanceTimeBy(5_000)
        rig.probes.started.single().complete(true)
        runCurrent()
        assertEquals(6_000L, rig.monitor.edges.value)

        current.validated(wifi)
        old.validated(wifi)
        runCurrent()
        assertEquals("the replacement still deduplicates its network", 1, rig.probes.started.size)
    }

    @Test fun `an old probe that ignores cancellation cannot change a newer candidate`() = runTest {
        for (oldResult in listOf(false, true)) {
            val rig = rig()
            rig.probes.stubborn = true
            rig.monitor.start()
            rig.registered().validated(wifi)
            runCurrent()
            rig.registered().validated(cellular) // the default network switches mid-probe
            runCurrent()
            assertEquals(2, rig.probes.started.size)

            rig.probes.started[0].complete(oldResult)
            runCurrent()
            val label = "old probe result=$oldResult"
            assertTrue(label, rig.monitor.probePending)
            assertEquals(label, 0L, rig.monitor.edges.value)
            rig.registered().validated(cellular)
            runCurrent()
            assertEquals("$label: the newer candidate is still owned", 2, rig.probes.started.size)

            rig.probes.started[1].complete(true)
            runCurrent()
            assertEquals(label, 1_000L, rig.monitor.edges.value)
            assertFalse(label, rig.monitor.probePending)
            rig.monitor.stop()
        }
    }

    @Test fun `a late probe result after stop publishes nothing`() = runTest {
        for (oldResult in listOf(false, true)) {
            val rig = rig()
            rig.probes.stubborn = true
            rig.monitor.start()
            rig.registered().validated(wifi)
            runCurrent()
            rig.monitor.stop()

            rig.probes.started.single().complete(oldResult)
            runCurrent()

            val label = "late result=$oldResult"
            assertEquals(label, 0L, rig.monitor.edges.value)
            assertEquals(label, 0L, rig.monitor.lastAvailableAt)
            assertFalse(label, rig.monitor.probePending)
        }
    }

    @Test fun `a late probe from a stopped registration cannot change the replacement's probe`() = runTest {
        for (oldResult in listOf(false, true)) {
            val rig = rig()
            rig.probes.stubborn = true
            rig.monitor.start()
            rig.registered().validated(wifi)
            runCurrent()
            rig.monitor.stop()
            rig.monitor.start()
            rig.registered().validated(wifi) // the same handle, now owned by the new registration
            runCurrent()
            assertEquals(2, rig.probes.started.size)

            rig.probes.started[0].complete(oldResult)
            runCurrent()
            val label = "late result=$oldResult"
            assertEquals(label, 0L, rig.monitor.edges.value)
            assertTrue(label, rig.monitor.probePending)

            rig.probes.started[1].complete(false)
            runCurrent()
            assertFalse(label, rig.monitor.probePending)
            rig.registered().validated(wifi)
            runCurrent()
            assertEquals("$label: the replacement's failure released its own candidate", 3, rig.probes.started.size)
            rig.monitor.stop()
        }
    }
}
