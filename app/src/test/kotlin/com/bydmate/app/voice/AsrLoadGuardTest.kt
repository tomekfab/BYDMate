package com.bydmate.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AsrLoadGuardTest {

    private lateinit var guard: AsrLoadGuard

    @Before fun setUp() {
        guard = AsrLoadGuard(ApplicationProvider.getApplicationContext<Context>())
        guard.reset()
    }

    @Test fun `fresh guard is not tripped`() {
        assertFalse(guard.isTripped())
    }

    @Test fun `one interrupted load does not trip - could be an unrelated process kill`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        assertFalse(guard.isTripped())
    }

    @Test fun `two interrupted recognizer loads trip the guard`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        assertTrue(guard.isTripped())
    }

    @Test fun `two interrupted vad loads trip the guard`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        assertTrue(guard.isTripped())
    }

    @Test fun `one interruption per artifact does not trip - counters are independent`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        assertFalse(guard.isTripped())
    }

    @Test fun `successful load clears its own counter`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadSuccess(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        assertFalse(guard.isTripped())
    }

    @Test fun `recognizer success does not wipe vad crash evidence`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadSuccess(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        assertTrue(guard.isTripped())
    }

    @Test fun `reset untips the guard`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.reset()
        assertFalse(guard.isTripped())
        // And the counter really is zero, not one-below-threshold.
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        assertFalse(guard.isTripped())
    }

    @Test fun `state survives a new instance over the same prefs - the whole point`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        val reborn = AsrLoadGuard(ApplicationProvider.getApplicationContext<Context>())
        assertTrue(reborn.isTripped())
    }

    // --- Task 2: TTS guard extension ---

    @Test fun `two interrupted TTS loads trip the guard`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_TTS)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_TTS)
        assertTrue(guard.isTripped())
    }

    @Test fun `TTS guard on separate prefs file does not affect ASR instance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ttsPrefs = context.getSharedPreferences("tts_load_guard", Context.MODE_PRIVATE)
        ttsPrefs.edit().clear().commit()
        val ttsGuard = AsrLoadGuard(ttsPrefs)
        // Trip the TTS guard
        ttsGuard.noteLoadBegin(AsrLoadGuard.ARTIFACT_TTS)
        ttsGuard.noteLoadBegin(AsrLoadGuard.ARTIFACT_TTS)
        assertTrue(ttsGuard.isTripped())
        // ASR guard (uses "asr_load_guard") is unaffected
        assertFalse(guard.isTripped())
    }

    // --- Step 7: the TTS guard is scoped by language and model ---

    private val gosiaScope = "pl:gosia-model"

    @Test fun `Gosia crash evidence never trips an unscoped legacy TTS counter`() {
        guard.noteTtsLoadBegin(gosiaScope)
        guard.noteTtsLoadBegin(gosiaScope)
        assertTrue(guard.isTtsTripped(gosiaScope))
        assertFalse("The unscoped legacy counter is untouched", guard.isTtsTripped(null))
        assertFalse("TrackingService's unscoped isTripped() never sees it", guard.isTripped())
    }

    @Test fun `a tripped unscoped legacy TTS counter does not block Gosia`() {
        guard.noteTtsLoadBegin(null)
        guard.noteTtsLoadBegin(null)
        assertTrue(guard.isTtsTripped(null))
        assertTrue(guard.isTripped())
        assertFalse(guard.isTtsTripped(gosiaScope))
    }

    @Test fun `a successful Gosia load clears only Gosia and an unscoped reset keeps Gosia evidence`() {
        guard.noteTtsLoadBegin(null)
        guard.noteTtsLoadBegin(gosiaScope)
        guard.noteTtsLoadSuccess(gosiaScope)
        guard.noteTtsLoadBegin(gosiaScope)
        assertFalse(guard.isTtsTripped(gosiaScope))
        guard.noteTtsLoadBegin(gosiaScope)
        assertTrue(guard.isTtsTripped(gosiaScope))
        guard.reset() // An unscoped legacy reset after a quarantine.
        assertTrue("Gosia evidence survives an unscoped reset", guard.isTtsTripped(gosiaScope))
        guard.reset(gosiaScope)
        assertFalse(guard.isTtsTripped(gosiaScope))
    }

    @Test fun `the sole Gosia voice uses its language and model scope`() {
        assertEquals("pl:${TtsVoiceCatalog.GOSIA.modelDirId}", AsrLoadGuard.ttsScopeOf(TtsVoiceCatalog.GOSIA))
    }

    @Test fun `the shared VAD counter alone is reported by isVadTripped`() {
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_VAD)
        assertTrue(guard.isVadTripped())
        guard.reset()
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        guard.noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        assertFalse(guard.isVadTripped())
    }
}
