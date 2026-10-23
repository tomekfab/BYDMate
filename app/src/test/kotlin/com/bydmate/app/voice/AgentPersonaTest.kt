package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AgentPersonaTest {
    @Test fun `fromId maps known ids and defaults to navigator`() {
        assertEquals(AgentPersona.SNARKY, AgentPersona.fromId("snarky"))
        assertEquals(AgentPersona.ENGINEER, AgentPersona.fromId("engineer"))
        assertEquals(AgentPersona.NAVIGATOR, AgentPersona.fromId("unknown"))
    }

    @Test fun `Polish persona phrases preserve identity and gender`() {
        val identity = AgentIdentity("Ola", AgentPersona.NAVIGATOR, TtsGender.FEMALE)
        val prompt = AgentPersonaPrompt.block(identity)
        assertTrue(prompt.contains("Ola"))
        assertFalse(prompt.contains("—"))
        assertTrue(AgentPersona.NAVIGATOR.spokenPhrase("Gotowe", Random(1), TtsGender.FEMALE).isNotBlank())
        assertTrue(AgentPersona.NAVIGATOR.fillerPhrase(Random(1)).isNotBlank())
        assertTrue(AgentPersona.NAVIGATOR.phrases(TtsGender.FEMALE).isNotEmpty())
    }
}
