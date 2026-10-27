package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** The matcher: a phrase runs only when the dictionary holds it whole. Per-phrase expectations
 *  live in the golden corpus; these pin the matching rules themselves. */
class NluParserTest {

    private fun commands(text: String): List<String>? = (NluParser.parse(text) as? ParseResult.Command)?.commands

    @Test fun phrases_not_in_the_dictionary_go_to_the_agent() {
        for (phrase in listOf(
            "nie otwieraj okna",
            "otwórz okno na palec",
            "poczekaj i otwórz okna",
            "otwieramy bagażnik",
            "wyłącz wentylację kierownicy",
            "prawe siedzenie kierowcy",
            "włącz ogrzewanie fotela na trzy procent",
        )) {
            assertEquals(phrase, ParseResult.Unrecognized, NluParser.parse(phrase))
        }
    }

    @Test fun polite_words_are_dropped_anywhere() {
        assertEquals(listOf("主驾半开"), commands("otwórz okno na pięćdziesiąt proszę procent"))
    }

    @Test fun listed_forms_resolve() {
        assertEquals(listOf("吹前挡"), commands("włącz nawiew na przednią szybę"))
        assertEquals(listOf("后视镜加热"), commands("włącz ogrzewanie wszystkich lusterek"))
        assertEquals(listOf("风量3"), commands("ustaw wentylator na trzeci bieg"))
    }

    @Test fun qualifiers_and_except_resolve() {
        assertEquals(listOf("前排车窗全开"), commands("otwórz okna kierowcy i pasażera"))
        assertEquals(listOf("副驾打开0", "后左打开0", "后右打开0"), commands("zamknij okna oprócz kierowcy"))
    }

    @Test fun a_compound_runs_only_when_every_part_is_a_command() {
        assertEquals(listOf("天窗打开100", "车窗关闭"), commands("otwórz szyberdach i zamknij okna"))
        assertEquals(listOf("天窗打开100", "车窗关闭"), commands("otwórz szyberdach a także zamknij okna"))
        assertEquals(ParseResult.Unrecognized, NluParser.parse("otwórz okna i szyberdach dokładnie na pół"))
        assertEquals(ParseResult.Unrecognized, NluParser.parse("jest gorąco i zimno"))
        assertEquals(ParseResult.Unrecognized, NluParser.parse("otwórz szyberdach i"))
    }

    @Test fun a_verbless_variant_never_carries_its_own_part_of_a_compound() {
        assertEquals(ParseResult.Unrecognized, NluParser.parse("wyłącz ogrzewanie kierownicy i ogrzewanie siedzeń"))
        assertEquals(
            ParseResult.Unrecognized,
            NluParser.parse("wyłącz ogrzewanie fotela kierowcy i ogrzewanie fotela pasażera"),
        )
        assertEquals(listOf("方向盘加热", "主驾座椅加热1档", "副驾座椅加热1档"), commands("włącz ogrzewanie kierownicy i siedzeń"))
        assertEquals(listOf("主驾座椅加热1档", "副驾座椅加热1档"), commands("ogrzewanie siedzeń"))
    }

    @Test fun empty_and_filler_only_are_unrecognized() {
        assertEquals(ParseResult.Unrecognized, NluParser.parse(""))
        assertEquals(ParseResult.Unrecognized, NluParser.parse("proszę dziękuję"))
    }

    @Test fun a_failed_dictionary_load_is_unrecognized_not_a_crash() {
        assertEquals(ParseResult.Unrecognized, NluParser.parse("otwórz okno", null))
    }
    @Test fun observed_car_phrases_resolve_offline() {
        assertEquals(listOf("副驾座椅通风关闭"), commands("Wyłącz nawiew fotela pasażera."))
        assertEquals(listOf("副驾打开0"), commands("Zamknij szyb pasażera."))
        assertEquals(listOf("天窗打开100"), commands("otwórz się werdach."))
        assertEquals(listOf("主驾座椅按摩打开"), commands("Włącz masaż w fotelu kierowcy."))
        assertEquals(listOf("主驾座椅按摩打开"), commands("Włącz masaż fotela kierowcy."))
    }

    @Test fun seat_massage_is_local_driver_on_and_off_only() {
        val on = listOf("主驾座椅按摩打开")
        val off = listOf("主驾座椅按摩关闭")
        for (text in listOf(
            "Włącz masaż fotela kierowcy.", "włącz masaż fotela", "włącz masaż w fotelu", "Włącz masaż w fotelu kierowcy.",
            "wlacz masaz fotela", "wlacz masaz fotela kierowcy",
        )) assertEquals(text, on, commands(text))
        for (text in listOf(
            "wyłącz masaż fotela kierowcy", "wyłącz masaż w fotelu", "wylacz masaz w fotelu kierowcy", "zgaś masaż fotela",
        )) assertEquals(text, off, commands(text))
        assertEquals(on + "主驾座椅加热1档", commands("włącz masaż fotela kierowcy i podgrzej fotel kierowcy"))
        for ((text, reason) in listOf(
            "nie włączaj masażu fotela kierowcy" to NluRefusal.NEGATED,
            "nie włącz masaż fotela" to NluRefusal.NEGATED,
            "włącz masaż fotela kierowcy nie" to NluRefusal.NEGATED,
            "niewłączaj masaż fotela" to NluRefusal.NEGATED,
            "„włącz masaż fotela kierowcy”" to NluRefusal.QUOTED,
            "\"wyłącz masaż fotela\"" to NluRefusal.QUOTED,
            "powiedział włącz masaż fotela kierowcy" to NluRefusal.QUOTED,
            "włącz masaż fotela kierowcy i coś jeszcze" to NluRefusal.PARTIAL,
            "włącz masaż fotela i otwórz maskę" to NluRefusal.PARTIAL,
            "włącz masaż fotela kierowcy teraz" to NluRefusal.UNSUPPORTED,
            "włącz masaż fotela kierowcy na maksimum" to NluRefusal.UNSUPPORTED,
            "włącz masaż fal fotela" to NluRefusal.UNSUPPORTED,
            "włącz masaż pulsacyjny w fotelu" to NluRefusal.UNSUPPORTED,
            "włącz masaż fotela pasażera" to NluRefusal.UNSUPPORTED,
            "włącz masaż foteli" to NluRefusal.UNSUPPORTED,
            "włącz masaż siedzenia" to NluRefusal.UNSUPPORTED,
            "włącz masaż" to NluRefusal.UNSUPPORTED,
            "wyłącz masaż" to NluRefusal.UNSUPPORTED,
        )) assertEquals(text, NluVerdict.Refused(reason), NluParser.classify(text, VoiceLanguage.PL))
    }

    @Test fun observed_car_repairs_keep_refusal_boundaries() {
        for ((text, reason) in listOf(
            "nie otwórz się werdach" to NluRefusal.NEGATED,
            "„otwórz się werdach”" to NluRefusal.QUOTED,
            "otwórz się werdach i coś jeszcze" to NluRefusal.PARTIAL,
            "Włącz ogrzewanie." to NluRefusal.UNSUPPORTED,
            "zamknij werdach" to NluRefusal.UNSUPPORTED,
            "otwórz szyb pasażera" to NluRefusal.UNSUPPORTED,
            "zamknij szyb kierowcy" to NluRefusal.UNSUPPORTED,
            "włącz nawiew fotela pasażera" to NluRefusal.UNSUPPORTED,
            "wyłącz nawiew fotela kierowcy" to NluRefusal.UNSUPPORTED,
        )) assertEquals(text, NluVerdict.Refused(reason), NluParser.classify(text, VoiceLanguage.PL))
    }

    @Test fun relative_temperature_keeps_its_signed_magnitude() {
        assertEquals(ParseResult.RelativeTemp(1), NluParser.parse("podnieś temperaturę", VoiceLanguage.PL))
        assertEquals(ParseResult.RelativeTemp(-1), NluParser.parse("zrób chłodniej", VoiceLanguage.PL))
        assertEquals(ParseResult.RelativeTemp(2), NluParser.parse("podnieś temperaturę o dwa stopnie", VoiceLanguage.PL))
        assertEquals(ParseResult.RelativeTemp(-2), NluParser.parse("obniż o dwa stopnie", VoiceLanguage.PL))
        assertEquals(ParseResult.RelativeTemp(-5), NluParser.parse("obniż temperaturę o 5 stopni", VoiceLanguage.PL))
    }

    @Test fun home_and_work_are_typed_results() {
        for (text in listOf("nawiguj do domu", "prowadź do domu", "jedźmy do domu", "włącz nawigację do domu")) {
            assertEquals(text, ParseResult.NavigateHome, NluParser.parse(text, VoiceLanguage.PL))
        }
        for (text in listOf("nawiguj do pracy", "prowadź do pracy", "jedźmy do pracy", "włącz nawigację do pracy")) {
            assertEquals(text, ParseResult.NavigateWork, NluParser.parse(text, VoiceLanguage.PL))
        }
    }

    @Test fun local_polish_destinations_keep_the_raw_suffix() {
        for ((text, destination) in listOf(
            "nawiguj na ulicę Janowskiego 82" to "ulicę Janowskiego 82",
            "prowadź do kina" to "kina",
            "jedź do Szybowcowa" to "Szybowcowa",
            "jedz na Klimontowska" to "Klimontowska",
            "jedźmy do Lampowa" to "Lampowa",
            "włącz nawigację na Dachowa" to "Dachowa",
            "uruchom nawigacje do Janowskiego dwadzieścia dwa" to "Janowskiego dwadzieścia dwa",
        )) {
            assertEquals(text, ParseResult.NavigateDestination(destination), NluParser.parse(text, VoiceLanguage.PL))
        }
    }

    @Test fun call_phrases_keep_the_raw_contact_name() {
        for ((text, name) in listOf(
            "zadzwoń do Jola Gil" to "Jola Gil",
            "zadzwon do Yola Gil" to "Yola Gil",
            "Zadzwoń do mamy." to "mamy",
            "dzwoń do Tomka" to "Tomka",
            "zadzwoń proszę do Kasi" to "Kasi",
            "połącz się z Kasią" to "Kasią",
            "połącz z Jolą Gil" to "Jolą Gil",
        )) {
            assertEquals(text, ParseResult.CallContact(name), NluParser.parse(text, VoiceLanguage.PL))
        }
    }

    @Test fun call_phrases_cannot_bypass_negation_or_quotation_and_need_a_name() {
        for ((text, reason) in listOf(
            "nie dzwoń do mamy" to NluRefusal.NEGATED,
            "nie zadzwoń do Jola Gil" to NluRefusal.NEGATED,
            "powiedział zadzwoń do mamy" to NluRefusal.QUOTED,
            "zadzwoń do" to NluRefusal.CALL_INCOMPLETE,
            "zadzwoń do." to NluRefusal.CALL_INCOMPLETE,
        )) {
            assertEquals(text, NluVerdict.Refused(reason), NluParser.classify(text, VoiceLanguage.PL))
        }
    }

    @Test fun destination_navigation_cannot_bypass_negation_or_quotation() {
        for ((text, reason) in listOf(
            "nawiguj do Janowskiego 82, niejedź tam" to NluRefusal.NEGATED,
            "nawiguj do Janowskiego 82, niejedz tam" to NluRefusal.NEGATED,
            "prowadź do Janowskiego 82, nienawiguj tam" to NluRefusal.NEGATED,
            "nawiguj do Janowskiego 82, nie jedź tam" to NluRefusal.NEGATED,
            "nawiguj do Janowskiego 82, powiedział" to NluRefusal.QUOTED,
            "„nawiguj do Janowskiego 82”" to NluRefusal.QUOTED,
        )) {
            assertEquals(text, NluVerdict.Refused(reason), NluParser.classify(text, VoiceLanguage.PL))
        }
    }

    @Test fun destination_navigation_still_precedes_generic_refusal_and_ambiguous_dictionary_candidates() {
        val text = "nawiguj do Lampowa"
        assertEquals(NluRefusal.UNSUPPORTED, PolishVoiceTextRules.commandLikeRefusal(text))
        val conflicting = VoiceDictionary.parse(
            "$text => 主驾打开100\n$text => 车窗全开", PolishVoiceTextRules,
        )
        assertEquals(1, conflicting.conflicts().size)
        assertEquals(
            NluVerdict.Match(ParseResult.NavigateDestination("Lampowa")),
            NluParser.classify(text, conflicting, PolishVoiceTextRules),
        )
    }

    @Test fun a_potem_sequence_is_one_ordered_command_list() {
        assertEquals(listOf("车窗关闭", "遮阳帘打开"), commands("zamknij okna potem otwórz roletę"))
        assertEquals(listOf("车窗关闭", "遮阳帘打开"), commands("zamknij okna i potem otwórz roletę"))
    }
}
