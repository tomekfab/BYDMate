package com.bydmate.app.service

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TailscaleStartupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `startup sends only the explicit Tailscale connect broadcast`() {
        val result = TailscaleStartup.requestConnection(context)

        val intents = shadowOf(context as android.app.Application).broadcastIntents
        val request = intents.single { it.action == "com.tailscale.ipn.CONNECT_VPN" }
        assertEquals("com.tailscale.ipn", request.component?.packageName)
        assertEquals("com.tailscale.ipn.IPNReceiver", request.component?.className)
        assertEquals(null, shadowOf(context).nextStartedActivity)
        assertFalse(result.receiverSupported)
        assertTrue(result.sent)
    }

    @Test fun `missing Tailscale receiver is unsupported but the request is sent`() {
        assertTrue(context.packageManager.queryBroadcastReceivers(
            Intent("com.tailscale.ipn.CONNECT_VPN").setClassName(
                "com.tailscale.ipn", "com.tailscale.ipn.IPNReceiver"), 0).isEmpty())

        val result = TailscaleStartup.requestConnection(context)

        assertFalse(result.receiverSupported)
        assertTrue(result.sent)
    }

    @Test fun `restricted broadcast is not sent and does not escape`() {
        val restricted = object : ContextWrapper(context) {
            override fun sendBroadcast(intent: Intent) {
                throw SecurityException("broadcast restricted")
            }
        }

        val result = TailscaleStartup.requestConnection(restricted)

        assertFalse(result.sent)
    }

    @Test fun `service starts the recovery coordinator after foreground and network monitoring`() {
        val relative = "src/main/kotlin/com/bydmate/app/service/TrackingService.kt"
        val source = listOf(File(relative), File("app", relative)).first { it.exists() }.readText()
        val onCreate = source.substringAfter("override fun onCreate() {").substringBefore("override fun onStartCommand")
        assertEquals(1, Regex("openClawConnectionCoordinator\\.start\\(").findAll(onCreate).count())
        assertTrue(onCreate.indexOf("openClawConnectionCoordinator.start(") > onCreate.indexOf("startForeground("))
        assertTrue(onCreate.indexOf("openClawConnectionCoordinator.start(") > onCreate.indexOf("networkAvailableMonitor.start()"))
        assertFalse(onCreate.contains("TailscaleStartup.requestConnection"))
    }
}
