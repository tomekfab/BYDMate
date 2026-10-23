package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bounded natural Polish wording of verified actions: word order, polite openers, seat and drive
 *  forms, relative temperature, Home/Work navigation, questions and "potem" sequences. Every
 *  expectation is a whole-phrase dictionary match; nothing here is fuzzy. */
class NaturalPolishCommandsTest {

    private fun actual(text: String) = PolishGoldenCorpus.actual(text)
    private fun verdict(text: String) = NluParser.classify(text, VoiceLanguage.PL)

    private fun assertRefused(reason: String, text: String) {
        assertEquals(text, NluVerdict.Refused(reason), verdict(text))
        assertEquals(text, ParseResult.Unrecognized, NluParser.parse(text, VoiceLanguage.PL))
    }

    /** Spoken text -> what the dictionary runs, rendered like the golden corpus. */
    private val runs = linkedMapOf(
        // Word order and halfway forms.
        "Szybę pasażera zamknij." to "副驾打开0",
        "Okno kierowcy otwórz" to "主驾打开100",
        "Okna zamknij" to "车窗关闭",
        "Do połowy otwórz okno." to "主驾半开",
        "Okno pasażera do połowy otwórz" to "副驾半开",
        "Szyberdach zamknij" to "天窗打开0",
        "Do połowy otwórz szyberdach" to "天窗打开50",
        // Seats.
        "Podgrzej fotel kierowcy" to "主驾座椅加热1档",
        "Podgrzej fotel pasażera na 2" to "副驾座椅加热2档",
        "Włącz grzanie fotela" to "主驾座椅加热1档",
        "Wyłącz grzanie fotela pasażera" to "副驾座椅加热关闭",
        "Włącz chłodzenie fotela kierowcy" to "主驾座椅通风1档",
        "Wyłącz chłodzenie siedzenia pasażera" to "副驾座椅通风关闭",
        // Drive mode and shade.
        "Przełącz na sport" to "运动模式",
        "Przełącz na tryb sportowy" to "运动模式",
        "Przełącz na eko" to "ECO模式",
        "Zasłoń roletę" to "遮阳帘关闭",
        "Odsłoń roletę" to "遮阳帘打开",
        // Relative temperature keeps its signed magnitude.
        "Podnieś temperaturę" to "TEMP:+1",
        "Obniż temperaturę" to "TEMP:-1",
        "Podnieś temperaturę o dwa stopnie" to "TEMP:+2",
        "Obniż o dwa stopnie" to "TEMP:-2",
        "Obniż o 3 stopnie" to "TEMP:-3",
        "Zwiększ temperaturę o 5 stopni" to "TEMP:+5",
        // Home and Work.
        "Prowadź do domu" to "NAVIGATE_HOME",
        "Jedźmy do domu" to "NAVIGATE_HOME",
        "Włącz nawigację do domu" to "NAVIGATE_HOME",
        "Nawiguj do domu." to "NAVIGATE_HOME",
        "Nawiguj do pracy" to "NAVIGATE_WORK",
        "Prowadź do pracy" to "NAVIGATE_WORK",
        "Jedźmy do pracy" to "NAVIGATE_WORK",
        "Włącz nawigację do pracy" to "NAVIGATE_WORK",
        // Questions.
        "Ile procent baterii" to "ASK:soc",
        "Jaki mamy zasięg" to "ASK:range",
        "Ile jest na dworze" to "ASK:outside_temp",
        // Polite openers.
        "Czy możesz zamknąć okno" to "主驾打开0",
        "Czy mógłbyś zamknąć okno" to "主驾打开0",
        "Chciałbym otworzyć bagażnik" to "开后备箱",
        "Chciałabym otworzyć bagażnik" to "开后备箱",
        // Independently complete sequences.
        "Zamknij okna potem otwórz roletę" to "车窗关闭+遮阳帘打开",
        "Zamknij okna i potem otwórz roletę" to "车窗关闭+遮阳帘打开",
    )

    @Test fun polite_everything_and_shade_infinitives_are_refused_locally() {
        for (text in listOf(
            "czy możesz zamknąć wszystko", "czy mógłbyś otworzyć wszystko",
            "chciałabym zamknąć wszystko", "zasłonić dach", "odsłonić dach",
        )) assertRefused(NluRefusal.UNSUPPORTED, text)
    }

    @Test fun natural_forms_resolve_to_their_dictionary_result() {
        val wrong = runs.filter { (text, expected) -> actual(text) != expected }
            .map { (text, expected) -> "\"$text\" => ${actual(text)} (want $expected)" }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun every_natural_form_is_refused_when_negated_quoted_or_reported() {
        val bad = runs.keys.flatMap { text ->
            listOf(
                "nie $text" to NluRefusal.NEGATED,
                "\"$text\"" to NluRefusal.QUOTED,
                "powiedział $text" to NluRefusal.QUOTED,
            ).filter { (variant, reason) -> verdict(variant) != NluVerdict.Refused(reason) }
                .map { (variant, reason) -> "\"$variant\" => ${verdict(variant)} (want $reason)" }
        }
        assertTrue(bad.take(15).joinToString("\n"), bad.isEmpty())
    }

    @Test fun local_navigation_is_refused_when_negated_quoted_or_reported() {
        val text = "nawiguj na ulicę Janowskiego 82"
        for ((variant, reason) in listOf(
            "nie $text" to NluRefusal.NEGATED,
            "\"$text\"" to NluRefusal.QUOTED,
            "powiedział $text" to NluRefusal.QUOTED,
        )) assertRefused(reason, variant)
    }

    @Test fun every_natural_command_with_a_tail_is_refused_as_partial() {
        val commandsOnly = runs.filterValues { !it.startsWith("ASK:") }.keys
        val bad = commandsOnly.filter { verdict("$it i coś jeszcze") != NluVerdict.Refused(NluRefusal.PARTIAL) }
        assertTrue(bad.take(15).joinToString("\n"), bad.isEmpty())
    }

    @Test fun ambiguous_requests_are_refused_locally_never_resolved_to_a_control() {
        for (text in listOf(
            "Wyłącz nawiew", "Włącz wentylację", "Wyłącz wentylację", "Zamknij wszystko", "Otwórz wszystko",
            "Wyłącz wszystko", "Włącz wszystko", "Proszę zamknij wszystko",
        )) assertRefused(NluRefusal.UNSUPPORTED, text)
        // A qualifier names the control, so it stays a command.
        assertEquals("打开空调通风", actual("Włącz wentylację kabiny"))
    }

    @Test fun cabin_heating_massage_and_side_temperature_stay_unsupported() {
        for (text in listOf(
            "Włącz ogrzewanie", "Włącz grzanie", "Podgrzej kabinę", "Włącz grzanie kabiny", "Włącz masaż fotela pasażera",
            "Podnieś temperaturę po stronie pasażera", "Obniż temperaturę kierowcy", "Obniż temperaturę pasażera",
            "Obniż o 6 stopni", "Obniż o 0 stopni", "Podnieś o dwadzieścia stopni",
        )) assertRefused(NluRefusal.UNSUPPORTED, text)
    }

    @Test fun mixed_typed_sequences_are_refused_whole() {
        for (text in listOf(
            "Nawiguj do domu potem zamknij okna",
            "Zamknij okna potem nawiguj do pracy",
            "Podnieś temperaturę potem zamknij okna",
            "Zamknij okna i potem obniż o dwa stopnie",
            "Zrób ciszej potem zamknij okna",
            "Zamknij okna i potem zrób ciszej",
            "Nawiguj do domu i potem nawiguj do pracy",
            "Podnieś temperaturę i zamknij okna",
            "nawiguj do Janowskiego 82 i zamknij okna",
        )) assertRefused(NluRefusal.PARTIAL, text)
    }

    @Test fun sequences_refuse_the_whole_utterance_for_an_unsupported_negated_or_incomplete_clause() {
        for (text in listOf(
            "Zamknij okna potem", "Zamknij okna i potem", "Zamknij okna potem otwórz maskę",
            "Zamknij okna potem coś jeszcze", "Zamknij okna potem otwórz roletę potem",
        )) assertRefused(NluRefusal.PARTIAL, text)
        assertRefused(NluRefusal.NEGATED, "Zamknij okna potem nie otwieraj rolety")
        assertRefused(NluRefusal.NEGATED, "Zamknij okna i potem nie otwieraj rolety")
        assertRefused(NluRefusal.QUOTED, "Powiedział zamknij okna potem otwórz roletę")
        assertTrue(verdict("Potem zamknij okna") is NluVerdict.Refused)
    }

    @Test fun a_polite_opener_counts_only_at_the_start() {
        assertTrue(verdict("Zamknij okno czy możesz") is NluVerdict.Refused)
        assertTrue(verdict("Zamknij okno chciałbym") is NluVerdict.Refused)
        assertTrue(verdict("Czy zamknąć okno") is NluVerdict.Refused)
        assertEquals(NluVerdict.Unknown, verdict("Czy możesz"))
        assertEquals(NluVerdict.Unknown, verdict("Czy możesz opowiedzieć dowcip"))
    }

    @Test fun unlisted_spellings_and_nearby_words_never_match() {
        for (text in listOf("Podgrzej fotal kierowcy", "Prowadź dom", "Jedźmy domku", "Przełącz na sporty")) {
            assertEquals(text, ParseResult.Unrecognized, NluParser.parse(text, VoiceLanguage.PL))
        }
        assertEquals(
            ParseResult.NavigateDestination("kina"),
            NluParser.parse("Prowadź do kina", VoiceLanguage.PL),
        )
    }
}
