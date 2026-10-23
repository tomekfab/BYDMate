package com.bydmate.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** PL-only destination for the neutral session/release cases from ProfileAsrEngineTest. */
@RunWith(RobolectricTestRunner::class)
class ProfileAsrEngineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clearPreferences() {
        context.getSharedPreferences("profile_asr_engine_test", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class FakeEngine : ReleasableAsr {
        val starts = AtomicInteger()
        val releases = AtomicInteger()
        val running = CompletableDeferred<Unit>()
        override fun isReady() = true
        override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = flow {
            starts.incrementAndGet()
            running.complete(Unit)
            try {
                emit(ContinuousAsrEvent.SpeechStart)
                emit(ContinuousAsrEvent.Utterance("pl-hello"))
                awaitCancellation()
            } finally {
                // The enclosing ProfileAsrEngine must wait for this finally before release.
            }
        }
        override suspend fun releaseNative() { releases.incrementAndGet() }
    }

    private fun store() = VoiceProfileStore(
        context.getSharedPreferences("profile_asr_engine_test", Context.MODE_PRIVATE),
    )

    @Test fun `PL session events pass through unchanged`() = runBlocking {
        val fake = FakeEngine()
        val profile = ProfileAsrEngine(store(), mapOf(VoiceLanguage.PL to fake))

        val events = profile.transcribe(flowOf(ShortArray(160))).take(2).toList()

        assertEquals(
            listOf(ContinuousAsrEvent.SpeechStart, ContinuousAsrEvent.Utterance("pl-hello")),
            events,
        )
        assertEquals(1, fake.starts.get())
    }

    @Test fun `asset release ends the PL session before closing native handles`() = runBlocking {
        val fake = FakeEngine()
        val profile = ProfileAsrEngine(store(), mapOf(VoiceLanguage.PL to fake))
        val session = launch(Dispatchers.Default) { profile.transcribe(flow { awaitCancellation() }).toList() }
        withTimeout(5_000) { fake.running.await() }

        profile.withAssetsReleased(VoiceLanguage.PL) {}

        withTimeout(5_000) { session.join() }
        assertFalse(session.isCancelled)
        assertEquals(1, fake.releases.get())
    }

    @Test fun `released PL profile cannot start a new session until the block returns`() = runBlocking {
        val fake = FakeEngine()
        val profile = ProfileAsrEngine(store(), mapOf(VoiceLanguage.PL to fake))
        val entered = CompletableDeferred<Unit>()
        val leave = CompletableDeferred<Unit>()
        val release = launch {
            profile.withAssetsReleased(VoiceLanguage.PL) { entered.complete(Unit); leave.await() }
        }
        entered.await()

        assertTrue(profile.transcribe(flowOf(ShortArray(160))).toList().isEmpty())
        assertEquals(0, fake.starts.get())
        leave.complete(Unit)
        release.join()
    }

    private fun events(): MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Test fun `activation does not verify or commit while the PL assets are released`() = runBlocking {
        val profile = ProfileAsrEngine(store(), mapOf(VoiceLanguage.PL to FakeEngine()))
        val log = events()
        val inBlock = CompletableDeferred<Unit>()
        val leave = CompletableDeferred<Unit>()
        val release = launch(Dispatchers.Default) {
            profile.withAssetsReleased(VoiceLanguage.PL) {
                log += "release-start"; inBlock.complete(Unit); leave.await(); log += "release-end"
            }
        }
        withTimeout(5_000) { inBlock.await() }
        val activation = async(Dispatchers.Default) {
            profile.activate(
                VoiceLanguage.PL,
                ProfileReadiness(asr = { log += "verify"; emptyList() }, vad = { true }, tts = { true }),
            )
        }
        delay(300)

        assertEquals(
            "activation verified/committed while the PL assets were deleted",
            listOf("release-start"), log.toList(),
        )
        assertFalse(activation.isCompleted)
        leave.complete(Unit)
        release.join()
        val result = withTimeout(5_000) { activation.await() }
        assertEquals(listOf("release-start", "release-end", "verify"), log.toList())
        assertEquals(ActivationResult.AlreadyActive(VoiceLanguage.PL), result)
    }

    @Test fun `asset release does not start while an activation is verifying`() = runBlocking {
        val profile = ProfileAsrEngine(store(), mapOf(VoiceLanguage.PL to FakeEngine()))
        val log = events()
        val verifying = CompletableDeferred<Unit>()
        val finishVerify = CompletableDeferred<Unit>()
        val activation = async(Dispatchers.Default) {
            profile.activate(
                VoiceLanguage.PL,
                ProfileReadiness(
                    asr = { log += "verify-start"; verifying.complete(Unit); finishVerify.await(); log += "verify-end"; emptyList() },
                    vad = { true }, tts = { true },
                ),
            )
        }
        withTimeout(5_000) { verifying.await() }
        val release = launch(Dispatchers.Default) {
            profile.withAssetsReleased(VoiceLanguage.PL) { log += "release" }
        }
        delay(300)

        assertEquals(
            "assets were released (deleted) while an activation was still verifying them",
            listOf("verify-start"), log.toList(),
        )
        assertFalse(release.isCompleted)
        finishVerify.complete(Unit)
        withTimeout(5_000) { activation.await() }
        release.join()
        assertEquals(listOf("verify-start", "verify-end", "release"), log.toList())
    }
}
