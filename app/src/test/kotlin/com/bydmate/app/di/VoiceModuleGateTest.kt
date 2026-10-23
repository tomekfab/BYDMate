package com.bydmate.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.voice.VoiceLanguage
import com.bydmate.app.voice.VoiceProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceModuleGateTest {
    @Test fun `close after command is off on a fresh install`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit().clear().commit()

        assertFalse(VoiceModule.provideVoiceGate(context).closeAfterCommand())
    }

    @Test fun `runtime module always selects Polish Gosia`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit()
            .putString("tts_voice", "dmitri").putString("tts_source", "gemini").commit()
        val profile = VoiceProfileStore(context.getSharedPreferences("voice_module_gate", Context.MODE_PRIVATE))
        assertEquals(VoiceLanguage.PL, VoiceModule.provideSessionProfile(profile)().language)
    }
}
