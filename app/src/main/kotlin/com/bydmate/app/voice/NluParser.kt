package com.bydmate.app.voice

import android.util.Log

sealed interface ParseResult {
    /** One utterance can run several dispatchable commands (both seats, or every window except one),
     *  one, "A i B"). Single-command callers keep using [command]. */
    data class Command(val commands: List<String>) : ParseResult {
        constructor(command: String) : this(listOf(command))
        val command: String? get() = commands.singleOrNull()
    }
    /** A step of the AC setpoint: signed whole degrees (+2 raise, -2 lower), never zero. */
    data class RelativeTemp(val delta: Int) : ParseResult
    data class Volume(val payload: String) : ParseResult
    /** A question answered from the car's readings; nothing is dispatched. */
    data class Ask(val question: VehicleQuestion) : ParseResult
    /** Yandex Music's personal mix, started like the agent's play_music with no query. */
    data object NavigateHome : ParseResult
    /** The saved Work place in the selected navigation app, the twin of [NavigateHome]. */
    data object NavigateWork : ParseResult
    /** A local Polish destination query, retained exactly for the selected navigation app. */
    data class NavigateDestination(val destination: String) : ParseResult
    data object Music : ParseResult
    data object Unrecognized : ParseResult
}

/** Why a phrase that looks like a request to the car was refused. Stable codes: journal, dump and
 *  logcat carry them. A refused request is never handed to the assistant, with one exception: [UNSUPPORTED]
 *  goes to it when the Polish profile's AI opt-in is on (its proposals are validated and confirmed first). */
object NluRefusal {
    const val NEGATED = "command_negated"
    const val QUOTED = "command_quoted"
    /** Two dictionary phrases disagree about it. */
    const val AMBIGUOUS = "command_ambiguous"
    /** Part of an "A and B" is a command and part is not (or is not allowed in a compound). */
    const val PARTIAL = "command_partial"
    /** Command words, but no phrase of the dictionary. The only refusal the AI opt-in may send to the assistant. */
    const val UNSUPPORTED = "command_unsupported"
    /** A supported local-navigation prefix ended after its required preposition. */
    const val NAV_INCOMPLETE = "command_nav_incomplete"
}

/** What the dictionary made of one utterance. */
sealed interface NluVerdict {
    /** A phrase of the dictionary (or a compound of them): run [result]. */
    data class Match(val result: ParseResult) : NluVerdict
    /** Nothing in it looks like a request to the car: the assistant may take it when enabled. */
    data object Unknown : NluVerdict
    /** It looks like a request to the car and nothing runs; it must not reach the assistant. */
    data class Refused(val reason: String) : NluVerdict
}

/**
 * Offline voice commands. A phrase runs only when it equals, word for word after normalization, a
 * phrase of the built-in dictionary of its language ([VoiceDictionary]). A compound ("A i B", "A
 * oraz B") runs when every part is a dictionary command of its own; anything else is
 * [ParseResult.Unrecognized]. Russian goes to the agent unchanged. Polish additionally tells a
 * harmless unknown phrase from a rejected request to the car ([classify]), which is refused for good.
 * Deterministic and pure.
 */
object NluParser {

    private const val TAG = "NluParser"

    // A corrupt resource must never surface as internal_error: a failed load is logged once
    // here (by "by lazy") and every phrase after it goes to the agent instead, same as one the
    // dictionary itself does not recognize.
    private val dictionaries: Map<VoiceLanguage, Lazy<VoiceDictionary?>> =
        VoiceLanguage.values().associateWith { language -> lazy { load(language) } }

    private fun load(language: VoiceLanguage): VoiceDictionary? {
        val start = System.currentTimeMillis()
        return runCatching { VoiceDictionary.load(language) }
            .onSuccess {
                val ms = System.currentTimeMillis() - start
                Log.i(TAG, "dictionary loaded: lang=${language.id} templates=${it.templateCount} keys=${it.keyCount} in $ms ms")
            }
            .onFailure { Log.e(TAG, "dictionary failed to load: lang=${language.id}", it) }
            .getOrNull()
    }

    private val dictionary: VoiceDictionary? get() = dictionaries.getValue(VoiceLanguage.PL).value

    /** Load the dictionary ahead of the first command so it never waits on the parse, and log a
     *  self-test so a car log settles whether it loaded without waiting for a real command. */
    fun warmUp() {
        val dict = dictionary
        if (dict == null) {
            Log.w(TAG, "self-test: dictionary unavailable")
            return
        }
        val result = parse("otwórz szyberdach", dict)
        val summary = (result as? ParseResult.Command)?.commands?.joinToString(",") ?: result::class.simpleName
        Log.i(TAG, "self-test: otwórz szyberdach -> $summary")
    }

    /** Same as [warmUp] for the Polish dictionary. Returns false when its resource could not
     * load, so a profile switch can leave the prior language active. Startup callers may keep
     * using this as best-effort preload. */
    fun warmUp(language: VoiceLanguage): Boolean {
        if (language == VoiceLanguage.PL) {
            warmUp()
            return dictionary != null
        }
        return false
    }

    fun parse(text: String): ParseResult = parse(text, dictionary)

    /** [text] as a command of [language]'s dictionary, or [ParseResult.Unrecognized]. A refused
     *  request is Unrecognized too; use [classify] to tell the two apart. */
    fun parse(text: String, language: VoiceLanguage): ParseResult =
        (classify(text, language) as? NluVerdict.Match)?.result ?: ParseResult.Unrecognized

    /** [dict] is the loaded dictionary or null, exposed here so a failed load can be exercised
     *  without pointing [NluParser] at a broken resource. */
    internal fun parse(text: String, dict: VoiceDictionary?): ParseResult =
        (classify(text, dict, dict?.rules ?: PolishVoiceTextRules) as? NluVerdict.Match)?.result
            ?: ParseResult.Unrecognized

    /** What [language]'s dictionary makes of [text]: a command, a harmless unknown phrase, or a
     *  refused request to the car. Russian never refuses: its unmatched phrases are Unknown. */
    fun classify(text: String, language: VoiceLanguage = VoiceLanguage.DEFAULT): NluVerdict =
        classify(text, dictionaries.getValue(language).value, VoiceTextRules.forLanguage(language))

    /** [dict] null is a failed load: Polish still refuses what looks like a command, so a broken
     *  resource can never send a car request to the assistant. */
    internal fun classify(text: String, dict: VoiceDictionary?, rules: VoiceTextRules): NluVerdict {
        if (dict == null) {
            // Logged per call on purpose: the load itself may have happened before a log
            // recording started, and this line is what tells a load failure from a miss.
            Log.w(TAG, "dictionary unavailable, phrase goes to the agent")
            return refusalOrUnknown(rules.commandLikeRefusal(text))
        }
        // Quotation marks are gone after normalization: look at the raw text first.
        if (rules.isQuoted(text)) return refusalOrUnknown(rules.commandLikeRefusal(text))
        val words = dict.rules.words(text)
        if (words.isEmpty()) return NluVerdict.Unknown
        // Reserved tokens cannot occur in a validated template, and compound splitting does not
        // consume them. Matching therefore cannot succeed; keep the existing refusal precedence
        // without walking candidates. Glued negation is not reserved and keeps exact matching.
        reservedRefusal(text, words, dict, rules)?.let { return it }
        val whole = dict.candidates(words)
        whole.singleOrNull()?.let { return NluVerdict.Match(it) }
        val compound = compound(words, dict)
        compound.result?.let { return NluVerdict.Match(it) }
        val refusal = if (rules.refusesRejectedCommands) rules.commandLikeRefusal(text) else null
        // Glued negations are not reserved tokens. Honor safety refusals before accepting a raw
        // destination, while generic unsupported and ambiguous requests keep their fallback order.
        if (refusal == NluRefusal.NEGATED || refusal == NluRefusal.QUOTED) {
            return NluVerdict.Refused(refusal)
        }
        recognizePolishDestination(text, rules, compound)?.let { return it }
        Log.i(TAG, "no match: words=${words.joinToString(" ").take(80)}")
        if (!rules.refusesRejectedCommands) return NluVerdict.Unknown
        val base = refusal ?: NluRefusal.UNSUPPORTED.takeIf { embedsAction(words, dict) }
        return rejectedMatch(base, whole.size > 1 || compound.ambiguous, compound.partial)
    }

    private fun rejectedMatch(base: String?, ambiguous: Boolean, partial: Boolean): NluVerdict {
        val reason = when {
            base == NluRefusal.QUOTED || base == NluRefusal.NEGATED -> base
            ambiguous -> NluRefusal.AMBIGUOUS
            partial -> NluRefusal.PARTIAL
            else -> base
        }
        return refusalOrUnknown(reason)
    }

    private fun reservedRefusal(
        text: String,
        words: List<String>,
        dict: VoiceDictionary,
        rules: VoiceTextRules,
    ): NluVerdict.Refused? {
        if (rules.refusesRejectedCommands && words.any { it in dict.rules.reservedWords }) {
            val reason = rules.commandLikeRefusal(text)
            if (reason == NluRefusal.NEGATED || reason == NluRefusal.QUOTED) {
                return NluVerdict.Refused(reason)
            }
        }
        return null
    }

    private fun refusalOrUnknown(reason: String?): NluVerdict =
        if (reason == null) NluVerdict.Unknown else NluVerdict.Refused(reason)

    /** The local destination form stays outside the dictionary so the navigation app can search
     * the untouched suffix. Dictionary and compound handling deliberately happen before this. */
    private fun recognizePolishDestination(
        text: String,
        rules: VoiceTextRules,
        compound: Compound,
    ): NluVerdict? {
        if (rules.language != VoiceLanguage.PL || compound.hasConnector) return null
        val match = POLISH_DESTINATION.matchEntire(text.trim()) ?: return null
        val destination = match.groups[1]?.value?.trim().orEmpty()
        // Home and Work are closed dictionary destinations. Their exact forms have already
        // returned above; a tail must keep the existing refusal path instead of becoming a search.
        if (destination.lowercase() in setOf("domu", "pracy") ||
            destination.lowercase().startsWith("domu ") || destination.lowercase().startsWith("pracy ")
        ) return null
        return if (destination.isEmpty()) {
            NluVerdict.Refused(NluRefusal.NAV_INCOMPLETE)
        } else {
            NluVerdict.Match(ParseResult.NavigateDestination(destination))
        }
    }

    /** A run of at least two words inside [words] that is a complete action phrase of the dictionary:
     *  a familiar command with something else around it. It is refused, not extracted. Questions
     *  and the "it is cold" statements do not count: those stay harmless without a verb. */
    private fun embedsAction(words: List<String>, dict: VoiceDictionary): Boolean {
        for (from in words.indices) for (to in from + 2..words.size) {
            val action = dict.candidates(words.subList(from, to)).singleOrNull() ?: continue
            if (action !is ParseResult.Ask && action !is ParseResult.RelativeTemp) return true
        }
        return false
    }

    private class Compound(
        val result: ParseResult?,
        val ambiguous: Boolean,
        val partial: Boolean,
        val hasConnector: Boolean,
    )

    /** Every part between the connectors as its commands, or no result when some part is not a
     *  command of its own (a step, the volume, a question and a verbless phrase never are).
     *  [Compound.partial]: at least one part is a dictionary phrase; [Compound.ambiguous]: some
     *  part has two answers. */
    private fun compound(words: List<String>, dictionary: VoiceDictionary): Compound {
        val parts = dictionary.rules.splitCompound(words)
        if (parts.size < 2) return Compound(null, ambiguous = false, partial = false, hasConnector = false)
        val found = parts.map { dictionary.candidates(it, inCompound = true) }
        val commands = found.map { c -> (c.singleOrNull() as? ParseResult.Command)?.commands }
        val ok = commands.all { it != null }
        return Compound(
            result = if (ok) ParseResult.Command(commands.filterNotNull().flatten()) else null,
            ambiguous = found.any { it.size > 1 },
            partial = found.any { it.isNotEmpty() },
            hasConnector = true,
        )
    }

    private val POLISH_DESTINATION = Regex(
        "(?:nawiguj|prowadź|prowadz|jedź|jedz|jedźmy|jedzmy|(?:włącz|wlacz|uruchom)\\s+nawigacj(?:ę|e))\\s+(?:do|na)(?:\\s+(.*))?",
        RegexOption.IGNORE_CASE,
    )
}
