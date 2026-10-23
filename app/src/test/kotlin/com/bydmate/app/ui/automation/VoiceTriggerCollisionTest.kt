package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.voice.AliasTarget
import com.bydmate.app.voice.SaveResult
import com.bydmate.app.voice.VoicePhrase
import com.bydmate.app.voice.VoiceTriggerValidation
import com.bydmate.app.voice.VoiceUserPhrases
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Voice-trigger collisions: the pure check, and the editor save reading the one injected store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTriggerCollisionTest {
    @Test fun `duplicate phrase across rules is OtherRule`() {
        val taken = mapOf(VoicePhrase.normalize("nawigacja") to "Mapa")
        assertEquals(VoiceTriggerValidation.Collision.OtherRule("Mapa"),
            VoiceTriggerValidation.check("Nawigacja", taken))
    }
    @Test fun `unique phrase is None`() {
        assertEquals(VoiceTriggerValidation.Collision.None,
            VoiceTriggerValidation.check("jedziemy", mapOf(VoicePhrase.normalize("nawigacja") to "Mapa")))
    }

    // A destination alias saved a moment ago is already an owner when the editor saves.
    private val testDispatcher = StandardTestDispatcher()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val ruleDao = mockk<RuleDao>(relaxed = true)
    private val store = VoiceUserPhrases()
    private val windowClose = ActionDef("车窗关闭", "Zamknij wszystkie szyby")

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        LocalePreferences(ctx).setLanguage("en")
        ctx.getSharedPreferences("automation", Context.MODE_PRIVATE).edit()
            .putBoolean("templates_inserted", true).putBoolean("templates_tg_report_inserted", true).commit()
        every { ruleDao.getAll() } returns MutableStateFlow(emptyList())
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun voice(phrase: String) =
        TriggerDef(param = "Voice", chineseName = "语音", operator = "==", value = phrase, displayName = phrase, kind = "voice")

    private fun vm(rules: List<RuleEntity> = emptyList()): AutomationViewModel {
        every { ruleDao.getAll() } returns MutableStateFlow(rules)
        return AutomationViewModel(
            ruleDao = ruleDao,
            ruleLogDao = mockk<RuleLogDao>(relaxed = true) { every { getRecent(any()) } returns flowOf(emptyList()) },
            placeRepository = mockk<PlaceRepository>(relaxed = true) { every { getAll() } returns MutableStateFlow(emptyList()) },
            vehicleApi = mockk(relaxed = true),
            actionDispatcher = mockk(relaxed = true),
            settingsRepository = mockk(relaxed = true),
            context = ctx,
            userPhrases = store,
        ).also { testDispatcher.scheduler.advanceUntilIdle() }
    }

    private fun AutomationViewModel.saveNewWith(phrase: String) {
        openNewRule()
        updateEditing { copy(name = "Voice", triggers = listOf(voice(phrase)), actions = listOf(windowClose)) }
        saveRule()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun `a destination alias saved just now blocks the same voice trigger`() {
        assertEquals(SaveResult.Saved, store.saveAlias(AliasTarget.NavigateQuery("Rynek"), "jedź na rynek"))
        val vm = vm()

        vm.saveNewWith("Jedź na rynek!")

        assertTrue(vm.uiState.value.editorError.orEmpty(), vm.uiState.value.editorError.orEmpty().contains("Nawiguj do: Rynek"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun `an alias keeps its phrase while another rule is disabled`() {
        assertEquals(SaveResult.Saved, store.saveAlias(AliasTarget.NavigateShortcut("home"), "wracam"))
        val disabled = RuleEntity(id = 5, name = "Old", enabled = false, triggers = TriggerDef.listToJson(listOf(voice("stary tekst"))), actions = "[]")
        val vm = vm(listOf(disabled))

        vm.saveNewWith("wracam")
        assertTrue(vm.uiState.value.editorError.orEmpty(), vm.uiState.value.editorError.orEmpty().contains("Nawiguj do domu"))

        vm.saveNewWith("Stary tekst")
        assertTrue(vm.uiState.value.editorError.orEmpty(), vm.uiState.value.editorError.orEmpty().contains("Old"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }

    @Test fun `an alias phrase that is free saves the rule`() {
        coEvery { ruleDao.insert(any()) } returns 1L
        val vm = vm()

        vm.saveNewWith("zupełnie inna fraza")

        coVerify(exactly = 1) { ruleDao.insert(any()) }
    }
}
