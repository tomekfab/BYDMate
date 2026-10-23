package com.bydmate.app.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI contract for the explicit local spoken-reply opt-in. */
class PolishVoiceSettingsSectionTest {
    private val source: String by lazy {
        File(projectRoot(), SOURCE).readText()
    }
    private val viewModelSource: String by lazy { File(projectRoot(), VIEW_MODEL_SOURCE).readText() }

    @Test fun `spoken replies have an accessible disabled-by-default opt-in wired to the view model`() {
        assertTrue("missing stable selector", source.contains("const val SPOKEN_REPLY_ENABLE = \"spoken_reply_enable\""))
        assertTrue("the existing ViewModel setting must default off", viewModelSource.contains("val ttsEnabled: Boolean = false"))
        assertTrue("missing ViewModel callback seam", source.contains("setSpokenRepliesEnabled: (Boolean) -> Unit"))
        assertTrue("toggle must use the state value", source.contains("checked = spokenRepliesEnabled"))
        assertTrue("toggle must expose its selector", source.contains("traceId = PolishVoiceTags.SPOKEN_REPLY_ENABLE"))
        assertTrue("toggle must persist through the existing ViewModel API", source.contains("setSpokenRepliesEnabled = viewModel::setTtsEnabled"))
    }

    @Test fun `recovery state is collected forwarded and shown without transport diagnostics`() {
        assertTrue("missing recovery state import", source.contains("import com.bydmate.app.voice.openclaw.OpenClawRecoveryState"))
        assertTrue("missing recovery state collection", source.contains("val recovery by viewModel.openClawRecovery.collectAsState()"))
        assertTrue("missing recovery parameter forwarding", source.contains("recovery = recovery,"))
        assertTrue("content must accept recovery state", source.contains("recovery: OpenClawRecoveryState,"))
        assertTrue("assistant card must receive recovery state", source.contains("AssistantCard(assistant, recovery, actions)"))
        assertTrue("missing recovery status row", source.contains("title = \"Automatyczne odzyskiwanie połączenia\""))
        assertTrue("recovery row must use the safe text mapper", source.contains("description = PolishAssistantText.recovery(recovery)"))
    }

    private companion object {
        const val SOURCE = "app/src/main/kotlin/com/bydmate/app/ui/settings/PolishVoiceSettingsSection.kt"
        const val VIEW_MODEL_SOURCE = "app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt"
    }

    private fun projectRoot(): File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .firstOrNull { File(it, SOURCE).isFile }
        ?: error("Cannot find $SOURCE from ${File(".").canonicalPath}")
}
