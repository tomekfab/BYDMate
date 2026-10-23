package com.bydmate.app.ui.widget

import com.bydmate.app.voice.VoiceLanguage
import com.bydmate.app.voice.VoiceStage
import com.bydmate.app.voice.VoiceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetVoiceStatusBindingTest {
    @After fun clear() { WidgetController.applyVoiceStatusForTest(VoiceStatus.IDLE) }

    @Test fun `reattach receives current status without replaying old words`() = runTest {
        val processing = VoiceStatus(VoiceStage.PROCESSING, "otwórz okno", false, VoiceLanguage.PL)
        val done = processing.copy(stage = VoiceStage.DONE)
        val source = MutableStateFlow(processing)
        val first = mutableListOf<VoiceStatus>()
        val beforeDetach = backgroundScope.launch { WidgetController.collectVoiceStatus(source, first::add) }
        runCurrent()
        assertEquals(listOf(processing), first)
        beforeDetach.cancel()
        runCurrent()
        source.value = done
        val after = mutableListOf<VoiceStatus>()
        backgroundScope.launch { WidgetController.collectVoiceStatus(source, after::add) }
        runCurrent()
        assertEquals(listOf(done), after)
        assertEquals(listOf(processing), first)
    }

    @Test fun `detach clears the visible command`() {
        WidgetController.applyVoiceStatusForTest(
            VoiceStatus(VoiceStage.PROCESSING, "otwórz okno", false, VoiceLanguage.PL),
        )
        WidgetController.detach("test")
        assertEquals(VoiceStatus.IDLE, WidgetController.voiceStatusForTest())
    }

    @Test fun `widget keeps the existing anchor dimensions`() {
        assertEquals(260, WidgetStyle.WIDTH_DP)
        assertEquals(108, WidgetStyle.HEIGHT_DP)
    }
}
