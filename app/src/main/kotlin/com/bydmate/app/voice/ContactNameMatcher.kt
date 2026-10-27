package com.bydmate.app.voice

import java.text.Normalizer

/** A phone-book entry as the voice call sees it. [phone] never goes to the journal, the overlay or TTS. */
data class VoiceContact(val name: String, val phone: String) {
    override fun toString(): String = "VoiceContact(name=$name)"
}

/** Which contact a spoken name means, and whether the driver must confirm it first. */
sealed interface ContactMatch {
    /** Close enough and clearly closer than every other contact: called without asking. */
    data class Direct(val contact: VoiceContact) : ContactMatch
    /** The best plausible contact (the first in phone-book order on a tie), confirmed before the call. */
    data class Confirm(val contact: VoiceContact) : ContactMatch
    data object NotFound : ContactMatch
}

/**
 * Matches a spoken name against the phone book. Parakeet misspells names ("Yola Gil" for "Jola
 * Gil"), so the distance is Levenshtein over normalized text (case, diacritics and punctuation do
 * not count). A contact's distance is the smallest over:
 * - the spoken name as heard and with Polish case endings turned back to the nominative
 *   ("do Marka" → "Marek", "do Joli" → "Jola", "Kowalskiego" → "Kowalski");
 * - the contact's words in order and reversed ("Gil Jola"), and, when the contact has more words
 *   than the spoken name, every same-length subset of them ("Anna Nowak" → "Anna Maria Nowak").
 *
 * The call goes out without confirmation only when the best distance is at most [DIRECT_MAX]
 * (fewer for short names, where one letter is already another name: "Ela" vs "Ola") and the next
 * contact is clearly farther. Pure.
 */
object ContactNameMatcher {

    /** At most this many edits calls without confirmation. */
    const val DIRECT_MAX = 2

    fun match(query: String, contacts: List<VoiceContact>): ContactMatch {
        val spoken = normalize(query)
        if (spoken.isEmpty()) return ContactMatch.NotFound
        val variants = nominativeVariants(spoken)
        val ranked = contacts
            .map { it to distanceTo(variants, normalize(it.name)) }
            .filter { (_, d) -> d <= plausibleMax(spoken) }
            .sortedBy { (_, d) -> d } // stable: phone-book order breaks a tie
        val (best, bestDistance) = ranked.firstOrNull() ?: return ContactMatch.NotFound
        val runnerUp = ranked.getOrNull(1)?.second ?: Int.MAX_VALUE
        val margin = if (bestDistance == 0) 1 else 2
        val direct = bestDistance <= directMax(spoken) && runnerUp - bestDistance >= margin
        return if (direct) ContactMatch.Direct(best) else ContactMatch.Confirm(best)
    }

    /** One edit per four letters, never more than [DIRECT_MAX]: "Yola Gil" may be two off, "Ela" none. */
    private fun directMax(spoken: String): Int = minOf(DIRECT_MAX, spoken.count { it != ' ' } / 4)

    /** Beyond this the best contact is a guess, not a mishearing: nothing is offered. */
    private fun plausibleMax(spoken: String): Int = maxOf(DIRECT_MAX, spoken.length / 2)

    private fun distanceTo(variants: List<String>, name: String): Int {
        if (name.isEmpty()) return Int.MAX_VALUE
        val forms = nameForms(name, variants.first().split(' ').size)
        return variants.minOf { v -> forms.minOf { f -> distance(v, f) } }
    }

    /** The contact's name as it may be spoken with [wordCount] words. */
    private fun nameForms(name: String, wordCount: Int): List<String> {
        val words = name.split(' ')
        val orders = mutableListOf(words)
        if (wordCount < words.size && words.size <= MAX_NAME_WORDS) orders += subsets(words, wordCount)
        return orders.flatMap { listOf(it.joinToString(" "), it.reversed().joinToString(" ")) }.distinct()
    }

    private fun subsets(words: List<String>, size: Int): List<List<String>> {
        if (size == 0) return listOf(emptyList())
        if (words.size < size) return emptyList()
        val head = words.first()
        val tail = words.drop(1)
        return subsets(tail, size - 1).map { listOf(head) + it } + subsets(tail, size)
    }

    /** [spoken] as heard, then every combination of its words turned back to a nominative guess. */
    internal fun nominativeVariants(spoken: String): List<String> {
        val words = spoken.split(' ')
        if (words.size > MAX_NAME_WORDS) return listOf(spoken)
        var combos = listOf(emptyList<String>())
        for (word in words) {
            val forms = listOf(word) + CASE_ENDINGS.mapNotNull { (ending, nominative) ->
                if (word.length > ending.length + 1 && word.endsWith(ending)) word.dropLast(ending.length) + nominative else null
            }
            combos = combos.flatMap { prefix -> forms.distinct().map { prefix + it } }
        }
        return combos.map { it.joinToString(" ") }.distinct()
    }

    /** Lowercase, Polish letters without diacritics, letters and digits only, single spaces. */
    internal fun normalize(text: String): String {
        val lower = text.lowercase().replace('ł', 'l')
        val stripped = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
        return stripped.replace(NON_ALNUM, " ").trim()
    }

    /** Levenshtein distance: single-letter insertions, deletions and substitutions. */
    internal fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            previous = current
        }
        return previous[b.length]
    }

    private const val MAX_NAME_WORDS = 4

    /**
     * Polish case endings after "do" / "z" / "dla" and their nominative (normalized, so "ą" is "a"):
     * Marka→Marek, Tomka→Tomek, Joli→Jola, Kasi→Kasia, mamy→mama, mamie→mama, Janem→Jan,
     * Janowi→Jan, Kowalskiego→Kowalski, Kowalskiej→Kowalska, Nowakiem→Nowak. A bare one-letter
     * ending ("Jana"→"Jan") is left to the edit distance: dropping it for free would also make
     * "Jolanta" two letters from "Jola".
     */
    private val CASE_ENDINGS = listOf(
        "ka" to "ek", "ca" to "ec", "i" to "a", "i" to "ia", "y" to "a", "ie" to "a",
        "em" to "", "iem" to "", "owi" to "", "iego" to "i", "ego" to "y", "iej" to "a", "ej" to "a",
    )

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
}
