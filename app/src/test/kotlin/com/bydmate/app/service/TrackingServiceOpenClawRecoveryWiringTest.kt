package com.bydmate.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingServiceOpenClawRecoveryWiringTest {
    private val source: String by lazy {
        val relative = "src/main/kotlin/com/bydmate/app/service/TrackingService.kt"
        listOf(File(relative), File("app", relative)).first { it.exists() }.readText()
    }

    @Test fun `service owns the OpenClaw recovery coordinator lifecycle`() {
        assertTrue(source.contains("@Inject internal lateinit var openClawConnectionCoordinator: OpenClawConnectionCoordinator"))

        val onCreate = source.substringAfter("override fun onCreate() {").substringBefore("override fun onStartCommand")
        assertFalse(onCreate.contains("TailscaleStartup.requestConnection"))
        assertTrue(onCreate.indexOf("openClawConnectionCoordinator.start(") > onCreate.indexOf("startForeground("))
        assertTrue(onCreate.indexOf("openClawConnectionCoordinator.start(") > onCreate.indexOf("networkAvailableMonitor.start()"))
        assertTrue(onCreate.contains("this, serviceScope, networkAvailableMonitor.edges"))

        val wakeReceiver = source.substringAfter("private val screenWakeReceiver").substringBefore("private fun registerScreenWakeReceiver")
        assertTrue(wakeReceiver.contains("openClawConnectionCoordinator.onWake()"))
        assertTrue(source.substringAfter("private fun registerScreenWakeReceiver").substringBefore("private fun unregisterWakeReceivers").contains("Intent.ACTION_SCREEN_ON"))
        assertTrue(source.substringAfter("private fun registerScreenWakeReceiver").substringBefore("private fun unregisterWakeReceivers").contains("Intent.ACTION_USER_PRESENT"))

        val onDestroy = source.substringAfter("override fun onDestroy() {").substringBefore("override fun onTaskRemoved")
        assertTrue(onDestroy.indexOf("openClawConnectionCoordinator.stop()") < onDestroy.indexOf("networkAvailableMonitor.stop()"))
        assertTrue(onDestroy.indexOf("openClawConnectionCoordinator.stop()") < onDestroy.indexOf("serviceScope.cancel()"))
        assertEquals(1, Regex("openClawConnectionCoordinator\\.stop\\(\\)").findAll(onDestroy).count())
    }
}
