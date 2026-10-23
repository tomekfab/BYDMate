package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterMode
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsLaunchTest {

    private val gate = mockk<VoiceGate>()
    private val battery = mockk<BatteryStateRepository>()
    private val range = mockk<RangeCalculator>()
    private val tripDao = mockk<TripDao>()
    private val chargeDao = mockk<ChargeDao>()
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        .also { io.mockk.every { it.willOpenPinned(any()) } returns null }
    private val ruleDao = mockk<RuleDao>()
    private val engine = mockk<AutomationEngine>()
    private val places = mockk<PlaceRepository>()
    private val weather = mockk<WeatherClient>()
    private val exa = mockk<ExaSearchClient>()
    private val openRouterClient = mockk<OpenRouterClient>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val contactLookup = mockk<ContactLookup>()
    private val context = mockk<Context>(relaxed = true)
    private val clusterVoiceControl = mockk<ClusterVoiceControl>(relaxUnitFun = true)
    private val chargerSearchClient = mockk<ChargerSearchClient>(relaxed = true)

    private fun tools() = AgentTools(
        gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
        exa, openRouterClient, settingsRepository, contactLookup, context, clusterVoiceControl,
        chargerSearchClient,
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    ).apply {
        naviForegroundCheck = { true }; naviVerifyAttempts = 1; naviVerifyIntervalMs = 1L
    }

    // (a) destination matches a saved place (case-insensitively) -> route by lat/lon.
    @Test fun navigate_to_saved_place_routes_by_coords() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        coEvery { places.getAllSnapshot() } returns listOf(
            PlaceEntity(name = "Dom", lat = 55.75, lon = 37.61, radiusM = 100),
        )
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"dom"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("Dom", out.getString("place"))
        assertEquals("navigate", captured.captured.kind)
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals(55.75, payload.getDouble("lat"), 0.0001)
        assertEquals(37.61, payload.getDouble("lon"), 0.0001)
    }

    // (b) free-text address with no matching place -> search payload.
    @Test fun navigate_to_free_text_opens_search() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        coEvery { places.getAllSnapshot() } returns emptyList()
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"ulica Leśna 5"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("search", out.getString("mode"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("ulica Leśna 5", payload.getString("query"))
    }

// (c) placeRepository throwing must not surface as an exception; the home alias resolves as a
    // home/work shortcut and falls through to Navigator's own saved Home -> mode=route.
    @Test fun navigate_to_survives_place_lookup_failure() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        coEvery { places.getAllSnapshot() } throws RuntimeException("db down")
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"Dom"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("route", out.getString("mode"))
        assertEquals("Dom", out.getString("target"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("home", payload.getString("shortcut"))
    }

    // (d) empty destination -> error, dispatcher never called.
    @Test fun navigate_to_empty_destination_reports_error() = runTest {
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"  "}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // (e) go_home dispatches the go_home kind.
    @Test fun go_home_dispatches_go_home_kind() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(AgentToolCall("1", "go_home", "{}")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("go_home", captured.captured.kind)
    }

    // end_conversation is offered to the model and only reports ok: the voice session closes itself.
    @Test fun end_conversation_is_in_the_schemas_and_returns_ok() = runTest {
        coEvery { ruleDao.getEnabled() } returns emptyList()
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns ""
        val schemas = tools().schemas()
        val names = (0 until schemas.length())
            .map { schemas.getJSONObject(it).getJSONObject("function").getString("name") }
        assertTrue("end_conversation" in names)
        val out = JSONObject(tools().execute(AgentToolCall("1", "end_conversation", "{}")))
        assertTrue(out.getBoolean("ok"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // (f) dispatcher failure -> Polish error JSON.
    @Test fun navigate_to_dispatch_failure_reports_error() = runTest {
        coEvery { dispatcher.dispatch(any(), any()) } returns
            DispatchResult(false, "nie udało się otworzyć Nawigatora")
        coEvery { places.getAllSnapshot() } returns emptyList()
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"Gdańsk"}""")))
        assertFalse(out.has("ok"))
        assertEquals("nie udało się otworzyć Nawigatora", out.getString("error"))
    }

// (g) play_music without query -> the personal-wave mode.
    @Test fun play_music_without_query_plays_mybeat() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(AgentToolCall("1", "play_music", "{}")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("Moja fala", out.getString("playing"))
        assertEquals("yandex_music", captured.captured.kind)
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("mybeat", payload.getString("mode"))
    }

    // (h) play_music with query and no mode -> plays it directly (mode=play by default).
    @Test fun play_music_with_query_plays_by_default() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "play_music", """{"query":"Dawid Podsiadło"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("Dawid Podsiadło", out.getString("playing"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("play", payload.getString("mode"))
        assertEquals("Dawid Podsiadło", payload.getString("query"))
    }

    // (h2) play_music with query and mode=search -> only opens the search screen.
    @Test fun play_music_with_query_and_search_mode_only_searches() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "play_music", """{"query":"Dawid Podsiadło","mode":"search"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("Dawid Podsiadło", out.getString("playing"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("search", payload.getString("mode"))
        assertEquals("Dawid Podsiadło", payload.getString("query"))
    }

    // (i) dispatcher failure -> Polish error JSON.
    @Test fun play_music_dispatch_failure_reports_error() = runTest {
        coEvery { dispatcher.dispatch(any(), any()) } returns
            DispatchResult(false, "nie udało się włączyć muzyki")
        val out = JSONObject(tools().execute(AgentToolCall("1", "play_music", "{}")))
        assertFalse(out.has("ok"))
        assertEquals("nie udało się włączyć muzyki", out.getString("error"))
    }

    // --- youtube ---

    // (a) play mode (default) -> dispatches kind=youtube mode=play with query.
    @Test fun youtube_play_dispatches_youtube_kind() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "youtube", """{"query":"test Leopard 3"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("test Leopard 3", out.getString("playing"))
        assertEquals("youtube", captured.captured.kind)
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("play", payload.getString("mode"))
        assertEquals("test Leopard 3", payload.getString("query"))
    }

    // (b) mode=search is forwarded as-is.
    @Test fun youtube_search_mode_is_forwarded() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "youtube", """{"query":"jazda próbna","mode":"search"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("jazda próbna", out.getString("searching"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("search", payload.getString("mode"))
        assertEquals("jazda próbna", payload.getString("query"))
    }

    // (c) empty query -> BAD_ARGS_ERROR, dispatcher never called.
    @Test fun youtube_empty_query_reports_bad_args() = runTest {
        val out = JSONObject(tools().execute(AgentToolCall("1", "youtube", "{}")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // --- launch_app ---

// Four-app fixture: label -> package. Includes navigator and settings labels.
    private val launcherFixture = listOf(
        "Навигатор" to "ru.yandex.yandexnavi",
        "Настройки" to "com.android.settings",
        "Яндекс Музыка" to "ru.yandex.music",
        "Телефон" to "com.android.dialer",
    )

    // (a) exact case-insensitive match -> app_launch with the app's packageName.
    @Test fun launch_app_exact_match_dispatches_package() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val t = tools().apply { launcherAppsProvider = { launcherFixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"nawigator"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("app_launch", captured.captured.kind)
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("ru.yandex.yandexnavi", payload.getString("packageName"))
    }

    // (b) partial unambiguous match -> launches Settings.
    @Test fun launch_app_partial_unambiguous_launches() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val t = tools().apply { launcherAppsProvider = { launcherFixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"ustaw"}""")))
        assertTrue(out.getBoolean("ok"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("com.android.settings", payload.getString("packageName"))
    }

    // (c) ambiguous partial match -> error listing candidates, dispatcher never called.
    @Test fun launch_app_ambiguous_reports_candidates() = runTest {
        val fixture = listOf(
            "Карты" to "ru.yandex.yandexmaps",
            "Карточки" to "com.example.cards",
        )
        val t = tools().apply { launcherAppsProvider = { fixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"kart"}""")))
        assertFalse(out.has("ok"))
        assertTrue(out.getString("error").contains("Карты"))
        assertTrue(out.getString("error").contains("Карточки"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // (d) no match -> not-found error.
    @Test fun launch_app_not_found_reports_error() = runTest {
        val t = tools().apply { launcherAppsProvider = { launcherFixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"kalkulator"}""")))
        assertFalse(out.has("ok"))
        assertTrue(out.getString("error").contains("nie znaleziono aplikacji"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // (e) throwing provider -> error JSON, not a raw exception.
    @Test fun launch_app_provider_throws_reports_error() = runTest {
        val t = tools().apply { launcherAppsProvider = { throw RuntimeException("pm down") } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"nawigator"}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

// (a2) The camera alias resolves to the Chinese label via com.byd.avc.
    @Test fun launch_app_alias_camera_resolves_chinese_label() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val fixture = listOf("全景影像" to "com.byd.avc")
        val t = tools().apply { launcherAppsProvider = { fixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"kamera"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("全景影像", out.getString("app"))
        assertEquals("app_launch", captured.captured.kind)
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("com.byd.avc", payload.getString("packageName"))
    }

    // (b2) alias lookup is case-insensitive.
    @Test fun launch_app_alias_camera_case_insensitive() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val fixture = listOf("全景影像" to "com.byd.avc")
        val t = tools().apply { launcherAppsProvider = { fixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"KAMERA"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("全景影像", out.getString("app"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("com.byd.avc", payload.getString("packageName"))
    }

    @Test fun launch_app_alias_sentry_mode_uses_tryb_ochrony_and_advertises_it() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val t = tools().apply { launcherAppsProvider = { listOf("哨兵模式" to "com.byd.sentrymode") } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"tryb ochrony"}""")))

        assertTrue(out.getBoolean("ok"))
        assertEquals("com.byd.sentrymode", JSONObject(captured.captured.payload!!).getString("packageName"))
        val launchSchema = (0 until t.schemas().length()).map { t.schemas().getJSONObject(it).getJSONObject("function") }
            .single { it.getString("name") == "launch_app" }
        assertTrue(launchSchema.getString("description").contains("tryb ochrony"))
        assertFalse(launchSchema.getString("description").contains("strefa czasowa"))
    }

// (c2) The YouTube alias has two candidate packages in priority order; only the second is installed.
    @Test fun launch_app_alias_youtube_falls_back_to_second_candidate() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val fixture = listOf("YouTube" to "com.google.android.youtube")
        val t = tools().apply { launcherAppsProvider = { fixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"youtube"}""")))
        assertTrue(out.getBoolean("ok"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("com.google.android.youtube", payload.getString("packageName"))
    }

// (d2) The dashcam alias has no candidate installed and no label match -> not-found error.
    @Test fun launch_app_alias_dashcam_not_installed_reports_not_found() = runTest {
        val t = tools().apply { launcherAppsProvider = { launcherFixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"wideorejestrator"}""")))
        assertFalse(out.has("ok"))
        assertTrue(out.getString("error").contains("nie znaleziono aplikacji"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

// (e2) The maps alias picks the first installed candidate of the Maps package list.
    @Test fun launch_app_alias_yandex_maps_resolves_installed_package() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val fixture = listOf("Яндекс Карты" to "ru.yandex.yandexmaps.rustore")
        val t = tools().apply { launcherAppsProvider = { fixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"Yandex mapy"}""")))
        assertTrue(out.getBoolean("ok"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("ru.yandex.yandexmaps.rustore", payload.getString("packageName"))
    }

// (f2) The phone alias points at the BYD bluetooth dialer, not at a label match.
    @Test fun launch_app_alias_phone_resolves_byd_dialer() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val fixture = listOf("Телефон" to "com.android.dialer", "蓝牙电话" to "com.byd.bluetoothcall")
        val t = tools().apply { launcherAppsProvider = { fixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"telefon"}""")))
        assertTrue(out.getBoolean("ok"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("com.byd.bluetoothcall", payload.getString("packageName"))
    }

// (g2) The media-center alias resolves the stock media app.
    @Test fun launch_app_alias_mediacenter_resolves_stock_player() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val fixture = listOf("多媒体" to "com.byd.mediacenter")
        val t = tools().apply { launcherAppsProvider = { fixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"centrum multimediów"}""")))
        assertTrue(out.getBoolean("ok"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("com.byd.mediacenter", payload.getString("packageName"))
    }

    // --- set_cluster_projection ---

    // (a) on=true, before=OFF -> dispatched as a cluster_projection action, ok:true with label.
    // The dispatcher owns the mode poll, so the tool never polls a second time.
    @Test fun cluster_projection_on_dispatches_and_confirms() = runTest {
        every { clusterVoiceControl.projectionMode() } returns ClusterMode.OFF
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":true}""")))
        assertTrue(out.getBoolean("ok"))
        assertFalse(out.has("note"))
        assertEquals("Навигатор", out.getString("app"))
        assertEquals("cluster_projection", captured.captured.kind)
        assertEquals("1", captured.captured.payload)
        verify(exactly = 0) { clusterVoiceControl.apply(any()) }
    }

    // (b) on=true, before=FULLSCREEN -> nothing dispatched, ok:true "already" note with label.
    @Test fun cluster_projection_on_when_already_on_is_noop() = runTest {
        every { clusterVoiceControl.projectionMode() } returns ClusterMode.FULLSCREEN
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":true}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("Навигатор jest już na zestawie wskaźników", out.getString("note"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // (c) the dispatcher reports the projection never came up -> retry hint. Wave P: the
    // compositor is powered automatically around projection, so the old manual "Full + Navi"
    // precondition hint is gone; the honest advice is to retry in a few seconds.
    @Test fun cluster_projection_on_stuck_reports_retry_hint() = runTest {
        every { clusterVoiceControl.projectionMode() } returns ClusterMode.OFF
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        coEvery { dispatcher.dispatch(any(), any()) } returns
            DispatchResult(false, "projekcja na zestaw wskaźników nie została włączona")
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":true}""")))
        assertFalse(out.has("ok"))
        val error = out.getString("error")
        assertTrue(error.contains("Spróbuj ponownie"))
        assertFalse(error.contains("Navi"))
    }

    // (c2) the dispatcher blames the restarting daemon -> honest note, not a plain failure.
    @Test fun cluster_projection_on_stuck_daemon_down_reports_honest_note() = runTest {
        every { clusterVoiceControl.projectionMode() } returns ClusterMode.OFF
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        coEvery { dispatcher.dispatch(any(), any()) } returns
            DispatchResult(false, "proces usługi uruchamia się ponownie", daemonRestarting = true)
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":true}""")))
        assertFalse(out.getBoolean("ok"))
        val note = out.getString("note")
        assertTrue(note.contains("spróbuj ponownie"))
        assertFalse(note.contains("Navi"))
    }

    // (d) on=false, before=FULLSCREEN -> dispatched with payload "0", ok:true.
    @Test fun cluster_projection_off_dispatches_and_confirms() = runTest {
        every { clusterVoiceControl.projectionMode() } returns ClusterMode.FULLSCREEN
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":false}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("0", captured.captured.payload)
    }

// (e) on=false, before=OFF -> nothing dispatched, with an already-off projection note.
    @Test fun cluster_projection_off_when_already_off_is_noop() = runTest {
        every { clusterVoiceControl.projectionMode() } returns ClusterMode.OFF
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":false}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("projekcji nie ma już na zestawie wskaźników", out.getString("note"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // (f) projectionMode() throws -> treated as OFF, not propagated; on=true still dispatches.
    @Test fun cluster_projection_unreadable_state_treated_as_off_and_still_actuates() = runTest {
        every { clusterVoiceControl.projectionMode() } throws RuntimeException("boom")
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        coEvery { dispatcher.dispatch(any(), any()) } returns
            DispatchResult(false, "projekcja na zestaw wskaźników nie została włączona")
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":true}""")))
        assertTrue(out.has("error"))
        coVerify { dispatcher.dispatch(match { it.kind == "cluster_projection" }, any()) }
    }

    // missing "on" argument -> error, apply not called.
    @Test fun cluster_projection_missing_on_reports_error() = runTest {
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", "{}")))
        assertTrue(out.has("error"))
        verify(exactly = 0) { clusterVoiceControl.apply(any()) }
    }
}
