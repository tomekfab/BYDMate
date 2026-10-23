package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTriggerValidationTest {
    @Test fun `blank phrase is Empty`() {
        assertEquals(VoiceTriggerValidation.Collision.Empty,
            VoiceTriggerValidation.check("  ", emptyMap()))
    }
    @Test fun `phrase made of fillers only is Empty`() {
        assertEquals(VoiceTriggerValidation.Collision.Empty,
            VoiceTriggerValidation.check("ej, proszę", emptyMap()))
    }
    @Test fun `phrase the built-in parser understands is allowed`() {
        // "otwórz okna" is a built-in command, but automations resolve first, so the rule wins.
        assertEquals(VoiceTriggerValidation.Collision.None,
            VoiceTriggerValidation.check("otwórz okna", emptyMap()))
    }
    @Test fun `phrase already used by another rule is OtherRule with its name`() {
        val taken = mapOf(VoicePhrase.normalize("nawigacja") to "Mapa")
        assertEquals(VoiceTriggerValidation.Collision.OtherRule("Mapa"),
            VoiceTriggerValidation.check("nawigacja", taken))
    }
    @Test fun `user phrase of a built-in command is UserCommandPhrase with the command name`() {
        val userPhrases = mapOf(VoicePhrase.normalize("świeże powietrze") to "wszystkie okna na wietrzenie")
        assertEquals(VoiceTriggerValidation.Collision.UserCommandPhrase("wszystkie okna na wietrzenie"),
            VoiceTriggerValidation.check("Świeże powietrze!", emptyMap(), userPhrases))
    }
    @Test fun `only an exact duplicate collides, a longer phrase does not`() {
        val taken = mapOf(VoicePhrase.normalize("nawigacja") to "Mapa")
        assertEquals(VoiceTriggerValidation.Collision.None,
            VoiceTriggerValidation.check("otwórz nawigację", taken))
    }
    @Test fun `another inflection of a taken phrase does not collide`() {
        val taken = mapOf(VoicePhrase.normalize("otwórz okna") to "Przewietrz")
        assertEquals(VoiceTriggerValidation.Collision.None, VoiceTriggerValidation.check("otwórz okno", taken))
        assertEquals(VoiceTriggerValidation.Collision.None,
            VoiceTriggerValidation.check("otwórz okno", emptyMap(), mapOf(VoicePhrase.normalize("otwórz okna") to "Okna")))
        assertEquals(VoiceTriggerValidation.Collision.OtherRule("Przewietrz"),
            VoiceTriggerValidation.check("Otwórz, okna!", taken))
    }
    @Test fun `fresh custom phrase is None`() {
        assertEquals(VoiceTriggerValidation.Collision.None,
            VoiceTriggerValidation.check("jedziemy", emptyMap()))
    }
}
