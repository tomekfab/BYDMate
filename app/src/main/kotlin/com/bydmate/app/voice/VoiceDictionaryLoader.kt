package com.bydmate.app.voice

import java.util.IdentityHashMap

/** One element of template syntax. */
internal sealed interface Node {
    data class Word(val text: String, val number: Int?) : Node
    data class Rule(val name: String) : Node
    data class Slot(val name: String) : Node
    data class Choice(val options: List<List<Node>>, val optional: Boolean) : Node
}

/** One way a `list` or `range` slot is filled: its phrases and number ranges, and the value they
 *  give the slot (null for a `range`, whose value is the number said). */
internal class SlotGroup(val value: String?, val phrases: List<List<Node>>, val ranges: List<IntRange>)

/** A partly expanded phrase: its words ("#" for a number), the numbers, the slot values chosen. */
private data class Part(val words: List<String>, val nums: List<Num>, val values: Map<String, String>) {
    operator fun plus(o: Part): Part {
        val twice = values.keys.intersect(o.values.keys)
        require(twice.isEmpty()) { "slot {${twice.first()}} used twice in one phrase" }
        return Part(words + o.words, nums + o.nums, values + o.values)
    }

    companion object {
        val EMPTY = Part(emptyList(), emptyList(), emptyMap())
    }
}

private val NAME = Regex("[a-z0-9_]+")

internal class TemplateLine(val options: List<List<Node>>, val def: DictTemplate)

/** A template is matched directly against the submitted words. It never expands alternatives. */
internal class CompiledTemplate(
    val template: DictTemplate,
    private val options: List<List<Node>>,
    private val rules: Map<String, List<List<Node>>>,
    private val slots: Map<String, List<SlotGroup>>,
) {
    internal data class State(val at: Int, val values: Map<String, String>)

    /** Per utterance only: shared rules/slots do not re-walk the same submitted position. */
    internal class MatchContext(val words: List<String>, val rules: VoiceTextRules) {
        val numbers = words.map(rules::number)
        val keys = words.mapIndexed { i, word -> if (numbers[i] != null) VoiceDictionary.NUM else word }
        private val memo = IdentityHashMap<Any, MutableMap<Int, List<State>>>()

        fun cached(key: Any, at: Int, compute: () -> List<State>): List<State> =
            memo.getOrPut(key) { HashMap() }.getOrPut(at, compute)
    }
    private data class OptionDispatch(
        val byFirstWord: Map<String, List<List<Node>>>,
        val nullable: List<List<Node>>,
    )
    private data class SlotDispatch(val byFirstWord: Map<String, List<SlotGroup>>)

    private val dispatches = IdentityHashMap<List<List<Node>>, OptionDispatch>()
    private val slotDispatches = IdentityHashMap<List<SlotGroup>, SlotDispatch>()
    private val starts = TemplateFirstWords(rules, slots)
    val firstWords: Set<String> = starts.startsOptions(options).first

    private val vocabulary = TemplateVocabulary(rules, slots).collect(options)

    fun match(context: MatchContext): List<ParseResult> {
        // A necessary condition only: syntax matching below still decides every exact result.
        if (context.keys.any { it !in vocabulary }) return emptyList()
        return options(options, 0, context).asSequence()
            .filter { it.at == context.words.size }
            .map { template.output.resolve(it.values) }
            .distinct()
            .toList()
    }

    private fun options(options: List<List<Node>>, at: Int, context: MatchContext): List<State> =
        context.cached(options, at) {
            alternatives(options, at, context).flatMap {
                sequence(it, listOf(State(at, emptyMap())), context)
            }
        }

    private fun sequence(nodes: List<Node>, start: List<State>, context: MatchContext): List<State> {
        var states = start
        for (node in nodes) {
            if (states.isEmpty()) break
            states = states.flatMap { node(node, it, context) }
        }
        return states
    }

    private fun merge(state: State, matched: List<State>): List<State> = matched.mapNotNull {
        if (state.values.keys.any(it.values::containsKey)) null
        else State(it.at, if (state.values.isEmpty()) it.values else state.values + it.values)
    }

    private fun node(node: Node, state: State, context: MatchContext): List<State> = when (node) {
        is Node.Word -> {
            val actual = context.words.getOrNull(state.at)
            val matches = when {
                actual == null -> false
                node.number == null -> actual == node.text
                else -> context.numbers[state.at] == node.number
            }
            if (!matches) emptyList()
            else listOf(state.copy(at = state.at + 1))
        }
        is Node.Rule -> merge(state, options(requireNotNull(rules[node.name]), state.at, context))
        is Node.Slot -> {
            val all = requireNotNull(slots[node.name])
            val matched = context.cached(all, state.at) {
                groups(all, state.at, context).flatMap { group ->
                    val number = context.numbers.getOrNull(state.at)
                    val ranged = group.ranges.mapNotNull { range ->
                        if (number !in range) null else State(
                            state.at + 1, mapOf(node.name to (group.value ?: number.toString())),
                        )
                    }
                    val phrased = options(group.phrases, state.at, context).mapNotNull {
                        withValue(it, node.name, group.value)
                    }
                    ranged + phrased
                }
            }
            merge(state, matched)
        }
        is Node.Choice -> merge(state, options(node.options, state.at, context)) +
            if (node.optional) listOf(state) else emptyList()
    }

    private fun alternatives(
        options: List<List<Node>>,
        at: Int,
        context: MatchContext,
    ): List<List<Node>> {
        val dispatch = dispatches.getOrPut(options) {
            val byFirstWord = HashMap<String, MutableList<List<Node>>>()
            val nullable = ArrayList<List<Node>>()
            options.forEach { option ->
                val (first, canBeEmpty) = starts.startsNodes(option)
                first.forEach { word -> byFirstWord.getOrPut(word) { ArrayList() }.add(option) }
                if (canBeEmpty) nullable += option
            }
            OptionDispatch(byFirstWord, nullable)
        }
        val word = context.words.getOrNull(at)?.let { if (context.numbers[at] != null) VoiceDictionary.NUM else it }
        return if (word == null) dispatch.nullable else dispatch.byFirstWord[word].orEmpty() + dispatch.nullable
    }

    private fun groups(
        groups: List<SlotGroup>,
        at: Int,
        context: MatchContext,
    ): List<SlotGroup> {
        val dispatch = slotDispatches.getOrPut(groups) {
            val byFirstWord = HashMap<String, MutableList<SlotGroup>>()
            groups.forEach { group ->
                val (first, _) = starts.startsOptions(group.phrases)
                first.forEach { word -> byFirstWord.getOrPut(word) { ArrayList() }.add(group) }
                if (group.ranges.isNotEmpty()) {
                    byFirstWord.getOrPut(VoiceDictionary.NUM) { ArrayList() }.add(group)
                }
            }
            SlotDispatch(byFirstWord)
        }
        val word = context.words.getOrNull(at)?.let { if (context.numbers[at] != null) VoiceDictionary.NUM else it }
        return word?.let { dispatch.byFirstWord[it] }.orEmpty()
    }

    private fun withValue(state: State, name: String, value: String?): State? {
        requireNotNull(value)
        return if (name in state.values) null else state.copy(values = state.values + (name to value))
    }

    /** First literal words only. This is a bounded dispatch index, not phrase expansion. */

}

/** Reads the dictionary text and compiles it without expanding phrase combinations. */
internal class VoiceDictionaryLoader(text: String, private val textRules: VoiceTextRules = PolishVoiceTextRules) {

    private val rules = HashMap<String, List<List<Node>>>()
    private val slots = HashMap<String, List<SlotGroup>>()
    private val templates = ArrayList<TemplateLine>()
    // Cache keys are namespaced ("rule:" / "slot:") so a <name> reference never serves a {name}
    // reference from the same cache entry or the reverse: a name defined as one but referenced
    // as the other must still fail with "unknown rule"/"unknown slot", whichever expands first.
    private val expanded = HashMap<String, List<Part>>()
    private val expanding = HashSet<String>()

    init {
        logicalLines(text).forEach { (line, body) -> readLine(body, "line $line", line) }
    }

    fun build(beforeAudit: () -> Unit = {}): VoiceDictionary {
        validate()
        return VoiceDictionary(
            templates.map { CompiledTemplate(it.def, it.options, rules, slots) },
            { beforeAudit(); audit() },
            templates.size,
            textRules,
        )
    }

    /** Explicit JVM-test audit traversal. Runtime matching never calls this. */
    private fun audit(): Map<String, List<DictPhrase>> {
        val byKey = HashMap<String, MutableList<DictPhrase>>()
        for (t in templates) {
            val where = "line ${t.def.line}"
            for (part in t.options.flatMap { expand(it) }) {
                require(part.words.isNotEmpty()) { "$where: a phrase with no words" }
                val phrase = DictPhrase(part.nums, part.values, t.def)
                // Every {slot} of the command must be filled by every phrase of the template.
                val sample = runCatching { phrase.resolve(part.nums.map { it.range.first }) }
                require(sample.isSuccess) { "$where: ${sample.exceptionOrNull()?.message}" }
                byKey.getOrPut(part.words.joinToString(" ")) { ArrayList() }.add(phrase)
            }
        }
        return byKey
    }

    private fun validate() {
        val usedRules = HashSet<String>()
        val usedSlots = HashSet<String>()
        fun walk(nodes: List<Node>, expanding: MutableSet<String>) {
            nodes.forEach { node -> when (node) {
                is Node.Word -> Unit
                is Node.Choice -> node.options.forEach { walk(it, expanding) }
                is Node.Rule -> {
                    val key = "rule:${node.name}"
                    require(expanding.add(key)) { "\"${node.name}\" refers to itself" }
                    usedRules += node.name
                    requireNotNull(rules[node.name]) { "unknown rule <${node.name}>" }
                        .forEach { walk(it, expanding) }
                    expanding.remove(key)
                }
                is Node.Slot -> {
                    val key = "slot:${node.name}"
                    require(expanding.add(key)) { "\"${node.name}\" refers to itself" }
                    usedSlots += node.name
                    val phrases = requireNotNull(slots[node.name]) { "unknown slot {${node.name}}" }
                        .flatMap { it.phrases }
                    phrases.forEach { walk(it, expanding) }
                    expanding.remove(key)
                }
            } }
        }
        templates.forEach { walk(it.options.flatten(), HashSet()) }
        val unused = rules.keys - usedRules + (slots.keys - usedSlots)
        require(unused.isEmpty()) { "defined but never used: $unused" }
    }

    private fun readLine(body: String, where: String, line: Int) {
        val head = body.substringBefore(' ')
        val definition = head in setOf("rule", "list", "range")
        if (!definition) {
            val solo = head == "solo"
            val template = if (solo) body.substringAfter(' ') else body
            val arrow = template.indexOf("=>")
            require(arrow > 0) { "$where: no \"=>\" in \"$body\"" }
            val output = CommandExpr.parse(template.substring(arrow + 2).trim())
            val options = SyntaxReader(template.substring(0, arrow), where, textRules).read()
            templates.add(TemplateLine(options, DictTemplate(line, solo, output)))
            return
        }
        val name = body.substringAfter(' ').substringBefore(':').trim()
        require(NAME.matches(name) && ':' in body) { "$where: bad definition \"$body\"" }
        require(name !in rules && name !in slots) { "$where: \"$name\" defined twice" }
        val rest = body.substringAfter(':').trim()
        when (head) {
            "rule" -> rules[name] = SyntaxReader(rest, where, textRules).read()
            "range" -> slots[name] = listOf(SlotGroup(null, emptyList(), listOf(range(rest, where))))
            else -> slots[name] = splitTop(rest, ';').map { readGroup(it, where) }
        }
    }

    private fun readGroup(group: String, where: String): SlotGroup {
        val arrow = group.indexOf("=>")
        require(arrow > 0) { "$where: no \"=>\" in list group \"$group\"" }
        val value = group.substring(arrow + 2).trim()
        require(value.isNotEmpty() && value.none(Char::isWhitespace)) { "$where: bad list value \"$value\"" }
        val forms = splitTop(group.substring(0, arrow), '|').map { it.trim() }
        val (ranges, phrases) = forms.partition { RANGE.matches(it) }
        return SlotGroup(value, phrases.flatMap { SyntaxReader(it, where, textRules).read() }, ranges.map { range(it, where) })
    }

    private fun expand(seq: List<Node>): List<Part> =
        seq.fold(listOf(Part.EMPTY)) { acc, node -> expandNode(node).let { next -> acc.flatMap { a -> next.map { a + it } } } }

    private fun expandNode(node: Node): List<Part> = when (node) {
        is Node.Word -> listOf(word(node.text))
        is Node.Rule -> named("rule", node.name) {
            requireNotNull(rules[node.name]) { "unknown rule <${node.name}>" }.flatMap { expand(it) }
        }
        is Node.Slot -> named("slot", node.name) { slotParts(node.name) }
        is Node.Choice -> node.options.flatMap { expand(it) } + if (node.optional) listOf(Part.EMPTY) else emptyList()
    }

    private fun word(text: String): Part {
        val n = textRules.number(text) ?: return Part(listOf(text), emptyList(), emptyMap())
        return Part(listOf(VoiceDictionary.NUM), listOf(Num(n..n, null)), emptyMap())
    }

    /** The expansion of a rule or slot, computed once; a definition using itself is an error.
     *  [kind] ("rule" or "slot") namespaces the cache so <name> and {name} never share a cache
     *  entry when the file uses [name] as only one of the two. */
    private fun named(kind: String, name: String, compute: () -> List<Part>): List<Part> {
        val key = "$kind:$name"
        expanded[key]?.let { return it }
        require(expanding.add(key)) { "\"$name\" refers to itself" }
        return compute().also { expanded[key] = it; expanding.remove(key) }
    }

    private fun slotParts(name: String): List<Part> =
        requireNotNull(slots[name]) { "unknown slot {$name}" }.flatMap { g ->
            val values = g.value?.let { mapOf(name to it) }.orEmpty()
            val numSlot = if (g.value == null) name else null
            g.ranges.map { Part(listOf(VoiceDictionary.NUM), listOf(Num(it, numSlot)), values) } +
                g.phrases.flatMap { expand(it) }.map { it + Part(emptyList(), emptyList(), values) }
        }

    private companion object {
        val RANGE = Regex("(\\d+)\\.\\.(\\d+)")

        fun range(text: String, where: String): IntRange {
            val m = requireNotNull(RANGE.matchEntire(text.trim())) { "$where: bad range \"$text\"" }
            val lo = m.groupValues[1].toInt()
            val hi = m.groupValues[2].toInt()
            require(lo <= hi) { "$where: reversed range \"$text\"" }
            return lo..hi
        }

        /** [text] split at [sep] outside (), [] and {}; "=>" and <rule> never hold a separator. */
        fun splitTop(text: String, sep: Char): List<String> {
            val out = ArrayList<String>()
            var depth = 0
            var start = 0
            text.forEachIndexed { i, c ->
                when (c) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    sep -> if (depth == 0) { out.add(text.substring(start, i)); start = i + 1 }
                }
            }
            out.add(text.substring(start))
            return out
        }

        /** Lines without comments; a line starting with whitespace continues the previous one. */
        fun logicalLines(text: String): List<Pair<Int, String>> {
            val out = ArrayList<Pair<Int, String>>()
            text.lines().forEachIndexed { i, raw ->
                val line = raw.substringBefore('#')
                when {
                    line.isBlank() -> Unit
                    line.first().isWhitespace() && out.isNotEmpty() ->
                        out[out.lastIndex] = out.last().let { it.first to it.second + " " + line.trim() }
                    else -> out.add(i + 1 to line.trim())
                }
            }
            return out
        }
    }
}

/** Reads template syntax: words, <rule>, {slot}, [optional part], (a|b) choice, top-level a|b. */
private class SyntaxReader(private val text: String, private val where: String, private val rules: VoiceTextRules) {
    private var i = 0

    fun read(): List<List<Node>> = alternatives(null)

    private fun alternatives(close: Char?): List<List<Node>> {
        val options = ArrayList<List<Node>>()
        var seq = ArrayList<Node>()
        val words = StringBuilder()
        while (true) {
            val c = text.getOrNull(i)
            // A bare "}" or ">" here (one <rule>/{slot} already consumes its own closer via
            // name()) is as unbalanced as a stray ")" or "]": every one of the four must fail
            // to load, never get silently stripped by punctuation normalization later.
            if (c == null || c in "|)]}>") {
                seq.addAll(literal(words))
                words.clear()
                require(seq.isNotEmpty()) { "$where: empty choice in \"$text\"" }
                options.add(seq)
                i++
                if (c != '|') {
                    require(c == close) { "$where: unbalanced brackets in \"$text\"" }
                    return options
                }
                seq = ArrayList()
            } else if (c in "([<{") {
                seq.addAll(literal(words))
                words.clear()
                i++
                seq.add(element(c))
            } else {
                words.append(c)
                i++
            }
        }
    }

    private fun element(open: Char): Node = when (open) {
        '(' -> Node.Choice(alternatives(')'), optional = false)
        '[' -> Node.Choice(alternatives(']'), optional = true)
        '<' -> Node.Rule(name('>'))
        else -> Node.Slot(name('}'))
    }

    private fun name(close: Char): String {
        val end = text.indexOf(close, i)
        require(end > i) { "$where: unclosed name in \"$text\"" }
        val name = text.substring(i, end)
        require(NAME.matches(name)) { "$where: bad name \"$name\"" }
        i = end + 1
        return name
    }

    /** Template words normalized like the utterance; a filler could never match, so it is an error. */
    private fun literal(words: CharSequence): List<Node> {
        val tokens = rules.tokens(words.toString())
        require(tokens.none { it in rules.fillers }) { "$where: filler word in \"$words\"" }
        require(tokens.none { it in rules.reservedWords }) { "$where: negation or quote word in \"$words\"" }
        return rules.digits(tokens).map { Node.Word(it, rules.number(it)) }
    }
}

private class TemplateFirstWords(
    private val rules: Map<String, List<List<Node>>>,
    private val slots: Map<String, List<SlotGroup>>,
) {
    fun startsOptions(options: List<List<Node>>): Pair<Set<String>, Boolean> = options
        .map { startsNodes(it) }
        .let { it.flatMapTo(HashSet()) { pair -> pair.first } to it.any { pair -> pair.second } }

    fun startsNodes(nodes: List<Node>): Pair<Set<String>, Boolean> {
        val out = HashSet<String>()
        for (node in nodes) {
            val (words, nullable) = when (node) {
                is Node.Word -> setOf(text(node.text)) to false
                is Node.Rule -> startsOptions(requireNotNull(rules[node.name]) { "unknown rule <${node.name}>" })
                is Node.Slot -> startsSlot(requireNotNull(slots[node.name]) { "unknown slot {${node.name}}" })
                is Node.Choice -> startsOptions(node.options).let { it.first to (node.optional || it.second) }
            }
            out += words
            if (!nullable) return out to false
        }
        return out to true
    }

    fun startsSlot(groups: List<SlotGroup>): Pair<Set<String>, Boolean> = groups
        .map { group ->
            val phrase = startsOptions(group.phrases)
            (phrase.first + if (group.ranges.isEmpty()) emptySet() else setOf(VoiceDictionary.NUM)) to phrase.second
        }
        .let { it.flatMapTo(HashSet()) { pair -> pair.first } to it.any { pair -> pair.second } }

    fun text(word: String): String = if (word.toIntOrNull() != null) VoiceDictionary.NUM else word
}

private class TemplateVocabulary(
    private val rules: Map<String, List<List<Node>>>,
    private val slots: Map<String, List<SlotGroup>>,
) {
    private val words = HashSet<String>()

    fun collect(options: List<List<Node>>): Set<String> {
        options.forEach(::sequence)
        return words
    }

    private fun sequence(nodes: List<Node>) { nodes.forEach(::node) }

    private fun node(node: Node) {
        when (node) {
            is Node.Word -> words.add(if (node.number == null) node.text else VoiceDictionary.NUM)
            is Node.Rule -> rules.getValue(node.name).forEach(::sequence)
            is Node.Choice -> node.options.forEach(::sequence)
            is Node.Slot -> slots.getValue(node.name).forEach(::group)
        }
    }

    private fun group(group: SlotGroup) {
        if (group.ranges.isNotEmpty()) words.add(VoiceDictionary.NUM)
        group.phrases.forEach(::sequence)
    }
}
