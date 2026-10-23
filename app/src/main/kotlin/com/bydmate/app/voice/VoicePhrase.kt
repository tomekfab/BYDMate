package com.bydmate.app.voice

/**
 * Normalizes a spoken phrase for collision detection and matching.
 * Lowercase, strip non-letter/digit, split, drop Polish filler words, join with a single space.
 * No stemming: «otwórz okno» and «otwórz okna» are different phrases, for matching and for
 * collisions alike. Pure and deterministic.
 */
object VoicePhrase {
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd} ]")
    private val WHITESPACE = Regex("\\s+")

    // Negation ("nie") is never a filler: dropping it would turn a
    // refusal into a command.
    internal val FILLERS_PL = setOf("proszę", "prosze", "możesz", "mozesz", "mi", "słuchaj", "sluchaj", "ej")

    /** The key two phrases collide on: equal exactly when [isExact] holds between them. */
    fun normalize(phrase: String): String = words(phrase).joinToString(" ")

    /** The words of [phrase] as spoken, without stemming: lowercase, no punctuation, no
     *  fillers. Matching and collisions are judged on these, so «otwórz okna» is not «otwórz okno». */
    fun words(phrase: String): List<String> {
        val lower = phrase.lowercase()
        // Polish keeps its diacritics ("zażółć" and "zazolc" are different phrases).
        return lower
            .replace(NON_WORD, " ")
            .split(WHITESPACE)
            .filter { it.isNotBlank() && it !in FILLERS_PL }
    }

    /** [phrase] is the whole of [heard], word for word after normalization. */
    fun isExact(heard: String, phrase: String): Boolean {
        val words = words(phrase)
        return words.isNotEmpty() && words == words(heard)
    }
}
