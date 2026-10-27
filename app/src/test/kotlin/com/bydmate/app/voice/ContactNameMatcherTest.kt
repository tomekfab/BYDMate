package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactNameMatcherTest {

    private fun match(query: String, vararg names: String): ContactMatch =
        ContactNameMatcher.match(query, names.map { VoiceContact(it, "+48 $it") })

    private fun direct(name: String) = ContactMatch.Direct(VoiceContact(name, "+48 $name"))
    private fun confirm(name: String) = ContactMatch.Confirm(VoiceContact(name, "+48 $name"))

    @Test fun exact_name_calls_directly() {
        assertEquals(direct("Jola Gil"), match("Jola Gil", "Jola Gil", "Jan Kowalski"))
    }

    @Test fun asr_misspelling_up_to_two_letters_calls_directly() {
        assertEquals(direct("Jola Gil"), match("Yola Gil", "Jola Gil", "Jan Kowalski"))
        assertEquals(direct("Jola Gil"), match("Yola Gill", "Jola Gil", "Jan Kowalski"))
    }

    @Test fun case_diacritics_and_punctuation_do_not_count() {
        assertEquals(direct("Łukasz Dąbrowski"), match("lukasz dabrowski", "Łukasz Dąbrowski", "Jan Kowalski"))
        assertEquals(direct("Jola Gil"), match("JOLA, gil!", "Jola Gil"))
    }

    @Test fun polish_declension_is_a_small_suffix_change() {
        assertEquals(direct("Mama"), match("mamy", "Mama", "Tata"))
        assertEquals(direct("Kasia"), match("Kasi", "Kasia", "Tomek"))
        assertEquals(direct("Tomek"), match("Tomka", "Kasia", "Tomek"))
        assertEquals(direct("Jola Gil"), match("Joli Gil", "Jola Gil", "Jan Kowalski"))
        assertEquals(direct("Jan Kowalski"), match("Jana Kowalskiego", "Jola Gil", "Jan Kowalski"))
    }

    @Test fun declension_does_not_call_a_nearer_looking_wrong_person() {
        // "Marka" is one letter from "Marta" but means "Marek".
        assertEquals(direct("Marek"), match("Marka", "Marta", "Marek"))
        assertEquals(direct("Jacek"), match("Jacka", "Janka", "Jacek"))
    }

    @Test fun a_shorter_spoken_name_matches_a_longer_contact() {
        assertEquals(direct("Anna Maria Nowak"), match("Anna Nowak", "Anna Maria Nowak", "Jan Kowalski"))
        assertEquals(direct("Katarzyna Kasia Nowak"), match("Kasi", "Katarzyna Kasia Nowak", "Tomek"))
    }

    @Test fun one_letter_off_a_short_name_is_another_name() {
        assertEquals(confirm("Ola"), match("Ela", "Ola"))
    }

    @Test fun a_close_runner_up_asks_for_confirmation() {
        // Distances 1 and 2: too close to call either without asking.
        assertEquals(confirm("Jola Gil"), match("Yola Gil", "Jola Gil", "Jola Gilt"))
    }

    @Test fun reversed_word_order_matches() {
        assertEquals(direct("Jola Gil"), match("Gil Jola", "Jola Gil", "Jan Kowalski"))
    }

    @Test fun one_word_matches_a_single_word_of_the_name() {
        assertEquals(direct("Jola Gil"), match("Jola", "Jola Gil", "Jan Kowalski"))
    }

    @Test fun more_than_two_letters_off_asks_for_confirmation() {
        assertEquals(confirm("Jola Gil"), match("Jolanta Gil", "Jola Gil", "Jan Kowalski"))
    }

    @Test fun a_tie_asks_for_confirmation_instead_of_picking() {
        // "Jola Gal" is one letter from both.
        val result = match("Jola Gal", "Jola Gil", "Jola Gul")
        assertEquals(true, result is ContactMatch.Confirm)
        // A first name shared by two contacts is a tie too.
        assertEquals(true, match("Jola", "Jola Gil", "Jola Nowak") is ContactMatch.Confirm)
    }

    @Test fun two_contacts_with_the_same_name_ask_for_confirmation() {
        val book = listOf(VoiceContact("Jan Kowalski", "+48 111"), VoiceContact("Jan Kowalski", "+48 222"))
        assertEquals(ContactMatch.Confirm(book.first()), ContactNameMatcher.match("Jan Kowalski", book))
    }

    @Test fun exact_match_wins_over_a_near_one() {
        assertEquals(direct("Ola"), match("Ola", "Ola", "Ala"))
    }

    @Test fun nothing_plausible_is_not_found() {
        assertEquals(ContactMatch.NotFound, match("Zbigniew Brzęczyszczykiewicz", "Mama", "Jola Gil"))
        assertEquals(ContactMatch.NotFound, match("Jola Gil"))
        assertEquals(ContactMatch.NotFound, match("", "Jola Gil"))
    }

    @Test fun levenshtein_counts_single_letter_edits() {
        assertEquals(0, ContactNameMatcher.distance("jola", "jola"))
        assertEquals(1, ContactNameMatcher.distance("yola", "jola"))
        assertEquals(1, ContactNameMatcher.distance("jola", "jol"))
        assertEquals(3, ContactNameMatcher.distance("kitten", "sitting"))
    }
}
