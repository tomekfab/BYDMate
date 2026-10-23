package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsVoiceCatalogTest {
    @Test fun `legacy and unknown ids resolve to Gosia`() {
        listOf("dmitri", "alena", "ruslan", "irina", "mark", "sofia", "", "unknown").forEach {
            assertEquals("gosia", TtsVoiceCatalog.byId(it).id)
        }
    }

    @Test fun `Gosia is the sole Polish local voice`() {
        assertEquals(listOf("gosia"), TtsVoiceCatalog.ALL.map { it.id })
        assertEquals(TtsVoiceEngine.PIPER, TtsVoiceCatalog.GOSIA.engine)
        assertEquals(VoiceLanguage.PL, TtsVoiceCatalog.defaultFor().language)
    }
}
