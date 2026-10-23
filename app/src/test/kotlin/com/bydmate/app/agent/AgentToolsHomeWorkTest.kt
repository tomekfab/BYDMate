package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsHomeWorkTest {

    private val gate = mockk<VoiceGate>(relaxed = true)
    private val battery = mockk<BatteryStateRepository>(relaxed = true)
    private val range = mockk<RangeCalculator>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val chargeDao = mockk<ChargeDao>(relaxed = true)
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        .also { io.mockk.every { it.willOpenPinned(any()) } returns null }
    private val ruleDao = mockk<RuleDao>(relaxed = true)
    private val engine = mockk<AutomationEngine>(relaxed = true)
    private val places = mockk<PlaceRepository>(relaxed = true)
    private val weather = mockk<WeatherClient>(relaxed = true)
    private val exa = mockk<ExaSearchClient>(relaxed = true)
    private val openRouterClient = mockk<OpenRouterClient>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val contactLookup = mockk<ContactLookup>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private val tools = AgentTools(
        gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
        exa, openRouterClient, settingsRepository, contactLookup, context,
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    ).also {
        it.nowMs = { 1_000_000_000_000L }
        it.naviForegroundCheck = { true }
        it.naviVerifyAttempts = 1
        it.naviVerifyIntervalMs = 1L
    }

    private fun call(name: String, args: String) = AgentToolCall("1", name, args)

@Test fun `home prefers BYDMate place over navigator shortcut`() = runTest {
        coEvery { places.getAllSnapshot() } returns listOf(
            PlaceEntity(name = "Dom", lat = 55.7, lon = 37.6, radiusM = 100))
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        val out = JSONObject(tools.execute(call("navigate_to", """{"destination":"dom"}""")))

        assertTrue(out.getBoolean("ok"))
        assertEquals("route", out.getString("mode"))
        coVerify { dispatcher.dispatch(match {
            it.kind == "navigate" && JSONObject(it.payload!!).getDouble("lat") == 55.7
        }, null) }
    }

@Test fun `home falls back to navigator home shortcut when no place`() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        val out = JSONObject(tools.execute(call("navigate_to", """{"destination":"Dom"}""")))

        assertTrue(out.getBoolean("ok"))
        assertEquals("route", out.getString("mode"))
        coVerify { dispatcher.dispatch(match {
            it.kind == "navigate" && JSONObject(it.payload!!).getString("shortcut") == "home"
        }, null) }
    }

    @Test fun `Polish home aliases use the navigator shortcut when no place is saved`() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        for (destination in listOf("Dom", "do domu", "mój dom")) {
            val out = JSONObject(tools.execute(call("navigate_to", """{"destination":"$destination"}""")))

            assertTrue(destination, out.getBoolean("ok"))
            assertEquals(destination, "route", out.getString("mode"))
            assertEquals(destination, "Dom", out.getString("target"))
        }
        coVerify(exactly = 3) { dispatcher.dispatch(match {
            it.kind == "navigate" && JSONObject(it.payload!!).getString("shortcut") == "home"
        }, null) }
    }

    @Test fun `Polish home request resolves a historical saved home by coordinates`() = runTest {
        coEvery { places.getAllSnapshot() } returns listOf(
            PlaceEntity(name = "Дом", lat = 52.2297, lon = 21.0122, radiusM = 100),
        )
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)

        val out = JSONObject(tools.execute(call("navigate_to", """{"destination":"Dom"}""")))

        assertTrue(out.getBoolean("ok"))
        assertEquals("route", out.getString("mode"))
        assertEquals("Дом", out.getString("place"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals(52.2297, payload.getDouble("lat"), 0.0001)
        assertEquals(21.0122, payload.getDouble("lon"), 0.0001)
        assertTrue(!payload.has("shortcut"))
    }

@Test fun `work uses work shortcut`() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        tools.execute(call("navigate_to", """{"destination":"do pracy"}"""))

        coVerify { dispatcher.dispatch(match {
            JSONObject(it.payload!!).getString("shortcut") == "work"
        }, null) }
    }

@Test fun `home with nothing anywhere returns actionable error`() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { dispatcher.dispatch(any(), any()) } returns
            DispatchResult(false, "Brak aplikacji do obsługi")

        val out = JSONObject(tools.execute(call("navigate_to", """{"destination":"dom"}""")))

// The dispatcher failure reason is preferred over the hardcoded address-not-found message.
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("Brak aplikacji do obsługi"))
    }

    @Test fun `ordinary destination does not trigger home logic`() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { weather.geocode("Twer") } returns Result.success(
            WeatherClient.GeoPoint(lat = 56.86, lon = 35.9, name = "Twer"))
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        tools.execute(call("navigate_to", """{"destination":"Twer"}"""))

        coVerify { dispatcher.dispatch(match {
            val p = JSONObject(it.payload!!)
            p.has("lat") && !p.has("shortcut")
        }, null) }
    }
}
