package com.bydmate.app.voice.openclaw

import com.bydmate.app.voice.TtsEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The settings connection test: typed Polish text in, the answer out, through the production
 * [OpenClawAssistant]. Nothing in this rig is a microphone, a speech model, a profile or a car:
 * the probe has no such collaborator, and these tests run it with none installed.
 */
class AssistantConnectionProbeTest {

    private class Prefs(var on: Boolean = true, var endpoint: String = "wss://gateway.example.ts.net/") : OpenClawPrefs {
        override fun isEnabled() = on
        override fun setEnabled(enabled: Boolean) { on = enabled }
        override fun endpointText() = endpoint
        override fun setEndpointText(text: String) { endpoint = text }
    }

    private class Credentials : OpenClawCredentials {
        override fun exist() = false
        override fun clear() = Unit
    }

    private class FakeChat : OpenClawChat {
        override val linkState: StateFlow<OpenClawLinkState> = MutableStateFlow(OpenClawLinkState.Ready)
        val asked = mutableListOf<String>()
        val actionAsked = mutableListOf<String>()
        val resent = AtomicInteger()
        val cancels = AtomicInteger()
        @Volatile var resendScript: () -> OpenClawTurnResult = { OpenClawTurnResult.Answer("Odpowiedź po jawnym ponowieniu.") }
        @Volatile var script: suspend (String) -> OpenClawTurnResult = { OpenClawTurnResult.Answer("Cześć! Tu Bob.") }
        override suspend fun connect() = OpenClawLinkState.Ready
        override suspend fun pair() = OpenClawLinkState.Ready
        override suspend fun ask(text: String, onDelta: (String) -> Unit): OpenClawTurnResult { asked += text; return script(text) }
        override suspend fun askForActions(text: String, onDelta: (String) -> Unit): OpenClawTurnResult {
            actionAsked += text
            return script(text)
        }
        override suspend fun resendUncertain(onDelta: (String) -> Unit): OpenClawTurnResult {
            resent.incrementAndGet()
            return resendScript()
        }
        override suspend fun cancel() { cancels.incrementAndGet() }
        override suspend fun resetConversation() = Unit
        override suspend fun disconnect() = Unit
    }

    private class Rig(prefs: Prefs = Prefs(), voiceVerified: Boolean = false, speaks: Boolean = true) {
        val chat = FakeChat()
        val voice = mockk<TtsEngine>(relaxed = true) { every { speak(any()) } returns speaks }
        val assistant = OpenClawAssistant(prefs, { chat }, Credentials())
        val probe = AssistantConnectionProbe(assistant, voice) { voiceVerified }
    }

    @Test fun `typed Polish text gets an answer with no models, no profile and no car`() = runBlocking {
        val r = Rig(voiceVerified = false)

        r.probe.send("  Cześć, kim jesteś?  ")

        val state = r.probe.probe.value
        assertEquals(listOf("Cześć, kim jesteś?"), r.chat.asked)
        assertEquals("Cześć! Tu Bob.", state.reply)
        assertNull(state.error)
        assertFalse(state.running)
        assertEquals(AssistantConnectionProbe.AUDIO_NO_VOICE, state.audio)
        verify(exactly = 0) { r.voice.speak(any()) }
    }

    @Test fun `without Gosia the reply stays text and no other voice is used`() = runBlocking {
        val r = Rig(voiceVerified = false)
        r.probe.send("Cześć")
        verify(exactly = 0) { r.voice.speak(any()) }
        verify(exactly = 0) { r.voice.speakOffline(any()) }
        assertEquals(AssistantConnectionProbe.AUDIO_NO_VOICE, r.probe.probe.value.audio)
    }

    @Test fun `with verified Gosia the reply is spoken by the local Polish voice, without ASR or profile`() = runBlocking {
        val r = Rig(voiceVerified = true)

        r.probe.send("Cześć")

        verify(exactly = 1) { r.voice.speak("Cześć! Tu Bob.") }
        assertEquals(AssistantConnectionProbe.AUDIO_SPOKEN, r.probe.probe.value.audio)
    }

    @Test fun `a voice that does not start is reported as text-only`() = runBlocking {
        val r = Rig(voiceVerified = true, speaks = false)
        r.probe.send("Cześć")
        assertEquals(AssistantConnectionProbe.AUDIO_FAILED, r.probe.probe.value.audio)
        assertEquals("Cześć! Tu Bob.", r.probe.probe.value.reply)
    }

    @Test fun `the test needs the same separate opt-in and sends nothing without it`() = runBlocking {
        val r = Rig(prefs = Prefs(on = false))

        r.probe.send("Cześć")

        assertEquals(emptyList<String>(), r.chat.asked)
        assertEquals(PolishAssistantText.DISABLED, r.probe.probe.value.error)
        assertNull(r.probe.probe.value.reply)
    }

    @Test fun `blank input is refused before anything is sent`() = runBlocking {
        val r = Rig()
        r.probe.send("   ")
        assertEquals(emptyList<String>(), r.chat.asked)
        assertEquals(PolishAssistantText.failure(OpenClawFailure.EmptyRequest), r.probe.probe.value.error)
    }

    @Test fun `a transport failure shows a Polish line and no other provider is tried`() = runBlocking {
        val r = Rig()
        r.chat.script = { OpenClawTurnResult.Failed(OpenClawFailure.NetworkUnavailable) }

        r.probe.send("Cześć")

        assertEquals(1, r.chat.asked.size)
        assertEquals(PolishAssistantText.failure(OpenClawFailure.NetworkUnavailable), r.probe.probe.value.error)
        assertNull(r.probe.probe.value.reply)
        verify(exactly = 0) { r.voice.speak(any()) }
    }

    @Test fun `pairing and approval states come back as their own Polish lines`() = runBlocking {
        val approval = Rig().also { it.chat.script = { OpenClawTurnResult.NeedsApproval("r1") } }
        approval.probe.send("Cześć")
        assertEquals(PolishAssistantText.APPROVAL, approval.probe.probe.value.error)

        val revoked = Rig().also { it.chat.script = { OpenClawTurnResult.Revoked } }
        revoked.probe.send("Cześć")
        assertEquals(PolishAssistantText.REVOKED, revoked.probe.probe.value.error)
    }

    @Test fun `an uncertain send is never repeated on its own, only by an explicit resend`() = runBlocking {
        val r = Rig()
        r.chat.script = { OpenClawTurnResult.Uncertain }

        r.probe.send("Zrób coś")

        assertEquals(1, r.chat.asked.size)
        assertEquals(0, r.chat.resent.get())
        assertTrue(r.probe.probe.value.canResend)
        assertEquals(PolishAssistantText.UNCERTAIN, r.probe.probe.value.error)

        r.probe.resend()

        assertEquals("the explicit resend is the only repeat", 1, r.chat.resent.get())
        assertEquals("Odpowiedź po jawnym ponowieniu.", r.probe.probe.value.reply)
        assertFalse(r.probe.probe.value.canResend)
    }

    @Test fun `a too-large first send offers no resend`() = runBlocking {
        val r = Rig()
        r.chat.script = { OpenClawTurnResult.Failed(OpenClawFailure.RequestTooLarge) }

        r.probe.send("Bardzo długa wiadomość")

        assertFalse(r.probe.probe.value.canResend)
        assertEquals(PolishAssistantText.failure(OpenClawFailure.RequestTooLarge), r.probe.probe.value.error)
    }

    @Test fun `a too-large resend keeps the resend offered with text about the unresolved original`() = runBlocking {
        val r = Rig()
        r.chat.script = { OpenClawTurnResult.Uncertain }
        r.probe.send("Zrób coś")
        assertTrue(r.probe.probe.value.canResend)

        r.chat.resendScript = { OpenClawTurnResult.ResendTooLarge }
        r.probe.resend()

        val refused = r.probe.probe.value
        assertTrue("the original is still unresolved, so the button stays", refused.canResend)
        assertEquals(PolishAssistantText.RESEND_TOO_LARGE, refused.error)
        assertNull(refused.reply)
        assertEquals("the refused retry is not repeated on its own", 1, r.chat.resent.get())

        r.chat.resendScript = { OpenClawTurnResult.Answer("Odpowiedź po jawnym ponowieniu.") }
        r.probe.resend()

        assertEquals(2, r.chat.resent.get())
        assertEquals("Odpowiedź po jawnym ponowieniu.", r.probe.probe.value.reply)
        assertFalse(r.probe.probe.value.canResend)
    }

    @Test fun `cancel stops playback and the turn, and shows it`() = runBlocking {
        val r = Rig()
        val gate = CompletableDeferred<OpenClawTurnResult>()
        r.chat.script = { gate.await() }
        val sending = async { r.probe.send("Cześć") }
        withTimeout(2_000) { while (r.chat.asked.isEmpty()) delay(10) }
        assertTrue(r.probe.probe.value.running)

        r.probe.cancel()
        gate.complete(OpenClawTurnResult.Aborted)
        sending.await()

        verify { r.voice.stop() }
        assertEquals(1, r.chat.cancels.get())
        assertEquals(AssistantConnectionProbe.ABORTED, r.probe.probe.value.error)
        assertFalse(r.probe.probe.value.running)
    }

    @Test fun `the probe has no vehicle or microphone collaborator`() {
        val constructor = AssistantConnectionProbe::class.java.constructors.single()
        val types = constructor.parameterTypes.map { it.name }
        assertTrue(types.toString(), types.none { "ActionDispatcher" in it || "AudioCapture" in it || "VehicleApi" in it })
    }

    @Test fun `the probe turn is plain and never an action turn`() = runBlocking {
        val r = Rig()

        r.probe.send("Zamknij okna")

        assertEquals(listOf("Zamknij okna"), r.chat.asked)
        assertEquals(emptyList<String>(), r.chat.actionAsked)
    }

    @Test fun `a reply with a fenced action block is shown as text and proposes nothing`() = runBlocking {
        val r = Rig()
        val reply = "Zamykam okna.\n```bydmate-action\n{\"schema\":\"bydmate.action/1\"}\n```"
        r.chat.script = { OpenClawTurnResult.Answer(reply) }

        r.probe.send("Zamknij okna")

        assertEquals(reply, r.probe.probe.value.reply)
        assertNull(r.probe.probe.value.error)
        assertEquals(emptyList<String>(), r.chat.actionAsked)
    }

    @Test fun `an action answer reaching the probe is shown as text too`() = runBlocking {
        val r = Rig()
        r.chat.script = { OpenClawTurnResult.ActionAnswer("Tekst bez bloku.", recovered = false, turnKey = "t") }

        r.probe.send("Cześć")

        assertEquals("Tekst bez bloku.", r.probe.probe.value.reply)
    }
}
