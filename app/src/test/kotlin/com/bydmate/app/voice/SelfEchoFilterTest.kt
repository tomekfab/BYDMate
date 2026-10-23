package com.bydmate.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfEchoFilterTest {

    @Suppress("UNCHECKED_CAST")
    private fun normalizedWords(filter: SelfEchoFilter, text: String): List<String> {
        val method = SelfEchoFilter::class.java.getDeclaredMethod("normalize", String::class.java)
        method.isAccessible = true
        return method.invoke(filter, text) as List<String>
    }

    @Test
    fun `exact repeat of recent TTS phrase is echo`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("trasa aktywna")
        assertTrue(filter.isEcho("trasa aktywna"))
    }

    @Test
    fun `exact repeat case and punctuation insensitive`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("Trasa aktywna")
        assertTrue(filter.isEcho("trasa aktywna!"))
    }

    @Test
    fun `exact repeat uses the legacy yo fold`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("Ёжик")
        assertEquals(
            listOf("ежик"),
            normalizedWords(filter, "Ёжик"),
        )
        assertEquals(
            normalizedWords(filter, "Ёжик"),
            normalizedWords(filter, "ежик"),
        )
        assertTrue(filter.isEcho("ежик"))
    }

    @Test
    fun `Polish speech with diacritics normalizes to nonempty Polish words`() {
        val filter = SelfEchoFilter(now = { 0L })
        assertEquals(listOf("włącz", "światła"), normalizedWords(filter, "Włącz światła!"))
        filter.noteSpoken("Włącz światła!")
        assertFalse(filter.isEcho("otwórz okno"))
    }

    @Test
    fun `Polish diacritic words stay distinct from their stripped forms`() {
        val filter = SelfEchoFilter(now = { 0L })
        assertFalse(normalizedWords(filter, "łódź") == normalizedWords(filter, "d"))
    }

    @Test
    fun `matching echoed Polish phrase with diacritics is detected`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("Włącz światła")
        assertTrue(filter.isEcho("włącz światła"))
    }

    @Test
    fun `torn fragment with containment above threshold is echo`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("Bez tej dramy jestem asystentem z charakterem. Trasa aktywna.")
        // The transcript contains five normalized words, all present in the spoken phrase.
        // Its containment is 5/5 = 1.0, above the 0.75 threshold.
        assertTrue(filter.isEcho("trasa aktywna bez tej dramy"))
    }

    @Test
    fun `real command not matching spoken text is not echo`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("Gotowe. Co dalej?")
        assertFalse(filter.isEcho("uruchom radio"))
    }

    @Test
    fun `single word command 5 seconds after playback end is not echo`() {
        var time = 0L
        val filter = SelfEchoFilter(now = { time })
        time = 0L
        filter.noteSpoken("tak")
        time = 100L
        filter.onPlaybackEnd()
        // 5 seconds = 5100 ms total, SHORT_ECHO_WINDOW_MS = 1500 ms from playback end
        time = 5100L
        assertFalse(filter.isEcho("tak"))
    }

    @Test
    fun `single word with length 4 or more within 1_5s after playback end is echo`() {
        var time = 0L
        val filter = SelfEchoFilter(now = { time })
        time = 0L
        filter.noteSpoken("uruchom radio")
        time = 100L
        filter.onPlaybackEnd()
        // The repeated token has seven letters and arrives 1000 ms after playback ends.
        time = 1100L
        assertTrue(filter.isEcho("uruchom"))
    }

    @Test
    fun `single word with length 4 or more outside 1_5s window is not echo`() {
        var time = 0L
        val filter = SelfEchoFilter(now = { time })
        time = 0L
        filter.noteSpoken("uruchom radio")
        time = 100L
        filter.onPlaybackEnd()
        // 2000ms greater than 1500ms window
        time = 2100L
        assertFalse(filter.isEcho("uruchom"))
    }

    @Test
    fun `single word with length less than 4 is never echo via short window`() {
        var time = 0L
        val filter = SelfEchoFilter(now = { time })
        time = 0L
        filter.noteSpoken("tak, dobrze")
        time = 100L
        filter.onPlaybackEnd()
        // The repeated token has two letters, below the minimum length of four.
        time = 500L
        assertFalse(filter.isEcho("tak"))
    }

    @Test
    fun `phrase older than TTL is not echo`() {
        var time = 0L
        val filter = SelfEchoFilter(now = { time })
        time = 0L
        filter.noteSpoken("trasa aktywna")
        // PHRASE_TTL_MS = 20_000
        time = 20001L  // older than TTL
        assertFalse(filter.isEcho("trasa aktywna"))
    }

    @Test
    fun `phrase within TTL is echo`() {
        var time = 0L
        val filter = SelfEchoFilter(now = { time })
        time = 0L
        filter.noteSpoken("trasa aktywna")
        time = 19999L  // still within 20_000 ms TTL
        assertTrue(filter.isEcho("trasa aktywna"))
    }

    @Test
    fun `buffer holds only 6 phrases max`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("phrase1")
        filter.noteSpoken("phrase2")
        filter.noteSpoken("phrase3")
        filter.noteSpoken("phrase4")
        filter.noteSpoken("phrase5")
        filter.noteSpoken("phrase6")
        filter.noteSpoken("phrase7")  // pushes out phrase1

        assertFalse(filter.isEcho("phrase1"))  // no longer in buffer
        assertTrue(filter.isEcho("phrase2"))   // still in buffer
    }

    @Test
    fun `empty transcript after normalization is garbage`() {
        val filter = SelfEchoFilter(now = { 0L })
        filter.noteSpoken("something")
        assertTrue(filter.isEcho("!!!"))  // only punctuation, empty after normalization
        assertTrue(filter.isEcho("   "))  // only spaces, empty after normalization
        assertTrue(filter.isEcho(""))     // empty
    }

    @Test
    fun `containment calculation with multiset minimum`() {
        val filter = SelfEchoFilter(now = { 0L })
        // The spoken multiset has two "zamknij" tokens and one "okno" token.
        filter.noteSpoken("zamknij okno zamknij")
        // The transcript has three "zamknij" tokens, so the multiset intersection is two.
        // containment = 2 / 3 = 0.667 < 0.75, NOT echo
        assertFalse(filter.isEcho("zamknij zamknij zamknij"))
    }

    @Test
    fun `containment just meets threshold`() {
        val filter = SelfEchoFilter(now = { 0L })
        // The transcript uses three of the spoken phrase's five distinct tokens.
        // The spoken phrase has five distinct tokens.
        filter.noteSpoken("rano potem teraz zaraz dzisiaj")
        // The transcript has the first three, all present in the spoken phrase.
        // containment = 3/3 = 1.0 >= 0.75 ✓
        assertTrue(filter.isEcho("rano potem teraz"))
    }

    @Test
    fun `containment below threshold is not echo`() {
        val filter = SelfEchoFilter(now = { 0L })
        // The spoken phrase has five distinct tokens.
        filter.noteSpoken("rano potem teraz zaraz dzisiaj")
        // The transcript has the first two, both present in the spoken phrase.
        // containment = 2/2 = 1.0 >= 0.75, should be echo with 2+ words
        // The final transcript has two tokens but only one occurs in spoken text.
        // containment = 1/2 = 0.5 < 0.75
        assertFalse(filter.isEcho("rano okno"))
    }
}
