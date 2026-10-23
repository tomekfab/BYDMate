package com.bydmate.app.voice

/**
 * The language-specific text rules the command dictionary is compiled and matched with. One
 * instance serves both sides, so a template word and a spoken word are always normalized the same
 * way: [tokens] splits, [digits] writes numbers as digits, [fillers] are dropped from the spoken
 * phrase and may not appear in a template. Pure.
 */
interface VoiceTextRules {

    val language: VoiceLanguage

    /** Politeness and address words: dropped from the utterance anywhere, never in a template. */
    val fillers: Set<String>

    /** Words that change what a phrase means (negation, quoting) on top of [fillers]. A template may
     *  hold none of them, so no phrase that carries one can ever equal a dictionary phrase. */
    val reservedWords: Set<String> get() = fillers

    fun tokens(text: String): List<String>

    /** [tokens] with every number word written as digits. */
    fun digits(tokens: List<String>): List<String>

    /** The number a digit token stands for, or null for any other word. */
    fun number(token: String): Int?

    /** [tokens] without [fillers], numbers as digits: the words the dictionary matches. */
    fun words(text: String): List<String> = digits(tokens(text).filterNot { it in fillers })

    /** [words] cut at the connectors of "A and B"; one part when there is no connector. */
    fun splitCompound(words: List<String>): List<List<String>>

    /** True when a rejected request that looks like a command is refused for good ([NluVerdict.Refused])
     *  instead of going on to the assistant. Russian keeps sending every unmatched phrase on. */
    val refusesRejectedCommands: Boolean get() = false

    /** True when the raw [text] marks quoted speech that no word-level check can see (quotation
     *  marks are gone after [tokens]). */
    fun isQuoted(text: String): Boolean = false

    /**
     * Why a phrase that matched nothing still looks like a request to the car, or null when it is
     * harmless and may go to the assistant. One of [NluRefusal.NEGATED], [NluRefusal.QUOTED] or
     * [NluRefusal.UNSUPPORTED]. Russian keeps its original behaviour: it never refuses.
     */
    fun commandLikeRefusal(text: String): String? = null

    companion object {
        fun forLanguage(language: VoiceLanguage): VoiceTextRules = PolishVoiceTextRules
    }
}
