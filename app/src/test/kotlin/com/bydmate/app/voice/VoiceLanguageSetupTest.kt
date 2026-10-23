package com.bydmate.app.voice

import com.bydmate.app.voice.openclaw.OpenClawAssistant
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VoiceLanguageSetupTest {
    private val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }

    @Before fun installMain() {
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
        mockkObject(NluParser)
    }

    @After fun removeMain() {
        unmockkObject(NluParser)
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
    }

    private fun setup(): VoiceLanguageSetup {
        val store = mockk<VoiceProfileStore> {
            every { activeLanguage() } returns VoiceLanguage.PL
            every { pendingLanguage } returns null
        }
        val polish = mockk<PolishModelManager>(relaxed = true) {
            every { guardScope } returns "pl-test"
            every { readiness(any()) } returns ProfileReadiness(asr = { emptyList() }, vad = { true }, tts = { true })
        }
        val switcher = ProfileSwitcher { target, _ -> ActivationResult.AlreadyActive(target) }
        return VoiceLanguageSetup(
            store,
            SetupAsrAssets(polish, mockk(relaxed = true), mockk(relaxed = true)),
            mockk<SetupVoiceAssets>(relaxed = true),
            switcher,
            mockk<OpenClawAssistant>(relaxed = true),
        )
    }

    @Test fun `activation runs the parser warm-up off the main dispatcher`() = runBlocking {
        val release = CountDownLatch(1)
        val warmUpThread = AtomicReference<String>()
        val entered = CompletableDeferred<Unit>()
        every { NluParser.warmUp(VoiceLanguage.PL) } answers {
            warmUpThread.set(Thread.currentThread().name)
            entered.complete(Unit)
            release.await(5, TimeUnit.SECONDS)
            true
        }

        val activation = async(Dispatchers.Main) { setup().activatePolish() }
        try {
            withTimeout(5_000) { entered.await() }
            // The warm-up is blocked now. A free main dispatcher still runs this probe.
            val probe = CompletableDeferred<Unit>()
            launch(Dispatchers.Main) { probe.complete(Unit) }
            val mainWasFree = withTimeoutOrNull(1_000) { probe.await() } != null

            assertTrue(
                "the parser warm-up blocked the main dispatcher (ran on ${warmUpThread.get()})",
                mainWasFree,
            )
            assertNotNull(warmUpThread.get())
            assertTrue("warm-up ran on ${warmUpThread.get()}", warmUpThread.get() != "fake-main")
        } finally {
            release.countDown()
        }
        assertEquals(ActivationResult.AlreadyActive(VoiceLanguage.PL), withTimeout(5_000) { activation.await() })
    }
}
