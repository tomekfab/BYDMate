package com.bydmate.app.ui.widget

import com.bydmate.app.voice.VoiceLanguage
import com.bydmate.app.voice.VoiceStage
import com.bydmate.app.voice.VoiceStatus
import com.bydmate.app.voice.voiceStatusDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WidgetPanelModeTest {

    private fun status(
        stage: VoiceStage,
        heard: String? = null,
        speaking: Boolean = false,
        language: VoiceLanguage = VoiceLanguage.PL,
    ) = VoiceStatus(stage, heard, speaking, language)

    private val terminalStages = listOf(
        VoiceStage.STARTED, VoiceStage.CONFIRM_ON_SCREEN, VoiceStage.DONE, VoiceStage.ANSWERED,
        VoiceStage.REFUSED, VoiceStage.NOT_UNDERSTOOD, VoiceStage.FAILED,
    )

    private val busyStages = listOf(
        VoiceStage.PREPARING, VoiceStage.PROCESSING, VoiceStage.RUNNING_COMMAND,
        VoiceStage.WAITING_ASSISTANT,
    )

    @Test
    fun `idle and not listening shows data`() {
        assertEquals(WidgetPanelMode.DATA, widgetPanelMode(false, VoiceStatus.IDLE))
    }

    @Test
    fun `idle and listening shows data with snake`() {
        assertEquals(WidgetPanelMode.DATA_LISTENING, widgetPanelMode(true, VoiceStatus.IDLE))
    }

    @Test
    fun `listening stage shows data with snake whatever listening flag is`() {
        val listening = status(VoiceStage.LISTENING)
        assertEquals(WidgetPanelMode.DATA_LISTENING, widgetPanelMode(true, listening))
        assertEquals(WidgetPanelMode.DATA_LISTENING, widgetPanelMode(false, listening))
    }

    @Test
    fun `busy stages show voice panel whatever listening flag is`() {
        for (stage in busyStages) {
            assertEquals("$stage listening=true", WidgetPanelMode.VOICE, widgetPanelMode(true, status(stage)))
            assertEquals("$stage listening=false", WidgetPanelMode.VOICE, widgetPanelMode(false, status(stage)))
        }
    }

    @Test
    fun `every terminal stage shows voice panel whatever listening flag is`() {
        assertEquals(7, terminalStages.size)
        for (stage in terminalStages) {
            assertEquals("$stage listening=true", WidgetPanelMode.VOICE, widgetPanelMode(true, status(stage)))
            assertEquals("$stage listening=false", WidgetPanelMode.VOICE, widgetPanelMode(false, status(stage)))
        }
    }

    @Test
    fun `data returns with snake after terminal status is replaced by listening`() {
        val terminal = status(VoiceStage.DONE, heard = "zamknij okna")
        assertEquals(WidgetPanelMode.VOICE, widgetPanelMode(true, terminal))
        assertEquals(WidgetPanelMode.DATA_LISTENING, widgetPanelMode(true, status(VoiceStage.LISTENING)))
    }

    @Test
    fun `data returns after terminal status is replaced by idle`() {
        val terminal = status(VoiceStage.FAILED, heard = "zamknij okna")
        assertEquals(WidgetPanelMode.VOICE, widgetPanelMode(false, terminal))
        assertEquals(WidgetPanelMode.DATA, widgetPanelMode(false, VoiceStatus.IDLE))
    }

    @Test
    fun `speaking never changes the mode`() {
        for (stage in VoiceStage.values()) {
            for (listening in listOf(true, false)) {
                assertEquals(
                    "$stage listening=$listening",
                    widgetPanelMode(listening, status(stage, speaking = false)),
                    widgetPanelMode(listening, status(stage, speaking = true)),
                )
            }
        }
    }

    @Test
    fun `Polish done with words reads label and words`() {
        assertEquals(
            "Wykonano: zamknij okna",
            voiceStatusDescription(status(VoiceStage.DONE, heard = "zamknij okna")),
        )
    }

    @Test
    fun `polish refused without words reads label only`() {
        assertEquals(
            "Odmowa",
            voiceStatusDescription(status(VoiceStage.REFUSED, language = VoiceLanguage.PL)),
        )
    }

    @Test
    fun `failed description differs from done description for the same words`() {
        assertNotEquals(
            voiceStatusDescription(status(VoiceStage.DONE, heard = "zamknij okna")),
            voiceStatusDescription(status(VoiceStage.FAILED, heard = "zamknij okna")),
        )
    }
}
