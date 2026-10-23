package com.bydmate.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import com.bydmate.app.voice.PolishModelManifest
import com.bydmate.app.voice.TtsVoiceCatalog
import com.bydmate.app.voice.VoiceLanguage
import com.bydmate.app.voice.VoiceLanguageState
import com.bydmate.app.voice.openclaw.AssistantProbeState
import com.bydmate.app.voice.openclaw.OpenClawLinkState
import com.bydmate.app.voice.openclaw.OpenClawRecoveryState
import com.bydmate.app.voice.openclaw.OpenClawStatus
import com.bydmate.app.voice.openclaw.PolishAssistantText

/** Test tags of the Polish voice section, shared with the instrumentation test. */
object PolishVoiceTags {
    const val ENABLE = "openclaw_enable"
    const val SPOKEN_REPLY_ENABLE = "spoken_reply_enable"
    const val ENDPOINT = "openclaw_endpoint"
    const val PAIR = "openclaw_pair"
    const val STATUS = "openclaw_status"
    const val PROBE_INPUT = "assistant_probe_input"
    const val PROBE_SEND = "assistant_probe_send"
    const val PROBE_REPLY = "assistant_probe_reply"
    const val PROBE_ERROR = "assistant_probe_error"
    const val PROBE_AUDIO = "assistant_probe_audio"
}

/** Everything the section can ask for; a seam so the section is plain UI over [SettingsViewModel]. */
data class PolishVoiceActions(
    val downloadAsr: () -> Unit,
    val cancelAsr: () -> Unit,
    val deleteAsr: () -> Unit,
    val downloadVoice: () -> Unit,
    val cancelVoice: () -> Unit,
    val deleteVoice: () -> Unit,
    val activatePolish: () -> Unit,
    val setAssistantEnabled: (Boolean) -> Unit,
    val setEndpoint: (String) -> Unit,
    val pair: () -> Unit,
    val cancelPairing: () -> Unit,
    val reconnect: () -> Unit,
    val disconnect: () -> Unit,
    val resetConversation: () -> Unit,
    val setSpokenRepliesEnabled: (Boolean) -> Unit,
    val sendTest: (String) -> Unit,
    val resendTest: () -> Unit,
    val cancelTest: () -> Unit,
)

/**
 * Voice language and the optional Polish assistant. All labels are Polish on purpose: the Polish
 * setup must stay readable when the app is shown in another language. The connection test needs
 * no microphone, speech model, Polish profile or car.
 */
@Composable
internal fun PolishVoiceSettingsSection(viewModel: SettingsViewModel) {
    val settings by viewModel.uiState.collectAsState()
    val language by viewModel.voiceLanguageState.collectAsState()
    val assistant by viewModel.openClawStatus.collectAsState()
    val recovery by viewModel.openClawRecovery.collectAsState()
    val probe by viewModel.assistantProbeState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshVoiceLanguage() }
    PolishVoiceSettingsContent(
        language = language,
        spokenRepliesEnabled = settings.ttsEnabled,
        assistant = assistant,
        recovery = recovery,
        probe = probe,
        actions = PolishVoiceActions(
            downloadAsr = viewModel::downloadPolishAsr,
            cancelAsr = viewModel::cancelPolishAsrDownload,
            deleteAsr = viewModel::deletePolishAsr,
            downloadVoice = viewModel::downloadPolishVoice,
            cancelVoice = viewModel::cancelPolishVoiceDownload,
            deleteVoice = viewModel::deletePolishVoice,
            activatePolish = viewModel::activatePolish,
            setAssistantEnabled = viewModel::setOpenClawEnabled,
            setEndpoint = viewModel::setOpenClawEndpoint,
            pair = viewModel::pairOpenClaw,
            cancelPairing = viewModel::cancelOpenClawPairing,
            reconnect = viewModel::reconnectOpenClaw,
            disconnect = viewModel::disconnectOpenClaw,
            resetConversation = viewModel::resetOpenClawConversation,
            setSpokenRepliesEnabled = viewModel::setTtsEnabled,
            sendTest = viewModel::sendAssistantTest,
            resendTest = viewModel::resendAssistantTest,
            cancelTest = viewModel::cancelAssistantTest,
        ),
    )
}

@Composable
internal fun PolishVoiceSettingsContent(
    language: VoiceLanguageState,
    spokenRepliesEnabled: Boolean,
    assistant: OpenClawStatus,
    recovery: OpenClawRecoveryState,
    probe: AssistantProbeState,
    actions: PolishVoiceActions,
) {
    SectionTitle("Język głosu: polski (offline)")
    LanguageCard(language, actions)
    SectionTitle(stringResource(R.string.settings_tts_header))
    SpokenRepliesCard(spokenRepliesEnabled, actions)
    SectionTitle("Asystent online (OpenClaw), tylko tekst")
    AssistantCard(assistant, recovery, actions)
    SectionTitle("Test połączenia z asystentem")
    ProbeCard(assistant, probe, actions)
}

@Composable
private fun SpokenRepliesCard(spokenRepliesEnabled: Boolean, actions: PolishVoiceActions) {
    SectionCard {
        SettingToggleRow(
            title = stringResource(R.string.settings_tts_toggle),
            description = stringResource(R.string.settings_tts_subtitle),
            traceId = PolishVoiceTags.SPOKEN_REPLY_ENABLE,
            checked = spokenRepliesEnabled,
            onCheckedChange = actions.setSpokenRepliesEnabled,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) { content() }
    }
}

private fun mb(bytes: Long) = bytes / (1024 * 1024)

@Composable
private fun LanguageCard(state: VoiceLanguageState, actions: PolishVoiceActions) {
    val asrSizeMb = mb(PolishModelManifest.PARAKEET_TDT_V3_INT8.downloads.sumOf { it.sizeBytes })
    val voiceSizeMb = mb(PolishModelManifest.GOSIA_MEDIUM_INT8.downloads.sumOf { it.sizeBytes })
    val gosia = TtsVoiceCatalog.defaultFor().displayName // labelRes is 0 for Gosia
    SectionCard {
        SettingStatusRow(
            title = "Aktywny język głosu",
            description = "Polski",
            ok = true,
        )
        SettingDivider()
        ModelRow(
            title = "Rozpoznawanie mowy (Parakeet)",
            ready = state.asrReady,
            progress = state.asrProgress,
            failed = state.asrFailed,
            blocked = state.asrBlocked,
            sizeMb = asrSizeMb,
            shortfallMb = state.asrRequiredBytes?.let { mb(it) to mb(state.asrAvailableBytes ?: 0) },
            onDownload = actions.downloadAsr, onCancel = actions.cancelAsr, onDelete = actions.deleteAsr,
        )
        SettingDivider()
        ModelRow(
            title = "Głos $gosia (lokalny)",
            ready = state.voiceReady,
            progress = state.voiceProgress,
            failed = state.voiceFailed,
            blocked = state.voiceBlocked,
            sizeMb = voiceSizeMb,
            shortfallMb = null,
            onDownload = actions.downloadVoice, onCancel = actions.cancelVoice, onDelete = actions.deleteVoice,
        )
        SettingDivider()
        SettingActionRow(
            title = "Sprawdź gotowość języka polskiego",
            description = "Sprawdzenie obejmuje oba modele i nie włącza mikrofonu.",
            buttonLabel = if (state.activating) "Sprawdzam…" else "Sprawdź",
            onClick = actions.activatePolish,
            style = SettingButtonStyle.Primary,
            enabled = !state.activating,
        )
        state.activationProblems.forEach { problem ->
            Text(problem, color = SocRed, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
        SettingHint("Mowa jest rozpoznawana na urządzeniu, a dźwięk nie opuszcza samochodu. Modele pobierasz jednorazowo.")
    }
}

@Composable
private fun ModelRow(
    title: String,
    ready: Boolean,
    progress: Int,
    failed: Boolean,
    blocked: Boolean,
    sizeMb: Long,
    shortfallMb: Pair<Long, Long>?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    when {
        progress >= 0 -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentGreen)
                Text("$title: pobieranie $progress%", color = TextSecondary, fontSize = 12.sp)
            }
            SettingActionRow(title = "Pobieranie w toku", buttonLabel = "Anuluj", onClick = onCancel)
        }
        ready -> SettingActionRow(
            title = title,
            description = "Gotowy",
            buttonLabel = "Usuń",
            onClick = onDelete,
            style = SettingButtonStyle.Warning,
        )
        else -> {
            if (blocked) {
                Text(
                    "$title: ładowanie zatrzymane po awarii. Usuń i pobierz ponownie.",
                    color = SocRed, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp),
                )
            } else if (failed) {
                Text(
                    if (shortfallMb != null) {
                        "Za mało miejsca: potrzeba ${shortfallMb.first} MB, dostępne ${shortfallMb.second} MB."
                    } else {
                        "Pobieranie nie powiodło się. Spróbuj ponownie."
                    },
                    color = SocRed, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            SettingActionRow(
                title = title,
                description = "Nie pobrano",
                buttonLabel = "Pobierz ($sizeMb MB)",
                onClick = if (blocked) onDelete else onDownload,
                style = SettingButtonStyle.Primary,
            )
        }
    }
}

@Composable
private fun AssistantCard(
    status: OpenClawStatus,
    recovery: OpenClawRecoveryState,
    actions: PolishVoiceActions,
) {
    val isPaired = when (status.link) {
        OpenClawLinkState.Ready, OpenClawLinkState.Disconnected, OpenClawLinkState.Connecting -> true
        else -> false
    }
    SectionCard {
        SettingHint(
            "Wysyła wyłącznie rozpoznany tekst, nigdy dźwięk. Działa tylko wtedy, gdy go włączysz.",
        )
        SettingToggleRow(
            title = "Włącz asystenta OpenClaw",
            traceId = PolishVoiceTags.ENABLE,
            checked = status.enabled,
            onCheckedChange = actions.setAssistantEnabled,
        )
        SettingDivider()
        var endpoint by remember(status.endpoint) { mutableStateOf(status.endpoint) }
        val locked = isPaired || status.link is OpenClawLinkState.AwaitingApproval
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it; actions.setEndpoint(it) },
            label = { Text("Adres bramy (wss://…)") },
            singleLine = true,
            enabled = !locked,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(PolishVoiceTags.ENDPOINT),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = AccentGreen,
                unfocusedBorderColor = CardBorder,
                focusedLabelColor = AccentGreen,
                unfocusedLabelColor = TextSecondary,
                cursorColor = AccentGreen,
            ),
        )
        if (locked) SettingHint("Aby zmienić adres, najpierw rozłącz urządzenie.")
        SettingDivider()
        SettingStatusRow(
            title = "Stan połączenia",
            description = PolishAssistantText.link(status),
            ok = status.enabled && status.link == OpenClawLinkState.Ready,
        )
        SettingDivider()
        SettingStatusRow(
            title = "Automatyczne odzyskiwanie połączenia",
            description = PolishAssistantText.recovery(recovery),
            ok = recovery == OpenClawRecoveryState.Ready,
        )
        (status.link as? OpenClawLinkState.AwaitingApproval)?.requestId?.let { id ->
            SettingHint("Identyfikator prośby: $id. Zatwierdź urządzenie na komputerze z asystentem.")
        }
        when (status.link) {
            OpenClawLinkState.Connecting, is OpenClawLinkState.AwaitingApproval -> SettingActionRow(
                title = "Parowanie w toku",
                buttonLabel = "Anuluj",
                onClick = actions.cancelPairing,
            )
            OpenClawLinkState.Disconnected -> SettingActionRow(
                title = "Sparowano, rozłączono",
                buttonLabel = "Połącz ponownie",
                onClick = actions.reconnect,
                style = SettingButtonStyle.Primary,
                enabled = status.enabled && status.endpointValid,
            )
            OpenClawLinkState.Ready -> Unit
            else -> SettingActionRow(
                title = "Sparuj to urządzenie",
                description = "Wymaga włączenia asystenta i adresu bramy.",
                buttonLabel = "Sparuj",
                onClick = actions.pair,
                style = SettingButtonStyle.Primary,
                enabled = status.enabled && status.endpointValid,
            )
        }
        if (status.link != OpenClawLinkState.Unpaired) {
            SettingDivider()
            SettingActionRow(
                title = "Nowa rozmowa",
                description = "Zaczyna świeżą rozmowę z asystentem. Parowanie zostaje.",
                buttonLabel = "Nowa rozmowa",
                onClick = actions.resetConversation,
                enabled = isPaired,
            )
            SettingDivider()
            SettingActionRow(
                title = "Odłącz to urządzenie",
                description = "Usuwa klucz i rozmowę z telefonu. Odwołaj też urządzenie na komputerze z asystentem.",
                buttonLabel = "Odłącz",
                onClick = actions.disconnect,
                style = SettingButtonStyle.Warning,
            )
        }
    }
}

@Composable
private fun ProbeCard(status: OpenClawStatus, probe: AssistantProbeState, actions: PolishVoiceActions) {
    SectionCard {
        SettingHint(
            "Test połączenia: wpisz wiadomość po polsku i zobacz odpowiedź asystenta. " +
                "Nie potrzebuje mikrofonu, modeli mowy, profilu polskiego ani samochodu.",
        )
        var message by remember { mutableStateOf("") }
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Wiadomość testowa") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(PolishVoiceTags.PROBE_INPUT),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = AccentGreen,
                unfocusedBorderColor = CardBorder,
                focusedLabelColor = AccentGreen,
                unfocusedLabelColor = TextSecondary,
                cursorColor = AccentGreen,
            ),
        )
        if (probe.running) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentGreen)
                Text("Czekam na odpowiedź asystenta…", color = TextSecondary, fontSize = 12.sp)
            }
            SettingActionRow(title = "Anuluj wysyłanie", buttonLabel = "Anuluj", onClick = actions.cancelTest)
        } else {
            SettingActionRow(
                title = "Wyślij do asystenta",
                description = if (!status.enabled) "Najpierw włącz asystenta OpenClaw." else null,
                buttonLabel = "Wyślij",
                onClick = { actions.sendTest(message) },
                style = SettingButtonStyle.Primary,
                enabled = status.enabled,
            )
        }
        probe.sent?.let { Text("Wysłano: $it", color = TextSecondary, fontSize = 12.sp) }
        probe.reply?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.testTag(PolishVoiceTags.PROBE_REPLY))
        }
        probe.error?.let {
            Text(it, color = SocRed, fontSize = 13.sp, modifier = Modifier.testTag(PolishVoiceTags.PROBE_ERROR))
        }
        probe.audio?.let {
            Text(it, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.testTag(PolishVoiceTags.PROBE_AUDIO))
        }
        if (probe.canResend) {
            SettingActionRow(
                title = "Nie wiadomo, czy wiadomość dotarła",
                buttonLabel = "Wyślij ponownie",
                onClick = actions.resendTest,
                style = SettingButtonStyle.Warning,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
