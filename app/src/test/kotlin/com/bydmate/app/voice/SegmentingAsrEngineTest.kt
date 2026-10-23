package com.bydmate.app.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** Neutral lifecycle and segmentation cases ported from GigaAmSegmenterTest. */
class SegmentingAsrEngineTest {
    private class QueuedExecutor : Executor {
        private val tasks = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun runAll() = tasks.toList().also { tasks.clear() }.forEach(Runnable::run)
    }

    private class Assets(private val ready: Boolean = true) : AsrAssets {
        override fun isReady() = ready
        override fun vadPath() = "unused"
    }

    private class Vad(private val frames: List<Frame> = listOf(Frame(false))) : VadHandle {
        data class Frame(val speech: Boolean, val segment: FloatArray? = null)
        private var index = -1
        private var pending: FloatArray? = null
        var closed = false
        private fun current() = frames.getOrElse(index) { frames.last() }
        override fun acceptWaveform(samples: FloatArray) { index++; pending = current().segment }
        override fun isSpeechDetected() = current().speech
        override fun empty() = pending == null
        override fun front() = checkNotNull(pending)
        override fun pop() { pending = null }
        override fun close() { closed = true }
    }

    private class Recognizer(private val text: String = "") : RecognizerHandle {
        var closed = false
        var segment: FloatArray? = null
        override fun decode(samples: FloatArray): String { segment = samples; return text }
        override fun close() { closed = true }
    }

    private fun engine(
        recognizer: () -> RecognizerHandle,
        vad: () -> VadHandle,
        ready: Boolean = true,
        warmVad: Boolean = false,
        executor: Executor = Executor { it.run() },
    ) = SegmentingAsrEngine(
        assets = Assets(ready),
        recognizerFactory = recognizer,
        vadFactory = vad,
        protection = RecognizerLoadProtection(null, null),
        warmVad = warmVad,
        prewarmExecutor = executor,
    )

    @Test fun `emits speech transition silence ticks and decoded utterance`() = runTest {
        val segment = floatArrayOf(.1f, .2f)
        val recognizer = Recognizer("test")
        val asr = engine({ recognizer }, {
            Vad(listOf(Vad.Frame(true), Vad.Frame(false, segment)))
        })

        val events = asr.transcribe(List(2) { ShortArray(320) }.asFlow()).toList()

        assertEquals(1, events.filterIsInstance<ContinuousAsrEvent.SpeechStart>().size)
        assertEquals(20L, events.filterIsInstance<ContinuousAsrEvent.SilenceTick>().single().silentMs)
        assertEquals("test", events.filterIsInstance<ContinuousAsrEvent.Utterance>().single().text)
        assertSame(segment, recognizer.segment)
    }

    @Test fun `recognizer is reused but each collection closes its own vad`() = runTest {
        var recognizers = 0
        val vads = mutableListOf<Vad>()
        val asr = engine({ recognizers++; Recognizer() }, {
            Vad().also { vads += it }
        })

        asr.transcribe(flowOf(ShortArray(160))).toList()
        asr.transcribe(flowOf(ShortArray(160))).toList()

        assertEquals(1, recognizers)
        assertEquals(2, vads.size)
        assertTrue(vads.all { it.closed })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `cancellation closes vad and retains cached recognizer`() = runTest {
        val recognizer = Recognizer()
        val vad = Vad()
        val asr = engine({ recognizer }, { vad })
        val pcm = flow { while (true) { emit(ShortArray(320)); delay(10) } }

        val collection = launch { asr.transcribe(pcm).toList() }
        advanceTimeBy(50)
        collection.cancelAndJoin()

        assertTrue(vad.closed)
        assertFalse(recognizer.closed)
    }

    @Test fun `not ready assets block every native load`() = runTest {
        var recognizers = 0
        var vads = 0
        val asr = engine({ recognizers++; Recognizer() }, { vads++; Vad() }, ready = false)

        asr.warmUp()
        asr.transcribe(flowOf(ShortArray(160))).toList()

        assertEquals(0, recognizers)
        assertEquals(0, vads)
    }

    @Test fun `release closes cached recognizer and unused warm vad`() = runTest {
        val recognizer = Recognizer()
        val vads = mutableListOf<Vad>()
        val asr = engine({ recognizer }, {
            Vad().also { vads += it }
        }, warmVad = true)
        asr.warmUp()

        asr.releaseNative()

        assertTrue(recognizer.closed)
        assertTrue(vads.single().closed)
    }

    @Test fun `release drops queued vad prewarm but a later warm up still rebuilds it`() = runTest {
        val executor = QueuedExecutor()
        var recognizers = 0
        val vads = mutableListOf<Vad>()
        val asr = engine(
            recognizer = { recognizers++; Recognizer() },
            vad = { Vad().also { vads += it } },
            warmVad = true,
            executor = executor,
        )
        asr.warmUp()
        asr.transcribe(flowOf(ShortArray(160))).toList() // consumes the spare and queues its replacement

        asr.releaseNative()
        executor.runAll()

        assertEquals("a released engine must reject queued work from its old lifecycle", 1, vads.size)
        asr.warmUp()
        assertEquals("a deliberate warm-up after release must still work", 2, recognizers)
        assertEquals(2, vads.size)
    }
}
