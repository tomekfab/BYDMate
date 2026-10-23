package com.bydmate.app.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Contract tests for preferences written by pre-Polish-only builds. The fixture deliberately uses
 * persisted legacy strings, because the production enum is Polish-only.
 * migration.
 */
@RunWith(RobolectricTestRunner::class)
class PolishOnlyMigrationTest {

    private val prefs: SharedPreferences
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("polish_only_migration_test", Context.MODE_PRIVATE)

    @Test fun `legacy language selections resolve to Polish and legacy TTS selections resolve to Gosia`() {
        val cases: List<Pair<String, SharedPreferences.Editor.() -> Unit>> = listOf(
            "missing" to { remove("active_language") },
            "retired Russian" to { putString("active_language", "ru") },
            "unknown" to { putString("active_language", "xx") },
            "wrong type" to { putInt("active_language", 42) },
        )

        cases.forEach { (name, seed) ->
            prefs.edit().clear().apply(seed).commit()
            assertEquals(name, VoiceLanguage.PL, VoiceProfileStore(prefs).activeLanguage())
            assertEquals(name, "gosia", TtsVoiceCatalog.byId("dmitri").id)
        }
    }
}
