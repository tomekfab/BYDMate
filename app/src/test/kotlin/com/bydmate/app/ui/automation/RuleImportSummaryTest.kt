package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.SharedRule
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.util.appLocalizedContext
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the import preview says an action does, checked against the real dispatcher. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RuleImportSummaryTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { LocalePreferences(ctx).setLanguage("ru") }

    @Test fun `import preview shows the trigger logic and what navigate, music and split really run`() {
        val lc = ctx.appLocalizedContext()
        val rule = SharedRuleFixture.withActions(
            ActionDef("", "Działka", "navigate", """{"lat":54.1,"lon":27.2,"name":"Działka","shortcut":"home","go":true}"""),
            ActionDef("", "Muzyka", "yandex_music", """{"mode":"search","query":"Queen","minimize":true}"""),
            ActionDef("", "Podział", "split_screen", """{"narrow":"a.b","wide":"c.d","side":"right"}"""),
            ActionDef("", "Wyszukiwanie", "navigate", """{"query":"stacja paliw","show":true,"go":true,"app":"maps"}"""),
        )
        val autoGo = realDispatcher()::autoGoWillRun
        val preview = RuleImportSummary.preview(rule.copy(triggerLogic = "OR"), ctx, autoGo)
        assertEquals(lc.getString(R.string.automation_import_logic_any), preview.logic)
        assertEquals(lc.getString(R.string.automation_import_logic_all), RuleImportSummary.preview(rule, ctx, autoGo).logic)

        val home = preview.actions[0]
        val music = preview.actions[1]
        val split = preview.actions[2]
        val search = preview.actions[3]
        assertTrue(home, home.contains(lc.getString(R.string.automation_import_nav_home)))
        assertTrue(home, home.contains(lc.getString(R.string.automation_import_nav_go)))
        assertFalse(home, home.contains("54.1"))
        assertTrue(music, music.contains("search «Queen»"))
        assertTrue(music, music.contains(lc.getString(R.string.automation_import_minimize)))
        assertTrue(split, split.contains(lc.getString(R.string.split_action_side_right)))
        assertTrue(search, search.contains(lc.getString(R.string.automation_import_nav_search, "stacja paliw")))
        assertTrue(search, search.contains(lc.getString(R.string.automation_import_nav_maps)))
        assertFalse(search, search.contains(lc.getString(R.string.automation_import_nav_go)))
        assertFalse(search, search.contains(lc.getString(R.string.automation_import_nav_show)))
    }

    @Test fun `import preview names the app a close step closes`() {
        val rule = SharedRuleFixture.withActions(
            ActionDef("", "x", "app_close", """{"packageName":"com.example.radio","appLabel":"Radio"}"""),
        )
        val preview = RuleImportSummary.preview(rule, ctx, realDispatcher()::autoGoWillRun)
        assertEquals("Zamknij aplikację: com.example.radio", preview.actions[0])
    }

    @Test fun `import preview names the media key`() {
        val rule = SharedRuleFixture.withActions(
            ActionDef("", "x", "media_key", "play"),
            ActionDef("", "x", "media_key", "pause"),
        )
        val preview = RuleImportSummary.preview(rule, ctx, realDispatcher()::autoGoWillRun)
        assertEquals(listOf("Multimedia: odtwórz", "Multimedia: pauza"), preview.actions)
    }

    @Test fun `import preview names the screen step`() {
        val rule = SharedRuleFixture.withActions(
            ActionDef("screen", "x", "screen", "0"),
            ActionDef("screen", "x", "screen", "1"),
        )
        val preview = RuleImportSummary.preview(rule, ctx, realDispatcher()::autoGoWillRun)
        assertEquals(listOf("Ekran: wyłącz", "Ekran: włącz"), preview.actions)
    }

    @Test fun `import preview promises Start only where the dispatcher presses it`() {
        val lc = ctx.appLocalizedContext()
        val go = lc.getString(R.string.automation_import_nav_go)
        val show = lc.getString(R.string.automation_import_nav_show)
        val rule = SharedRuleFixture.withActions(
            ActionDef("", "Działka", "navigate", """{"lat":54.1,"lon":27.2,"name":"Działka","go":true}"""),
            ActionDef("", "Działka", "navigate", """{"lat":54.1,"lon":27.2,"name":"Działka","go":true,"app":"maps"}"""),
        )
        val dispatcher = realDispatcher()
        val navigator = RuleImportSummary.preview(rule, ctx, dispatcher::autoGoWillRun).actions[0]
        val maps = RuleImportSummary.preview(rule, ctx, dispatcher::autoGoWillRun).actions[1]
        assertTrue(navigator, navigator.contains(go))
        // Maps builds the route but has no Start action the dispatcher can press: no promise,
        // and no show-point-only outcome either, since a route is still built.
        assertFalse(maps, maps.contains(go))
        assertFalse(maps, maps.contains(show))
        assertTrue(maps, maps.contains(lc.getString(R.string.automation_import_nav_maps)))
    }

    private fun realDispatcher() = ActionDispatcher(
        mockk(relaxed = true), mockk(relaxed = true), ctx,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        com.bydmate.app.util.AppStrings(ctx), dagger.Lazy { io.mockk.mockk(relaxed = true) },
    )

    private object SharedRuleFixture {
        fun withActions(vararg actions: ActionDef) = SharedRule(
            name = "Navi", triggerLogic = "AND",
            triggers = listOf(TriggerDef("Speed", "车速", ">", "7", "Prędkość")),
            actions = actions.toList(), cooldownSeconds = 60, requirePark = false,
            confirmBeforeExecute = false, fireOncePerTrip = false, playSound = false,
        )
    }
}
