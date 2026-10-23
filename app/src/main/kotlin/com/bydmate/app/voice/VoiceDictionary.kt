package com.bydmate.app.voice

/**
 * A built-in command dictionary (Java resource [RESOURCE] for Russian, [PL_RESOURCE] for Polish): a
 * closed list of compiled phrase templates. Runtime matching visits only syntax alternatives for
 * the submitted words and numbers. [phrases] and [conflicts] explicitly expand the grammar for
 * JVM audits; production matching never calls them.
 */
class VoiceDictionary internal constructor(
    private val templates: List<CompiledTemplate>,
    private val audit: () -> Map<String, List<DictPhrase>>,
    /** Template lines in the file. */
    val templateCount: Int,
    /** The text rules the templates were compiled with; input must be normalized with the same. */
    val rules: VoiceTextRules = PolishVoiceTextRules,
) {

    // Index only possible initial tokens, never complete phrases or number cross-products.
    private val byFirstWord = buildMap<String, MutableList<CompiledTemplate>> {
        templates.forEach { template ->
            template.firstWords.forEach { word -> getOrPut(word) { ArrayList() }.add(template) }
        }
    }

    /** Compiled template entries the runtime matcher visits. */
    val keyCount: Int get() = templates.size

    /**
     * What [words] (already normalized by [words]) runs, or null when no phrase equals them or two
     * phrases disagree. [inCompound]: the words are one part of "A i B", where a `solo` template
     * (a phrase without its own verb) never matches.
     */
    fun match(words: List<String>, inCompound: Boolean = false): ParseResult? =
        candidates(words, inCompound).singleOrNull()

    /** Every distinct result [words] has: none for no phrase, one for a match, several when two
     *  phrases disagree (the ambiguity [match] turns into null). */
    internal fun candidates(words: List<String>, inCompound: Boolean = false): List<ParseResult> {
        val first = words.firstOrNull() ?: return emptyList()
        val key = rules.number(first)?.let { NUM } ?: first
        val context = CompiledTemplate.MatchContext(words, rules)
        return byFirstWord[key].orEmpty().asSequence()
            .filter { !inCompound || !it.template.solo }
            .flatMap { it.match(context).asSequence() }
            .distinct()
            .toList()
    }

    /** Every phrase the dictionary accepts with what it runs; a phrase two templates share is
     *  listed once per template. */
    fun phrases(): Sequence<Pair<String, ParseResult>> = audit().asSequence().flatMap { (key, list) ->
        list.asSequence().flatMap { p ->
            p.tuples().asSequence().mapNotNull { t -> p.resolve(t)?.let { spell(key, t) to it } }
        }
    }

    /** Phrases two templates accept with different results, one line each; empty when none. */
    fun conflicts(): List<String> = audit().flatMap { (key, list) ->
        list.flatMapIndexed { i, a -> list.drop(i + 1).mapNotNull { b -> conflict(key, a, b) } }
    }

    private fun conflict(key: String, a: DictPhrase, b: DictPhrase): String? {
        val shared = b.tuples().toSet()
        val t = a.tuples().firstOrNull { it in shared && a.resolve(it) != b.resolve(it) } ?: return null
        return "\"${spell(key, t)}\": line ${a.template.line} => ${a.resolve(t)}, line ${b.template.line} => ${b.resolve(t)}"
    }

    private fun spell(key: String, numbers: List<Int>): String {
        val next = numbers.iterator()
        return key.split(' ').joinToString(" ") { if (it == NUM) next.next().toString() else it }
    }

    companion object {
        const val PL_RESOURCE = "voice/pl_commands.txt"
        internal const val NUM = "#"

        fun resourceOf(): String = PL_RESOURCE

        /** Politeness and address words: dropped from the utterance anywhere, never in a template. */
        val FILLERS: Set<String> = PolishVoiceTextRules.fillers

        /** The dictionary of [language], Russian by default. */
        fun load(language: VoiceLanguage = VoiceLanguage.DEFAULT): VoiceDictionary {
            val resource = resourceOf()
            val stream = requireNotNull(VoiceDictionary::class.java.classLoader?.getResourceAsStream(resource)) {
                "$resource is missing"
            }
            return parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }, VoiceTextRules.forLanguage(language))
        }

        /** Throws [IllegalArgumentException] naming the line on any error in [text]. */
        fun parse(text: String, rules: VoiceTextRules = PolishVoiceTextRules): VoiceDictionary =
            VoiceDictionaryLoader(text, rules).build()

        /** The Polish words used for matching, with fillers removed and numbers as digits. */
        fun words(text: String): List<String> = PolishVoiceTextRules.words(text)
    }
}

/** A number the phrase holds at one place: the values accepted there and the `range` slot it
 *  fills with the number itself, or null when a list or the template fixes the value. */
internal class Num(val range: IntRange, val slot: String?)

internal class DictTemplate(val line: Int, val solo: Boolean, val output: CommandExpr)

/** One expanded phrase: the numbers it accepts, the slot values its words chose, its template. */
internal class DictPhrase(val nums: List<Num>, val values: Map<String, String>, val template: DictTemplate) {

    fun resolve(numbers: List<Int>): ParseResult? {
        if (numbers.size != nums.size || nums.indices.any { numbers[it] !in nums[it].range }) return null
        val ranged = nums.indices.mapNotNull { i -> nums[i].slot?.let { it to numbers[i].toString() } }
        return template.output.resolve(values + ranged)
    }

    /** Every number combination the phrase accepts (one empty list when it holds no number). */
    fun tuples(): List<List<Int>> =
        nums.fold(listOf(emptyList())) { acc, n -> acc.flatMap { t -> n.range.map { t + it } } }
}

/** The command side of a template line. */
internal sealed interface CommandExpr {
    fun resolve(values: Map<String, String>): ParseResult

    /** A setpoint step: [sign] times a fixed [amount], or the number a `range` slot [slot] took. */
    class Temp(private val sign: Int, private val amount: Int?, private val slot: String?) : CommandExpr {
        override fun resolve(values: Map<String, String>): ParseResult {
            val magnitude = amount ?: requireNotNull(values[slot]) { "{$slot} has no value in a TEMP step" }.toInt()
            return ParseResult.RelativeTemp(sign * magnitude)
        }
    }

    class Vol(private val payload: String) : CommandExpr {
        override fun resolve(values: Map<String, String>) = ParseResult.Volume(fill(payload, values).single())
    }

    class Ask(private val question: VehicleQuestion) : CommandExpr {
        override fun resolve(values: Map<String, String>) = ParseResult.Ask(question)
    }

    object NavigateHome : CommandExpr {
        override fun resolve(values: Map<String, String>) = ParseResult.NavigateHome
    }

    object NavigateWork : CommandExpr {
        override fun resolve(values: Map<String, String>) = ParseResult.NavigateWork
    }

    object Music : CommandExpr {
        override fun resolve(values: Map<String, String>) = ParseResult.Music
    }

    class Commands(private val terms: List<String>) : CommandExpr {
        override fun resolve(values: Map<String, String>) = ParseResult.Command(terms.flatMap { fill(it, values) })
    }

    companion object {
        private val SLOT_REF = Regex("\\{([a-z0-9_]+)\\}")
        private val TEMP_STEP = Regex("TEMP ([+-])(?:(\\d+)|\\{([a-z0-9_]+)\\})")

        fun parse(text: String): CommandExpr = when {
            text.startsWith("TEMP ") -> {
                val m = requireNotNull(TEMP_STEP.matchEntire(text)) { "bad temperature step \"$text\"" }
                val amount = m.groupValues[2].toIntOrNull()
                require(amount == null || amount > 0) { "bad temperature step \"$text\"" }
                Temp(if (m.groupValues[1] == "-") -1 else 1, amount, m.groupValues[3].ifEmpty { null })
            }
            text == "MUSIC" -> Music
            text == "NAVIGATE_HOME" -> NavigateHome
            text == "NAVIGATE_WORK" -> NavigateWork
            text.startsWith("VOL ") -> Vol(text.removePrefix("VOL ").trim())
            text.startsWith("ASK ") -> text.removePrefix("ASK ").trim().let { id ->
                Ask(requireNotNull(VehicleQuestion.of(id)) { "unknown question \"$id\" in \"$text\"" })
            }
            else -> Commands(text.split(" + ").map { it.trim() }.onEach {
                require(it.isNotEmpty() && it.none(Char::isWhitespace)) { "bad command \"$it\" in \"$text\"" }
            })
        }

        /** [term] with each {slot} replaced by its value; a value "a,b" repeats the term per symbol. */
        fun fill(term: String, values: Map<String, String>): List<String> {
            val filled = SLOT_REF.findAll(term).map { it.groupValues[1] }.toSet().associateWith { name ->
                requireNotNull(values[name]) { "{$name} has no value in \"$term\"" }.split(',').map { it.trim() }
            }
            val fan = filled.filterValues { it.size > 1 }.keys
            require(fan.size <= 1) { "more than one multi-valued slot in \"$term\"" }
            val base = filled.filterKeys { it !in fan }.entries.fold(term) { t, (n, v) -> t.replace("{$n}", v.single()) }
            val multi = fan.singleOrNull() ?: return listOf(base)
            return filled.getValue(multi).map { base.replace("{$multi}", it) }
        }
    }
}
