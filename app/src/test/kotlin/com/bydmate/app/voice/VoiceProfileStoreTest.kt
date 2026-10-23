package com.bydmate.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceProfileStoreTest {
    private fun store(name: String): VoiceProfileStore {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return VoiceProfileStore(prefs)
    }

    @Test fun `absent retired unknown and wrong typed language selections resolve to Polish`() {
        listOf<Pair<String, (android.content.SharedPreferences.Editor) -> Unit>>(
            "missing" to { it.remove("active_language") },
            "retired" to { it.putString("active_language", "ru") },
            "unknown" to { it.putString("active_language", "xx") },
            "wrong" to { it.putInt("active_language", 1) },
        ).forEachIndexed { index, (_, seed) ->
            val prefs = ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences("profile-$index", Context.MODE_PRIVATE)
            prefs.edit().clear().also(seed).commit()
            assertEquals(VoiceLanguage.PL, VoiceProfileStore(prefs).activeLanguage())
        }
    }

    @Test fun `already Polish still requires all local assets`() {
        val result = kotlinx.coroutines.runBlocking {
            store("not-ready").activate(VoiceLanguage.PL, ProfileReadiness(
                asr = { listOf("missing") }, vad = { true }, tts = { true },
            ))
        }
        assertTrue(result is ActivationResult.NotReady)
    }
}
