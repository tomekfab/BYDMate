package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNameMatcherTest {
    @Test fun `matches exact, case and diacritic insensitive`() {
        assertTrue(AgentNameMatcher.matches("Łucja, stój", "Łucja"))
        assertTrue(AgentNameMatcher.matches("ej lucja", "Łucja"))
        assertTrue(AgentNameMatcher.matches("lucja stop", "Łucja"))
    }
    @Test fun `matches ASR near-misses within distance 1 for length ge 4`() {
        assertTrue(AgentNameMatcher.matches("lucjo poczekaj", "Łucja"))
        assertTrue(AgentNameMatcher.matches("ania stój", "Ania"))
    }
    @Test fun `short names require exact token, longer words do not match`() {
        assertFalse(AgentNameMatcher.matches("olaf dzwonił", "Ola"))
        assertFalse(AgentNameMatcher.matches("olaś", "Ola"))   // len 3 -> exact only
        assertFalse(AgentNameMatcher.matches("otwórz okno", "Ola"))
    }
    @Test fun `blank name never matches`() {
        assertFalse(AgentNameMatcher.matches("łucja stój", ""))
    }
    @Test fun `strips leading name token with punctuation`() {
        assertEquals("otwórz okno", AgentNameMatcher.stripLeadingName("Łucja, otwórz okno", "Łucja"))
        assertEquals("otwórz okno", AgentNameMatcher.stripLeadingName("otwórz okno", "Łucja"))
    }
    @Test fun `name-only utterance is returned unchanged`() {
        assertEquals("Łucja", AgentNameMatcher.stripLeadingName("Łucja", "Łucja"))
    }
}
