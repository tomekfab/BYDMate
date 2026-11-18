package com.bydmate.app.voice

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercises capture collection and playback drain independently of the routing result. */
class VoiceRequestLifecycleTest {
    private val fixture = PolishVoiceControllerTest()
    private val question = "jaką książkę polecasz"
    private fun await(condition: () -> Boolean) = fixture.awaitTrue(condition = condition)
    private fun rig(tts: Boolean = false) = fixture.rig(null, VoiceUserPhrases(), ttsEnabled = tts)

    @Test fun a_rejected_later_sentence_cannot_count_as_a_complete_reply() = runBlocking {
        val r = rig(tts = true)
        val queue = mockk<TtsEngine.SpeechQueue>()
        val stopped = AtomicBoolean(false)
        every { r.tts.startQueue() } returns queue
        every { queue.enqueue("pierwsze") } returns true
        every { queue.enqueue("drugie") } returns false
        every { queue.finish() } returns Unit
        coEvery { queue.awaitPlayed() } returns Unit
        every { r.tts.stop() } answers { stopped.set(true) }
        assertEquals(false, r.controller.speakQueued("pierwsze drugie", listOf("pierwsze", "drugie")))
        assertTrue(stopped.get())
    }

    @Test fun a_failed_queue_finish_preserves_reading_time_for_the_unspoken_reply() {
        val r = rig(tts = true)
        val queue = mockk<TtsEngine.SpeechQueue>()
        val stopped = AtomicBoolean(false)
        every { r.tts.startQueue() } returns queue
        every { queue.enqueue(any()) } returns true
        every { queue.finish() } throws IllegalStateException("queue failed")
        every { r.tts.stop() } answers { stopped.set(true) }
        every { r.gate.closeAfterCommand() } returns true
        r.controller.dialogClearDelayMs = 10_000L
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(question))
        await { stopped.get() && r.controller.listening.value }
        r.controller.onPttPressed()
    }

    @Test fun queue_playback_failure_preserves_the_settled_request_outcome() {
        val r = rig(tts = true)
        val queue = mockk<TtsEngine.SpeechQueue>()
        val stopped = AtomicBoolean(false)
        every { r.tts.startQueue() } returns queue
        every { queue.enqueue(any()) } returns true
        every { queue.finish() } returns Unit
        coEvery { queue.awaitPlayed() } throws IllegalStateException("playback failed")
        every { r.tts.stop() } answers { stopped.set(true) }
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(question))
        await { r.journal.entries.value.any { it.answer == "odpowiedź" } }
        await { r.controller.listening.value }
        assertTrue(stopped.get())
        assertEquals(1, r.journal.entries.value.size)
        assertFalse(r.journal.entries.value.any { it.refusal == VoiceRefusal.INTERNAL_ERROR })
        r.controller.onPttPressed()
    }

    @Test fun overlay_reports_waiting_after_capture_and_preserves_a_new_listening_window() {
        val r = rig()
        val response = CompletableDeferred<AssistantReply>()
        val sent = AtomicInteger()
        val label = AtomicReference("")
        r.controller.showListeningOverlay = { label.set(it) }
        r.controller.updateListeningOverlay = { label.set(it) }
        coEvery { r.assistant.ask(any(), any()) } coAnswers { sent.incrementAndGet(); response.await() }
        r.controller.onPttPressed()
        var enabled = true
        try {
            await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
            assertEquals("Słucham.", label.get())
            r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(question))
            await { sent.get() == 1 && !r.asr.captureOpen.get() }
            assertEquals("Myślę.", label.get())
            r.controller.onPttPressed()
            r.controller.onPttPressed()
            await { r.controller.listening.value }
            response.complete(AssistantReply.Answer("odpowiedź"))
            Thread.sleep(100)
            assertEquals("Słucham.", label.get())
            r.controller.onPttPressed()
            enabled = false
            await { !r.asr.captureOpen.get() }
            await { r.journal.entries.value.any { it.answer == "odpowiedź" } }
            assertEquals("Myślę.", label.get())
        } finally {
            if (enabled) r.controller.onPttPressed()
            response.complete(AssistantReply.Answer("odpowiedź"))
        }
    }

    @Test fun speech_that_never_starts_is_stopped_before_capture_resumes() {
        val r = rig(tts = true)
        val response = CompletableDeferred<AssistantReply>()
        val sent = AtomicInteger()
        val stopped = AtomicBoolean(false)
        every { r.tts.startQueue() } returns null
        every { r.tts.speak(any()) } returns true
        every { r.tts.stop() } answers { stopped.set(true) }
        coEvery { r.assistant.ask(any(), any()) } coAnswers { sent.incrementAndGet(); response.await() }
        r.controller.onPttPressed()
        try {
            await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
            r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(question))
            await { sent.get() == 1 && !r.asr.captureOpen.get() }
            response.complete(AssistantReply.Answer("odpowiedź"))
            fixture.awaitTrue(timeoutMs = 7_000) { r.controller.listening.value }
            assertTrue("Timed-out speech must be cancelled before reopening the mic", stopped.get())
        } finally {
            r.controller.onPttPressed()
            response.complete(AssistantReply.Answer("odpowiedź"))
        }
    }

    @Test fun listening_and_ready_wait_for_a_captured_frame() {
        val r = rig()
        val frame = CompletableDeferred<Unit>()
        r.asr.beforeFrame = { frame.await() }
        r.controller.onPttPressed()
        await { r.asr.captureOpen.get() }
        assertFalse(r.controller.listening.value)
        assertEquals(VoiceStage.PREPARING, r.controller.status.value.stage)
        frame.complete(Unit)
        await { r.controller.listening.value }
        assertEquals(VoiceStage.LISTENING, r.controller.status.value.stage)
        r.controller.onPttPressed()
        await { !r.asr.captureOpen.get() }
    }

    @Test fun an_old_frame_callback_cannot_mark_a_new_capture_ready() {
        val r = rig()
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.captureMarks.size == 1 }
        val oldMark = r.asr.captureMarks.first()
        r.controller.onPttPressed()
        await { !r.asr.captureOpen.get() }
        val frame = CompletableDeferred<Unit>()
        r.asr.beforeFrame = { frame.await() }
        r.controller.onPttPressed()
        await { r.asr.captureOpen.get() && r.asr.captureMarks.size == 2 }
        oldMark(shortArrayOf(0))
        assertFalse(r.controller.listening.value)
        assertEquals(VoiceStage.PREPARING, r.controller.status.value.stage)
        frame.complete(Unit)
        await { r.controller.listening.value }
        r.controller.onPttPressed()
        await { !r.asr.captureOpen.get() }
    }

    @Test fun off_during_warmup_cannot_open_a_stale_capture() {
        val r = rig()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        r.asr.requiresWarm = true
        r.asr.warmAction = { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
        r.controller.onPttPressed()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        r.controller.onPttPressed()
        release.countDown()
        await { !r.controller.sessionActive() }
        assertFalse(r.asr.captureOpen.get())
        assertFalse(r.controller.listening.value)
    }

    @Test fun automation_playback_reservation_defers_capture_until_release() {
        val r = rig()
        assertTrue(r.controller.tryBeginAutomationSpeech())
        r.controller.onPttPressed()
        Thread.sleep(100)
        assertFalse(r.asr.captureOpen.get())
        r.controller.endAutomationSpeech()
        await { r.asr.captureOpen.get() && r.controller.listening.value }
        r.controller.onPttPressed()
        await { !r.asr.captureOpen.get() }
    }

    @Test fun queued_replies_drain_in_order_even_after_the_mic_is_off() {
        val r = rig(tts = true)
        val firstReply = CompletableDeferred<AssistantReply>()
        val drainA = CompletableDeferred<Unit>()
        val drainB = CompletableDeferred<Unit>()
        val queueA = mockk<TtsEngine.SpeechQueue>()
        val queueB = mockk<TtsEngine.SpeechQueue>()
        val queued = Collections.synchronizedList(mutableListOf<String>())
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val physical = MutableStateFlow(false)
        every { r.tts.speaking } returns physical
        every { r.tts.startQueue() } returnsMany listOf(queueA, queueB)
        every { queueA.enqueue(any()) } answers { queued += firstArg<String>(); true }
        every { queueB.enqueue(any()) } answers { queued += firstArg<String>(); true }
        every { queueA.finish() } returns Unit
        every { queueB.finish() } returns Unit
        coEvery { queueA.awaitPlayed() } coAnswers { drainA.await() }
        coEvery { queueB.awaitPlayed() } coAnswers { drainB.await() }
        coEvery { r.assistant.ask(any(), any()) } coAnswers {
            sent += firstArg<String>()
            if (sent.size == 1) firstReply.await() else AssistantReply.Answer("druga")
        }
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(question))
        await { sent.size == 1 && !r.asr.captureOpen.get() }
        r.controller.onPttPressed()
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
        firstReply.complete(AssistantReply.Answer("pierwsza"))
        Thread.sleep(100)
        assertEquals(emptyList<String>(), queued.toList())
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance("jak się masz"))
        await { queued.size == 1 && !r.asr.captureOpen.get() }
        r.controller.onPttPressed()
        assertTrue(r.controller.sessionActive())
        assertEquals(1, sent.size)
        physical.value = true
        Thread.sleep(50)
        physical.value = false
        assertEquals(1, sent.size)
        drainA.complete(Unit)
        await { queued.size == 2 }
        assertEquals(listOf("pierwsza", "druga"), queued.toList())
        assertEquals(2, sent.size)
        assertTrue(r.controller.sessionActive())
        drainB.complete(Unit)
        await { !r.controller.sessionActive() }
        assertFalse(r.asr.captureOpen.get())
        coVerify(exactly = 1) { queueA.awaitPlayed() }
        coVerify(exactly = 1) { queueB.awaitPlayed() }
        coVerify(exactly = 2) { r.assistant.ask(any(), any()) }
    }

    @Test fun a_local_acknowledgement_waits_for_the_remote_reply_to_drain() {
        val r = rig(tts = true)
        val response = CompletableDeferred<AssistantReply>()
        val drainA = CompletableDeferred<Unit>()
        val drainB = CompletableDeferred<Unit>()
        val queueA = mockk<TtsEngine.SpeechQueue>()
        val queueB = mockk<TtsEngine.SpeechQueue>()
        val queued = Collections.synchronizedList(mutableListOf<String>())
        val sent = AtomicInteger()
        every { r.tts.startQueue() } returnsMany listOf(queueA, queueB)
        every { queueA.enqueue(any()) } answers { queued += firstArg<String>(); true }
        every { queueB.enqueue(any()) } answers { queued += firstArg<String>(); true }
        every { queueA.finish() } returns Unit
        every { queueB.finish() } returns Unit
        coEvery { queueA.awaitPlayed() } coAnswers { drainA.await() }
        coEvery { queueB.awaitPlayed() } coAnswers { drainB.await() }
        coEvery { r.assistant.ask(any(), any()) } coAnswers { sent.incrementAndGet(); response.await() }
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(question))
        await { sent.get() == 1 && !r.asr.captureOpen.get() }
        r.controller.onPttPressed()
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance("Nawiguj do domu"))
        await { !r.asr.captureOpen.get() }
        r.controller.onPttPressed()
        response.complete(AssistantReply.Answer("pierwsza"))
        await { queued.size == 1 }
        assertTrue(r.dispatched.isEmpty())
        drainA.complete(Unit)
        await { queued.size == 2 }
        assertEquals("pierwsza", queued.first())
        assertEquals(1, r.dispatched.size)
        assertEquals(1, sent.get())
        drainB.complete(Unit)
        await { !r.controller.sessionActive() }
    }

    @Test fun an_old_auto_close_cannot_close_a_new_capture() {
        val r = rig()
        every { r.gate.closeAfterCommand() } returnsMany listOf(true, false)
        r.controller.dialogClearDelayMs = 1_000
        val response = CompletableDeferred<AssistantReply>()
        val sent = AtomicInteger()
        coEvery { r.assistant.ask(any(), any()) } coAnswers { sent.incrementAndGet(); response.await() }
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(question))
        await { sent.get() == 1 && !r.asr.captureOpen.get() }
        response.complete(AssistantReply.Answer("tak"))
        await { r.asr.captureOpen.get() && r.controller.listening.value }
        r.controller.onPttPressed()
        await { !r.asr.captureOpen.get() }
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
        Thread.sleep(1_200)
        assertTrue(r.asr.captureOpen.get())
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance("jak się masz"))
        await { sent.get() == 2 }
        coVerify(exactly = 2) { r.assistant.ask(any(), any()) }
        r.controller.onPttPressed()
    }

    @Test fun silence_closes_an_explicit_window_while_a_reply_waits() {
        val r = rig()
        val response = CompletableDeferred<AssistantReply>()
        coEvery { r.assistant.ask(any(), any()) } coAnswers { response.await() }
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(question))
        await { r.controller.routingJobForTest()?.isActive == true && !r.asr.captureOpen.get() }
        r.controller.onPttPressed()
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
        response.complete(AssistantReply.Answer("odpowiedź"))
        r.asr.events.tryEmit(ContinuousAsrEvent.SilenceTick(30_001))
        await { !r.asr.captureOpen.get() }
        await { r.journal.entries.value.any { it.answer == "odpowiedź" } }
        await { !r.controller.sessionActive() }
    }

    @Test fun waiting_status_returns_after_the_new_capture_is_closed() {
        val r = rig()
        val response = CompletableDeferred<AssistantReply>()
        val sent = AtomicInteger()
        coEvery { r.assistant.ask(any(), any()) } coAnswers { sent.incrementAndGet(); response.await() }
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(question))
        await { sent.get() == 1 && !r.asr.captureOpen.get() }
        r.controller.onPttPressed()
        r.controller.onPttPressed()
        await { r.controller.listening.value }
        r.controller.onPttPressed()
        await { !r.asr.captureOpen.get() && r.controller.status.value.stage == VoiceStage.WAITING_ASSISTANT }
        assertTrue(r.controller.sessionActive())
        response.complete(AssistantReply.Answer("odpowiedź"))
        await { r.journal.entries.value.any { it.answer == "odpowiedź" } }
    }

    @Test fun listening_resumes_after_a_reply_only_when_still_enabled() {
        val r = rig()
        val response = CompletableDeferred<AssistantReply>()
        val sent = AtomicInteger()
        coEvery { r.assistant.ask(any(), any()) } coAnswers { sent.incrementAndGet(); response.await() }
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(question))
        await { sent.get() == 1 && !r.asr.captureOpen.get() }
        response.complete(AssistantReply.Answer("odpowiedź"))
        await { r.controller.listening.value && r.asr.captureOpen.get() }
        r.controller.onPttPressed()
        await { !r.asr.captureOpen.get() }
    }

    @Test fun missing_model_is_recorded_without_opening_capture() {
        val r = rig()
        r.asr.ready = false
        r.controller.onPttPressed()
        await { r.journal.entries.value.any { it.refusal == VoiceRefusal.MODEL_MISSING } }
        assertFalse(r.asr.captureOpen.get())
        assertFalse(r.controller.listening.value)
    }

    @Test fun speech_failure_releases_suppression_with_the_mic_off() {
        val r = rig(tts = true)
        val releases = AtomicInteger()
        val response = CompletableDeferred<AssistantReply>()
        val sent = AtomicInteger()
        every { r.audioCapture.acquireSuppression(any()) } answers { MediaSuppressionLease { releases.incrementAndGet() } }
        every { r.tts.startQueue() } returns null
        every { r.tts.speak(any()) } throws IllegalStateException("playback failed")
        coEvery { r.assistant.ask(any(), any()) } coAnswers { sent.incrementAndGet(); response.await() }
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(question))
        await { sent.get() == 1 && !r.asr.captureOpen.get() }
        r.controller.onPttPressed()
        response.complete(AssistantReply.Answer("odpowiedź"))
        await { releases.get() == 2 && !r.controller.sessionActive() }
        assertFalse(r.asr.captureOpen.get())
    }

    @Test fun request_failure_releases_capture_and_keeps_the_queue_live() {
        val r = rig()
        val releases = AtomicInteger()
        every { r.audioCapture.acquireSuppression(any()) } answers { MediaSuppressionLease { releases.incrementAndGet() } }
        coEvery { r.assistant.ask(any(), any()) } throws IllegalStateException("request failed")
        r.controller.onPttPressed()
        await { r.controller.listening.value && r.asr.events.subscriptionCount.value > 0 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(question))
        await { releases.get() == 1 && r.journal.entries.value.any { it.refusal == VoiceRefusal.INTERNAL_ERROR } }
        r.controller.onPttPressed()
        await { !r.controller.sessionActive() }
        assertFalse(r.asr.captureOpen.get())
    }

    @Test fun capture_failure_releases_the_early_media_lease() {
        val r = rig()
        val releases = AtomicInteger()
        every { r.audioCapture.acquireSuppression(any()) } answers { MediaSuppressionLease { releases.incrementAndGet() } }
        every { r.audioCapture.captureSession(any(), any<(ShortArray) -> Any?>()) } returns kotlinx.coroutines.flow.flow {
            throw IllegalStateException("capture failed")
        }
        r.controller.onPttPressed()
        await { releases.get() == 1 }
        await { !r.controller.sessionActive() }
        assertFalse(r.controller.listening.value)
    }
}
