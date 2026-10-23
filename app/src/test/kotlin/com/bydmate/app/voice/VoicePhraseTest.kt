package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePhraseTest {
    // Collisions are as exact as matching: another inflection is another phrase.
    @Test fun `inflections normalize apart`() {
        assertNotEquals(VoicePhrase.normalize("okienko"), VoicePhrase.normalize("okienka"))
        assertNotEquals(VoicePhrase.normalize("otwórz okno"), VoicePhrase.normalize("otwórz okna"))
    }
    @Test fun `strips punctuation and case and collapses spaces`() {
        assertEquals("jedź do domu", VoicePhrase.normalize("  Jedź do domu!  "))
    }
    @Test fun `blank yields blank`() {
        assertEquals("", VoicePhrase.normalize("   "))
    }

    @Test fun `Polish fillers normalize away`() {
        assertEquals(VoicePhrase.normalize("choinka"), VoicePhrase.normalize("Ej, choinka, proszę"))
    }

    @Test fun `exactness compares words without stemming`() {
        assertFalse(VoicePhrase.isExact("otwórz okno", "otwórz okna"))
        assertTrue(VoicePhrase.isExact("słuchaj otwórz okno proszę", "otwórz okno"))
        assertTrue(VoicePhrase.isExact("Otwórz choinkę!", "otwórz choinkę"))
        assertFalse(VoicePhrase.isExact("proszę", "proszę"))
    }

    @Test fun `a phrase inside a longer utterance is not exact`() {
        assertFalse(VoicePhrase.isExact("otwórz okno w samochodzie", "okno w"))
        assertFalse(VoicePhrase.isExact("okienkować", "okno"))
    }
}
