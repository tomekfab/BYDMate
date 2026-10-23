package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.split.Pane
import com.bydmate.app.split.SplitPair
import com.bydmate.app.split.SplitPreferences
import com.bydmate.app.split.SplitSessionManager
import com.bydmate.app.split.SplitSessionState
import com.bydmate.app.split.SplitSide
import com.bydmate.app.split.SplitStartResult
import com.bydmate.app.voice.VoiceGate
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsSplitScreenTest {

    private val gate = mockk<VoiceGate>()
    private val battery = mockk<BatteryStateRepository>()
    private val range = mockk<RangeCalculator>()
    private val tripDao = mockk<TripDao>()
    private val chargeDao = mockk<ChargeDao>()
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
    private val ruleDao = mockk<RuleDao> { coEvery { getCount() } returns 0 }
    private val engine = mockk<AutomationEngine>()
    private val places = mockk<PlaceRepository>()
    private val weather = mockk<WeatherClient>()
    private val exa = mockk<ExaSearchClient>()
    private val openRouterClient = mockk<OpenRouterClient>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val contactLookup = mockk<ContactLookup>()
    private val context = mockk<Context>(relaxed = true)

    // Mock SplitPreferences — isFeatureEnabled() returns true by default.
    private val splitPreferences = mockk<SplitPreferences>()

    // Mock SplitSessionManager — state and suspend functions set up per test.
    private val splitMgr = mockk<SplitSessionManager>(relaxed = true)

    // State flow shared across all tests; individual tests set it as needed.
    private val stateFlow = MutableStateFlow<SplitSessionState>(SplitSessionState.Idle)

    init {
        every { splitPreferences.isFeatureEnabled() } returns true
        every { splitMgr.state } returns stateFlow
    }

    // Four-app fixture for the launcher resolver.
    private val launcherFixture = listOf(
        "Nawigator" to "ru.yandex.yandexnavi",
        "YouTube" to "com.google.android.youtube",
        "Aparat" to "com.byd.avc",
        "Przeglądarka" to "com.android.chrome",
    )

    private fun tools(): AgentTools = AgentTools(
        gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
        exa, openRouterClient, settingsRepository, contactLookup, context,
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    ).apply {
        injectSplit(splitPreferences, splitMgr)
        launcherAppsProvider = { launcherFixture }
        naviForegroundCheck = { true }
        naviVerifyAttempts = 1
        naviVerifyIntervalMs = 1L
    }

    // ─── Feature gate ────────────────────────────────────────────────────────────

// (a) Feature disabled -> every action returns a disabled error.
    @Test fun feature_disabled_returns_error_for_start() = runTest {
        every { splitPreferences.isFeatureEnabled() } returns false
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "split_screen", """{"action":"start"}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("wyłączony"))
        coVerify(exactly = 0) { splitMgr.start(any()) }
        coVerify(exactly = 0) { splitMgr.startLastPair() }
    }

    @Test fun feature_disabled_returns_error_for_exit() = runTest {
        every { splitPreferences.isFeatureEnabled() } returns false
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "split_screen", """{"action":"exit"}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { splitMgr.exit() }
    }

    // ─── start ───────────────────────────────────────────────────────────────────

    // (b) start with narrow_app + wide_app → resolves packages, calls start().
    @Test fun start_with_apps_resolves_and_calls_manager() = runTest {
        coEvery { splitMgr.start(any()) } returns SplitStartResult.OK
        val out = JSONObject(tools().execute(AgentToolCall("1", "split_screen",
            """{"action":"start","narrow_app":"Nawigator","wide_app":"YouTube"}""")))
        assertTrue(out.getBoolean("ok"))
        coVerify {
            splitMgr.start(SplitPair(
                narrowPkg = "ru.yandex.yandexnavi",
                widePkg = "com.google.android.youtube",
                narrowSide = SplitSide.RIGHT, // default
            ))
        }
    }

    // (c) start with side=left → SplitSide.LEFT passed.
    @Test fun start_with_side_left_passes_left_side() = runTest {
        coEvery { splitMgr.start(any()) } returns SplitStartResult.OK
        tools().execute(AgentToolCall("1", "split_screen",
            """{"action":"start","narrow_app":"Nawigator","wide_app":"YouTube","side":"left"}"""))
        coVerify {
            splitMgr.start(SplitPair("ru.yandex.yandexnavi", "com.google.android.youtube", SplitSide.LEFT))
        }
    }

    // (d) start with no apps → startLastPair().
    @Test fun start_no_apps_restores_last_pair() = runTest {
        coEvery { splitMgr.startLastPair() } returns SplitStartResult.OK
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "split_screen", """{"action":"start"}""")))
        assertTrue(out.getBoolean("ok"))
        coVerify { splitMgr.startLastPair() }
        coVerify(exactly = 0) { splitMgr.start(any()) }
    }

// (e) Start with no apps and no saved pair -> an honest no-saved-pair error.
    @Test fun start_no_apps_no_saved_pair_returns_error() = runTest {
        coEvery { splitMgr.startLastPair() } returns null
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "split_screen", """{"action":"start"}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("brak zapisanej pary"))
    }

    // (f) only one app specified → error, no manager call.
    @Test fun start_only_one_app_returns_error() = runTest {
        val out = JSONObject(tools().execute(AgentToolCall("1", "split_screen",
            """{"action":"start","narrow_app":"Nawigator"}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("narrow_app"))
        coVerify(exactly = 0) { splitMgr.start(any()) }
    }

    // (g) same package for both panes → same-package guard, no manager call.
    @Test fun start_same_package_rejected() = runTest {
        val out = JSONObject(tools().execute(AgentToolCall("1", "split_screen",
            """{"action":"start","narrow_app":"Nawigator","wide_app":"nawigator"}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("takie same"))
        coVerify(exactly = 0) { splitMgr.start(any()) }
    }

// (h) An unresolvable app name -> an honest app-not-found error, no start().
    @Test fun start_unresolvable_narrow_app_returns_not_found() = runTest {
        val out = JSONObject(tools().execute(AgentToolCall("1", "split_screen",
            """{"action":"start","narrow_app":"Kalkulator","wide_app":"YouTube"}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("nie znaleziono aplikacji"))
        coVerify(exactly = 0) { splitMgr.start(any()) }
    }

// (i) FREEFORM_UNAVAILABLE -> restart-the-car error text.
    @Test fun start_freeform_unavailable_returns_reboot_hint() = runTest {
        coEvery { splitMgr.start(any()) } returns SplitStartResult.FREEFORM_UNAVAILABLE
        val out = JSONObject(tools().execute(AgentToolCall("1", "split_screen",
            """{"action":"start","narrow_app":"Nawigator","wide_app":"YouTube"}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("uruchomieniu"))
    }

    // (j) LAUNCH_FAILED → error response.
    @Test fun start_launch_failed_returns_error() = runTest {
        coEvery { splitMgr.start(any()) } returns SplitStartResult.LAUNCH_FAILED
        val out = JSONObject(tools().execute(AgentToolCall("1", "split_screen",
            """{"action":"start","narrow_app":"Nawigator","wide_app":"YouTube"}""")))
        assertTrue(out.has("error"))
    }

    // ─── mirror ──────────────────────────────────────────────────────────────────

    // (k) mirror when Active → calls mirror(), ok.
    @Test fun mirror_when_active_calls_manager() = runTest {
        stateFlow.value = SplitSessionState.Active(
            SplitPair("a.pkg", "b.pkg", SplitSide.RIGHT), narrowTaskId = 1, wideTaskId = 2)
        coEvery { splitMgr.mirror() } returns null
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "split_screen", """{"action":"mirror"}""")))
        assertTrue(out.getBoolean("ok"))
        coVerify { splitMgr.mirror() }
    }

    // Wave 3: mirror() is a silent no-op in several states (pane on the cluster, move in
// progress) — the driver must hear the reason, not a generic success reply.
    @Test fun mirror_that_did_nothing_returns_the_reason() = runTest {
        stateFlow.value = SplitSessionState.Active(
            SplitPair("a.pkg", "b.pkg", SplitSide.RIGHT), narrowTaskId = 1, wideTaskId = 2)
        coEvery { splitMgr.mirror() } returns "jeden z paneli jest teraz na zestawie wskaźników"
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "split_screen", """{"action":"mirror"}""")))
        assertEquals("jeden z paneli jest teraz na zestawie wskaźników", out.getString("error"))
    }

// (l) Mirror when Idle -> split-not-started, no manager call.
    @Test fun mirror_when_idle_returns_not_running() = runTest {
        stateFlow.value = SplitSessionState.Idle
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "split_screen", """{"action":"mirror"}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("podział ekranu nie jest uruchomiony"))
        coVerify(exactly = 0) { splitMgr.mirror() }
    }

    // ─── swap ────────────────────────────────────────────────────────────────────

    // (m) swap when Active → calls swapApps(), ok.
    @Test fun swap_when_active_calls_manager() = runTest {
        stateFlow.value = SplitSessionState.Active(
            SplitPair("a.pkg", "b.pkg", SplitSide.RIGHT), narrowTaskId = 1, wideTaskId = 2)
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "split_screen", """{"action":"swap"}""")))
        assertTrue(out.getBoolean("ok"))
        coVerify { splitMgr.swapApps() }
    }

// (n) Swap when Idle -> split-not-started, no manager call.
    @Test fun swap_when_idle_returns_not_running() = runTest {
        stateFlow.value = SplitSessionState.Idle
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "split_screen", """{"action":"swap"}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("podział ekranu nie jest uruchomiony"))
        coVerify(exactly = 0) { splitMgr.swapApps() }
    }

    // ─── change ──────────────────────────────────────────────────────────────────

    // (o) change side=right, narrowSide=RIGHT → changes NARROW pane.
    @Test fun change_right_pane_with_narrow_on_right_changes_narrow() = runTest {
        stateFlow.value = SplitSessionState.Active(
            SplitPair("ru.yandex.yandexnavi", "com.google.android.youtube", SplitSide.RIGHT),
            narrowTaskId = 1, wideTaskId = 2)
        coEvery { splitMgr.changeApp(Pane.NARROW, "com.byd.avc") } returns SplitStartResult.OK
        val out = JSONObject(tools().execute(AgentToolCall("1", "split_screen",
            """{"action":"change","side":"right","app":"Aparat"}""")))
        assertTrue(out.getBoolean("ok"))
        coVerify { splitMgr.changeApp(Pane.NARROW, "com.byd.avc") }
    }

    // (p) change side=left, narrowSide=RIGHT → changes WIDE pane.
    @Test fun change_left_pane_with_narrow_on_right_changes_wide() = runTest {
        stateFlow.value = SplitSessionState.Active(
            SplitPair("ru.yandex.yandexnavi", "com.google.android.youtube", SplitSide.RIGHT),
            narrowTaskId = 1, wideTaskId = 2)
        coEvery { splitMgr.changeApp(Pane.WIDE, "com.byd.avc") } returns SplitStartResult.OK
        val out = JSONObject(tools().execute(AgentToolCall("1", "split_screen",
            """{"action":"change","side":"left","app":"Aparat"}""")))
        assertTrue(out.getBoolean("ok"))
        coVerify { splitMgr.changeApp(Pane.WIDE, "com.byd.avc") }
    }

    // (q) change: new app same as OTHER pane → same-package guard, no manager call.
    @Test fun change_same_as_other_pane_is_rejected() = runTest {
// narrow=navigator on right, wide=youtube on left. Changing right (NARROW) to youtube = same as wide.
        stateFlow.value = SplitSessionState.Active(
            SplitPair("ru.yandex.yandexnavi", "com.google.android.youtube", SplitSide.RIGHT),
            narrowTaskId = 1, wideTaskId = 2)
        val out = JSONObject(tools().execute(AgentToolCall("1", "split_screen",
            """{"action":"change","side":"right","app":"YouTube"}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("takie same"))
        coVerify(exactly = 0) { splitMgr.changeApp(any(), any()) }
    }

// (r) Change when Idle -> split-not-started.
    @Test fun change_when_idle_returns_not_running() = runTest {
        stateFlow.value = SplitSessionState.Idle
        val out = JSONObject(tools().execute(AgentToolCall("1", "split_screen",
            """{"action":"change","side":"right","app":"Aparat"}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("podział ekranu nie jest uruchomiony"))
        coVerify(exactly = 0) { splitMgr.changeApp(any(), any()) }
    }

    // (s) change: missing side → error.
    @Test fun change_missing_side_returns_error() = runTest {
        stateFlow.value = SplitSessionState.Active(
            SplitPair("a.pkg", "b.pkg", SplitSide.RIGHT), narrowTaskId = 1, wideTaskId = 2)
        val out = JSONObject(tools().execute(AgentToolCall("1", "split_screen",
            """{"action":"change","app":"Aparat"}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("side"))
        coVerify(exactly = 0) { splitMgr.changeApp(any(), any()) }
    }

    // (t) change: missing app → error.
    @Test fun change_missing_app_returns_error() = runTest {
        stateFlow.value = SplitSessionState.Active(
            SplitPair("a.pkg", "b.pkg", SplitSide.RIGHT), narrowTaskId = 1, wideTaskId = 2)
        val out = JSONObject(tools().execute(AgentToolCall("1", "split_screen",
            """{"action":"change","side":"right"}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { splitMgr.changeApp(any(), any()) }
    }

    // (u) change: unresolvable app → not-found error.
    @Test fun change_unresolvable_app_returns_not_found() = runTest {
        stateFlow.value = SplitSessionState.Active(
            SplitPair("a.pkg", "b.pkg", SplitSide.RIGHT), narrowTaskId = 1, wideTaskId = 2)
        val out = JSONObject(tools().execute(AgentToolCall("1", "split_screen",
            """{"action":"change","side":"right","app":"Kalkulator"}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("nie znaleziono aplikacji"))
        coVerify(exactly = 0) { splitMgr.changeApp(any(), any()) }
    }

    // ─── exit ────────────────────────────────────────────────────────────────────

    // (v) exit when Active → calls exit(), ok.
    @Test fun exit_when_active_calls_manager() = runTest {
        stateFlow.value = SplitSessionState.Active(
            SplitPair("a.pkg", "b.pkg", SplitSide.RIGHT), narrowTaskId = 1, wideTaskId = 2)
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "split_screen", """{"action":"exit"}""")))
        assertTrue(out.getBoolean("ok"))
        coVerify { splitMgr.exit() }
    }

// (w) Exit when Idle -> split-not-started, no manager call.
    @Test fun exit_when_idle_returns_not_running() = runTest {
        stateFlow.value = SplitSessionState.Idle
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "split_screen", """{"action":"exit"}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("podział ekranu nie jest uruchomiony"))
        coVerify(exactly = 0) { splitMgr.exit() }
    }

    // ─── Schema ──────────────────────────────────────────────────────────────────

    // (x) split_screen appears in the tool schema list.
    @Test fun split_screen_appears_in_schemas() = runTest {
        val schemas = tools().schemas()
        val names = (0 until schemas.length()).map {
            schemas.getJSONObject(it).getJSONObject("function").getString("name")
        }
        assertTrue(names.contains("split_screen"))
    }

    // ─── create_automation with split_screen action kind ─────────────────────────

    // Helper that builds a create_automation JSON with a split_screen action.
    private fun createArgs(
        name: String = "Tryb nocny",
        trigger: String = """{"kind":"param","param":"SOC","operator":"<","value":"20"}""",
        actions: String,
    ) = """{"name":"$name","trigger":$trigger,"actions":$actions}"""

    // (y) create_automation with split_screen action builds and persists the ActionDef.
    @Test fun create_automation_with_split_screen_action_saves_correct_payload() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val saved = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(saved)) } returns 1L
        val t = tools()
        val out = JSONObject(t.execute(AgentToolCall("1", "create_automation",
            createArgs(actions = """[{"kind":"split_screen","narrow_app":"Nawigator","wide_app":"YouTube"}]"""))))
        assertTrue(out.getBoolean("ok"))
        val action = ActionDef.listFromJson(saved.captured.actions).single()
        assertEquals("split_screen", action.kind)
        val payload = JSONObject(action.payload!!)
        assertEquals("ru.yandex.yandexnavi", payload.getString("narrow"))
        assertEquals("com.google.android.youtube", payload.getString("wide"))
        assertEquals("right", payload.getString("side")) // default side
    }

    // (z) create_automation: same narrow_app and wide_app → rejected by validator with
// The two-apps-must-differ error is surfaced from SplitScreenSamePackage.
    @Test fun create_automation_split_screen_same_package_is_rejected() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val out = JSONObject(tools().execute(AgentToolCall("1", "create_automation",
            createArgs(actions = """[{"kind":"split_screen","narrow_app":"Nawigator","wide_app":"Nawigator"}]"""))))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("takie same") || out.getString("error").contains("różne"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    // (z2) create_automation: split_screen with side=left stores "left" in payload.
    @Test fun create_automation_split_screen_side_left_stored() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val saved = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(saved)) } returns 1L
        val out = JSONObject(tools().execute(AgentToolCall("1", "create_automation",
            createArgs(actions = """[{"kind":"split_screen","narrow_app":"Nawigator","wide_app":"YouTube","side":"left"}]"""))))
        assertTrue(out.getBoolean("ok"))
        val payload = JSONObject(ActionDef.listFromJson(saved.captured.actions).single().payload!!)
        assertEquals("left", payload.getString("side"))
    }
}
