package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Polish-only safety sweep retained from the former bilingual grammar test. */
class PolishCommandSafetyTest {
    private companion object {
        val dictionary = VoiceDictionary.load(VoiceLanguage.PL)
        val phrases = dictionary.phrases().map { it.first }.distinct().toList()
        fun sample(n: Int) = phrases.filterIndexed { index, _ -> index % n == 0 }
    }

    private fun verdict(text: String) = NluParser.classify(text, VoiceLanguage.PL)
    private fun failures(bad: List<String>) = bad.take(15).joinToString("\n")
    private fun assertRefused(reason: String, text: String) {
        assertEquals(NluVerdict.Refused(reason), verdict(text))
        assertEquals(ParseResult.Unrecognized, NluParser.parse(text, VoiceLanguage.PL))
    }

    @Test fun dictionary_is_large_enough_for_the_exhaustive_sweep() = assertTrue(phrases.size > 1000)

    @Test fun negation_around_every_phrase_dispatches_nothing() {
        val before = phrases.filter { verdict("nie $it") != NluVerdict.Refused(NluRefusal.NEGATED) }
        val after = phrases.filter { verdict("$it nie") != NluVerdict.Refused(NluRefusal.NEGATED) }
        assertTrue(failures(before), before.isEmpty())
        assertTrue(failures(after), after.isEmpty())
    }

    @Test fun quoted_and_reported_speech_dispatch_nothing() {
        val quoted = sample(4).filter { verdict("\"$it\"") != NluVerdict.Refused(NluRefusal.QUOTED) }
        assertTrue(failures(quoted), quoted.isEmpty())
        for (marker in listOf("powiedział", "mówi", "powtórz", "polecenie", "cytuję", "napisz")) {
            val bad = sample(20).filter { verdict("$marker $it") != NluVerdict.Refused(NluRefusal.QUOTED) }
            assertTrue("$marker\n${failures(bad)}", bad.isEmpty())
        }
    }

    @Test fun unknown_affixes_and_tails_never_extract_a_command() {
        val bad = sample(5).flatMap { phrase ->
            listOf("$phrase na palec", "$phrase teraz", "$phrase i", "teraz $phrase", "wtedy $phrase")
        }.filter { NluParser.parse(it, VoiceLanguage.PL) != ParseResult.Unrecognized }
        assertTrue(failures(bad), bad.isEmpty())
    }

    @Test fun unsupported_and_partial_requests_are_refused_locally() {
        for (text in listOf("otwórz maskę", "otwórz drzwi", "włącz silnik", "włącz tempomat", "włącz światła", "złóż lusterka", "zamknij drzwi")) {
            assertRefused(NluRefusal.UNSUPPORTED, text)
        }
        assertRefused(NluRefusal.PARTIAL, "otwórz okna i otwórz maskę")
        assertRefused(NluRefusal.PARTIAL, "otwórz okna i")
        assertRefused(NluRefusal.NEGATED, "otwórz okna i nie zamykaj okna")
    }

    @Test fun every_word_a_command_can_start_with_is_known_to_the_refusal_check() {
        // New verbs and nouns of the natural forms: negated and reported speech must be refused
        // even when nothing else in the utterance names a control.
        for (word in listOf(
            "prowadź", "prowadz", "jedź", "jedz", "jedźmy", "jedzmy", "obniż", "obniz", "zasłoń", "odsłoń",
            "przełącz", "podgrzej", "grzanie", "chłodzenie", "dworze",
        )) {
            assertEquals("nie $word", NluRefusal.NEGATED, PolishVoiceTextRules.commandLikeRefusal("nie $word"))
            assertEquals("powiedział $word", NluRefusal.QUOTED, PolishVoiceTextRules.commandLikeRefusal("powiedział $word"))
        }
    }

    @Test fun harmless_requests_remain_unknown() {
        for (text in listOf(
            "jaka będzie pogoda jutro", "opowiedz mi dowcip", "włącz muzykę", "otwórz plik", "",
            "jedzenie jest pyszne", "włącz nawigację", "czy możesz opowiedzieć dowcip",
        )) {
            assertEquals(text, NluVerdict.Unknown, verdict(text))
        }
    }

    @Test fun incomplete_or_compound_local_navigation_does_not_become_a_destination() {
        assertEquals(NluVerdict.Refused(NluRefusal.NAV_INCOMPLETE), verdict("nawiguj do"))
        assertEquals(NluVerdict.Refused(NluRefusal.NAV_INCOMPLETE), verdict("prowadź na"))
        assertEquals(NluVerdict.Unknown, verdict("włącz nawigację"))
        assertEquals(NluVerdict.Unknown, verdict("nawiguj do Lecha i Marii"))
    }

    @Test fun templates_are_unambiguous_and_reject_reserved_words() {
        assertTrue(dictionary.conflicts().joinToString("\n"), dictionary.conflicts().isEmpty())
        val conflicting = VoiceDictionary.parse("otwórz okno => 主驾打开100\notwórz okno => 车窗全开", PolishVoiceTextRules)
        assertEquals(1, conflicting.conflicts().size)
        assertNull(conflicting.match(PolishVoiceTextRules.words("otwórz okno")))
        assertEquals(NluVerdict.Refused(NluRefusal.AMBIGUOUS), NluParser.classify("otwórz okno", conflicting, PolishVoiceTextRules))
        for (word in listOf("proszę", "nie", "bez", "powiedział", "polecenie")) {
            assertThrows(IllegalArgumentException::class.java) {
                VoiceDictionary.parse("otwórz $word okno => 主驾打开100", PolishVoiceTextRules)
            }
        }
    }

    /** Runtime matching must never expand the grammar: [VoiceDictionary.phrases] and
     *  [VoiceDictionary.conflicts] are JVM audits. The loader's `beforeAudit` hook counts every audit
     *  traversal of a dictionary built from the real resource. */
    @Test fun runtime_matcher_never_calls_the_audit_traversal() {
        val text = requireNotNull(VoiceDictionary::class.java.classLoader?.getResourceAsStream(VoiceDictionary.PL_RESOURCE)) {
            "${VoiceDictionary.PL_RESOURCE} is missing"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        var audits = 0
        val counted = VoiceDictionaryLoader(text, PolishVoiceTextRules).build(beforeAudit = { audits++ })

        for (utterance in listOf(
            "otwórz okna", "nie otwórz okna", "otwórz okna nie", "\"otwórz okna\"", "powiedział otwórz okna",
            "otwórz okna i zamknij okna", "otwórz okna i otwórz maskę", "otwórz okna i nie zamykaj okna",
            "otwórz maskę", "jaka będzie pogoda jutro", "otwórz okna na palec", "",
        )) {
            NluParser.classify(utterance, counted, PolishVoiceTextRules)
            NluParser.parse(utterance, counted)
            counted.match(PolishVoiceTextRules.words(utterance))
        }
        assertEquals("runtime matching walked the audit traversal (phrases()/conflicts())", 0, audits)

        // Control: the hook does fire for an explicit audit, so the zero above is meaningful.
        counted.conflicts()
        assertEquals(1, audits)
        counted.phrases().first()
        assertEquals(2, audits)
    }

    @Test fun failed_polish_dictionary_still_refuses_command_like_text() {
        assertEquals(NluVerdict.Refused(NluRefusal.NEGATED), NluParser.classify("nie otwieraj okna", null, PolishVoiceTextRules))
        assertEquals(NluVerdict.Refused(NluRefusal.UNSUPPORTED), NluParser.classify("otwórz okno", null, PolishVoiceTextRules))
        assertEquals(NluVerdict.Unknown, NluParser.classify("jaka będzie pogoda", null, PolishVoiceTextRules))
    }
}
