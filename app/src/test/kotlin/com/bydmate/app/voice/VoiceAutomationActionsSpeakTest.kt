package com.bydmate.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.util.AppStrings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric: the refusal reasons come from the current Polish app strings.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VoiceAutomationActionsSpeakTest {

    private val strings = AppStrings(ApplicationProvider.getApplicationContext())

    private fun lang(tag: String) = LocalePreferences(ApplicationProvider.getApplicationContext()).setLanguage(tag)

    init {
        lang("pl")
    }

    @After fun restoreLanguage() = lang("pl")

    private fun makeActions(
        ttsEnabled: Boolean = true,
        voiceEnabled: Boolean = true,
        listening: Boolean = false,
        speakOk: Boolean = true,
        ttsEngine: TtsEngine = mockk(relaxed = true) {
            every { speaking } returns MutableStateFlow(false)
            every { speak(any()) } returns speakOk
        },
        audioCapture: AudioCapture = mockk(relaxed = true),
        orchestrator: AgentOrchestrator = mockk(relaxed = true),
    ): Pair<VoiceAutomationActions, TtsEngine> {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns voiceEnabled
        every { gate.ttsEnabled() } returns ttsEnabled
        val controller = mockk<VoiceController>()
        every { controller.listening } returns MutableStateFlow(listening)
        every { controller.sessionActive() } returns listening
        val actions = VoiceAutomationActions(
            ttsEngine = ttsEngine,
            audioCapture = audioCapture,
            gate = gate,
            agentOrchestrator = dagger.Lazy { orchestrator },
            voiceController = dagger.Lazy { controller },
            context = mockk<Context>(relaxed = true),
            strings = strings,
        )
        // Never touch the real static overlay in JVM tests.
        actions.isOrbShowing = { false }
        actions.canShowOrb = { false }
        return actions to ttsEngine
    }

    @Test fun `speaks the raw text and reports success`() = runTest {
        val (actions, tts) = makeActions()
        val r = actions.speak("Zadzwoń do żony")
        assertTrue(r.success)
        verify(exactly = 1) { tts.speak("Zadzwoń do żony") }  // raw, no persona wrapper
    }

    @Test fun `ducks music and restores it after speech`() = runTest {
        val capture = mockk<AudioCapture>(relaxed = true)
        io.mockk.every { capture.duckMusic() } returns 20
        val (actions, _) = makeActions(audioCapture = capture)
        actions.speak("test")
        io.mockk.verifyOrder {
            capture.duckMusic()
            capture.restoreMusic(20)
        }
    }

    @Test fun `voice master off - refused with reason`() = runTest {
        val (actions, tts) = makeActions(voiceEnabled = false)
        val r = actions.speak("test")
        assertFalse(r.success)
        assertEquals("asystent głosowy jest wyłączony w ustawieniach", r.reason)
        verify(exactly = 0) { tts.speak(any()) }
    }

    @Test fun `tts toggle off - refused with reason`() = runTest {
        val (actions, tts) = makeActions(ttsEnabled = false)
        val r = actions.speak("test")
        assertFalse(r.success)
        assertEquals("czytanie odpowiedzi na głos jest wyłączone w ustawieniach", r.reason)
        verify(exactly = 0) { tts.speak(any()) }
    }

    @Test fun `live voice session - dropped, not queued`() = runTest {
        val (actions, tts) = makeActions(listening = true)
        val r = actions.speak("test")
        assertFalse(r.success)
        assertEquals("trwa sesja głosowa, akcja pominięta", r.reason)
        verify(exactly = 0) { tts.speak(any()) }
    }

    // Fix 3: legacy one-shot startSession() sets only busy, not _listening. sessionActive()
    // must cover that path; the gate must drop the speak with the same reason.
    @Test fun `legacy session active (sessionActive=true, listening=false) - speak dropped`() = runTest {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.ttsEnabled() } returns true
        val controller = mockk<VoiceController>()
        every { controller.listening } returns MutableStateFlow(false) // continuous session NOT active
        every { controller.sessionActive() } returns true              // legacy busy IS active
        val tts = mockk<TtsEngine>(relaxed = true) {
            every { speaking } returns MutableStateFlow(false)
        }
        val actions = VoiceAutomationActions(
            ttsEngine = tts,
            audioCapture = mockk(relaxed = true),
            gate = gate,
            agentOrchestrator = dagger.Lazy { mockk(relaxed = true) },
            voiceController = dagger.Lazy { controller },
            context = mockk(relaxed = true),
            strings = strings,
        )
        actions.isOrbShowing = { false }
        actions.canShowOrb = { false }
        val r = actions.speak("test")
        assertFalse(r.success)
        assertEquals("trwa sesja głosowa, akcja pominięta", r.reason)
        verify(exactly = 0) { tts.speak(any()) }
    }

    @Test fun `blank text - refused`() = runTest {
        val (actions, _) = makeActions()
        val r = actions.speak("   ")
        assertFalse(r.success)
        assertEquals("nie podano tekstu", r.reason)
    }

    @Test fun `tts refusing to start reports failure but still restores music`() = runTest {
        val capture = mockk<AudioCapture>(relaxed = true)
        io.mockk.every { capture.duckMusic() } returns 12
        val (actions, _) = makeActions(speakOk = false, audioCapture = capture)
        val r = actions.speak("test")
        assertFalse(r.success)
        assertEquals("czytanie się nie uruchomiło", r.reason)
        verify(exactly = 1) { capture.restoreMusic(12) }
    }

    @Test fun `speak timeout stops tts and returns failure`() = runTest {
        // speaking stuck at true: start-wait (first { it }) resolves immediately;
        // drain (first { !it }) suspends forever. runTest's virtual clock advances
        // SPEAK_TIMEOUT_MS so withTimeoutOrNull fires, stop() must be called and
        // the result must be failure with the expected reason.
        val tts = mockk<TtsEngine>(relaxed = true) {
            every { speaking } returns MutableStateFlow(true)
            every { speak(any()) } returns true
        }
        val (actions, _) = makeActions(ttsEngine = tts)
        val r = actions.speak("test")
        assertFalse(r.success)
        assertEquals("czytanie przerwane po przekroczeniu czasu", r.reason)
        verify(exactly = 1) { tts.stop() }
    }

    @Test fun `music duck is restored on cancellation mid-drain`() = runTest {
        val capture = mockk<AudioCapture>(relaxed = true)
        io.mockk.every { capture.duckMusic() } returns 15
        // speaking = true: start-wait (first { it }) completes immediately;
        // drain (first { !it }) suspends forever -- coroutine hangs there until cancelled.
        val tts = mockk<TtsEngine>(relaxed = true) {
            every { speaking } returns MutableStateFlow(true)
            every { speak(any()) } returns true
        }
        val (actions, _) = makeActions(ttsEngine = tts, audioCapture = capture)
        val job = launch { actions.speak("test") }
        testScheduler.runCurrent()    // start coroutine, run until suspended in drain
        job.cancelAndJoin()           // cancel while suspended; finally must fire
        io.mockk.verify(exactly = 1) { capture.restoreMusic(15) }
    }

    @Test fun `a second speak while the first still plays - refused as busy`() = runTest {
        val tts = mockk<TtsEngine>(relaxed = true) {
            every { speaking } returns MutableStateFlow(true)
            every { speak(any()) } returns true
        }
        val (actions, _) = makeActions(ttsEngine = tts)
        val first = launch { actions.speak("pierwsza") }
        testScheduler.runCurrent()    // the first speak now hangs in the drain

        val r = actions.speak("druga")
        first.cancelAndJoin()

        assertFalse(r.success)
        assertEquals("inna akcja głosowa jest jeszcze wykonywana", r.reason)
    }

    @Test fun `refusals follow the app language`() = runTest {
        lang("en")
        val stuck = mockk<TtsEngine>(relaxed = true) {
            every { speaking } returns MutableStateFlow(true)
            every { speak(any()) } returns true
        }
        val (playing, _) = makeActions(ttsEngine = stuck)
        val first = launch { playing.speak("one") }
        testScheduler.runCurrent()
        val reasons = listOf(
            makeActions(voiceEnabled = false).first.speak("x").reason!!,
            makeActions(ttsEnabled = false).first.speak("x").reason!!,
            makeActions(listening = true).first.speak("x").reason!!,
            makeActions().first.speak(" ").reason!!,
            makeActions(speakOk = false).first.speak("x").reason!!,
            playing.speak("two").reason!!,
        )
        first.cancelAndJoin()
        val timedOut = makeActions(ttsEngine = stuck).first.speak("x").reason!!

        assertEquals("the voice assistant is off in settings", reasons[0])
        assertEquals("another voice action is still running", reasons[5])
        (reasons + timedOut).forEach { assertFalse(it, it.hasCyrillic()) }
    }

    private fun String.hasCyrillic() = any { it in 'Ѐ'..'ӿ' }
}
