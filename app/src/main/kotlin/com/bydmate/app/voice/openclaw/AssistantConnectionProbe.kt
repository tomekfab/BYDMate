package com.bydmate.app.voice.openclaw

import com.bydmate.app.voice.AssistantReply
import com.bydmate.app.voice.TtsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the connection test on the settings screen shows. All texts are Polish. */
data class AssistantProbeState(
    val running: Boolean = false,
    /** What was sent, so the screen can show the exchange. */
    val sent: String? = null,
    val reply: String? = null,
    val error: String? = null,
    /** A previous send may have reached the assistant but its answer is unknown; offered explicitly. */
    val canResend: Boolean = false,
    /** Whether the reply was spoken by the local Polish voice, or why it stayed text-only. */
    val audio: String? = null,
)

/**
 * The assistant connection test: types Polish text, shows the answer. It exists so the network,
 * pairing and chat can be checked on a phone or emulator without a car, a microphone, any speech
 * model or the Polish profile. It goes through [OpenClawAssistant.ask], the same transport,
 * identity and conversation as the voice session, and needs the same separate opt-in.
 *
 * It has no vehicle collaborator on purpose: nothing here can dispatch a car action. The reply
 * is spoken only by the local Polish voice, and only when that voice is installed and verified;
 * otherwise the screen says so and the answer stays text. It never substitutes a cloud or
 * Russian voice.
 */
class AssistantConnectionProbe(
    private val assistant: OpenClawAssistant,
    /** The dedicated local Polish engine (Gosia), independent of the active profile. */
    private val polishVoice: TtsEngine,
    private val voiceVerified: () -> Boolean,
) {
    private val state = MutableStateFlow(AssistantProbeState())
    val probe: StateFlow<AssistantProbeState> = state.asStateFlow()

    /** Sends [text] and shows the answer. Returns when the turn ended (answer, failure or cancel). */
    suspend fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            state.value = AssistantProbeState(error = PolishAssistantText.failure(OpenClawFailure.EmptyRequest))
            return
        }
        polishVoice.stop()
        state.value = AssistantProbeState(running = true, sent = trimmed)
        finish(assistant.askText(trimmed))
    }

    /** Repeats the last uncertain send. Only an explicit tap calls this. */
    suspend fun resend() {
        val sent = state.value.sent
        state.value = AssistantProbeState(running = true, sent = sent)
        finish(assistant.resendUncertain())
    }

    /** Stops the running turn and any playback. */
    suspend fun cancel() {
        polishVoice.stop()
        assistant.cancel()
    }

    private fun finish(reply: AssistantReply) {
        when (reply) {
            is AssistantReply.Answer -> state.update {
                it.copy(running = false, reply = reply.text, error = null, canResend = false, audio = speak(reply.text))
            }
            // Cannot come from askText; shown as text so the probe never acts on it.
            is AssistantReply.Proposal -> state.update {
                it.copy(running = false, reply = reply.prose, error = null, canResend = false, audio = speak(reply.prose))
            }
            AssistantReply.Aborted -> state.update {
                it.copy(running = false, reply = null, error = ABORTED, canResend = false, audio = null)
            }
            is AssistantReply.Problem -> state.update {
                it.copy(running = false, reply = null, error = reply.detail, canResend = reply.uncertain, audio = null)
            }
        }
    }

    private fun speak(text: String): String {
        if (!voiceVerified()) return AUDIO_NO_VOICE
        return if (polishVoice.speak(text)) AUDIO_SPOKEN else AUDIO_FAILED
    }

    companion object {
        const val ABORTED = "Anulowano."
        const val AUDIO_SPOKEN = "Odpowiedź odtwarzana lokalnym głosem Gosia."
        const val AUDIO_NO_VOICE = "Tylko tekst: pobierz głos Gosia, aby usłyszeć odpowiedź."
        const val AUDIO_FAILED = "Tylko tekst: głos Gosia nie wystartował."
    }
}
