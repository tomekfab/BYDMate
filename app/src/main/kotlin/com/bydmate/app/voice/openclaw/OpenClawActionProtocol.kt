package com.bydmate.app.voice.openclaw

import com.bydmate.app.agent.ActionCandidate
import com.bydmate.app.agent.ActionRegistry
import com.bydmate.app.agent.ActionRejection
import com.bydmate.app.agent.ActionRisk

/** What a final assistant reply holds: plain text, or text plus exactly one `bydmate-action` block. */
sealed interface Extraction {
    data class NoBlock(val text: String) : Extraction
    data class Block(val prose: String, val rawJson: String) : Extraction
    object Malformed : Extraction
}

/** The proposal envelope after strict parsing. Still untrusted: [candidate] goes through the validator. */
data class Envelope(
    val requestId: String,
    val proposalId: String,
    val capabilities: String,
    val type: String,
    val phrase: String?,
    val candidate: ActionCandidate,
)

sealed interface ParsedEnvelope {
    data class Ok(val envelope: Envelope) : ParsedEnvelope
    data class Invalid(val reason: ActionRejection) : ParsedEnvelope
}

enum class ReportStatus(val code: String) {
    DONE("done"), FAILED("failed"), BLOCKED("blocked"), DECLINED("declined"), EXPIRED("expired"),
    CANCELLED("cancelled"), UNCERTAIN("uncertain"), REJECTED("rejected"), SAVED("saved"), DISMISSED("dismissed"),
}

/** Closed list of codes for the report line; never free text. */
enum class ReportReason(val code: String) {
    OK("ok"),
    MALFORMED("malformed"),
    UNSUPPORTED_VERSION("unsupported_version"),
    STALE_CAPABILITIES("stale_capabilities"),
    UNKNOWN_KIND("unknown_kind"),
    UNKNOWN_COMMAND("unknown_command"),
    NOT_EXECUTABLE("not_executable"),
    MISSING_VALUE("missing_value"),
    VALUE_OUT_OF_RANGE("value_out_of_range"),
    UNEXPECTED_FIELD("unexpected_field"),
    EMPTY_DESTINATION("empty_destination"),
    DESTINATION_INVALID("destination_invalid"),
    NOT_CORRELATED("not_correlated"),
    DUPLICATE_TURN("duplicate_turn"),
    EXPIRED("expired"),
    RECOVERED_NOT_EXECUTED("recovered_not_executed"),
    CANCELLED("cancelled"),
    CONFIRM_DECLINED("confirm_declined"),
    CONFIRM_UNAVAILABLE("confirm_unavailable"),
    BLOCKED_BY_GATE("blocked_by_gate"),
    ALIAS_UNAVAILABLE("alias_unavailable"),
    LEDGER_UNAVAILABLE("ledger_unavailable"),
    DISPATCH_FAILED("dispatch_failed"),
    TIMED_OUT("timed_out"),
    UNCERTAIN("uncertain"),
    SAVED("saved"),
    DISMISSED("dismissed"),
}

/**
 * Wire format of assistant action proposals and of the capability manifest, frozen by the issue 4 design.
 * Pure functions only: no sockets, no Android, no logging. The reply is untrusted: this codec checks shape only
 * (strict JSON, closed key sets, bounded ids). It never judges an id, value or destination; that is
 * [com.bydmate.app.agent.ActionValidating]'s job, so there is exactly one validation seam.
 */
object OpenClawActionProtocol {
    const val SCHEMA = "bydmate.action/1"
    const val MANIFEST_SCHEMA = "bydmate.capabilities/1"
    const val MAX_ENVELOPE_BYTES = 2048

    private const val MARKER = "bydmate-action"
    private const val MAX_DEPTH = 3
    private const val MAX_PHRASE_CHARS = 40
    private val REQUEST_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val PROPOSAL_ID = Regex("[A-Za-z0-9_-]{8,64}")
    private val TOP_KEYS = setOf("schema", "requestId", "proposalId", "capabilities", "type", "action", "phrase")
    private val ACTION_KEYS = setOf("kind", "id", "value", "query", "shortcut")
    private const val TYPE_ACTION = "action"
    private const val TYPE_ALIAS = "alias_proposal"

    /**
     * Added to every action-capable turn after the plain-text reply instruction. It overrides the "no formatting"
     * rule in one narrow way: a single fenced block, only for a real car or navigation request.
     */
    const val ACTION_INSTRUCTION =
        "Odpowiadaj po polsku zwykłym tekstem bez formatowania, bo odpowiedź zostanie przeczytana na głos. " +
            "Wyjątek: tylko gdy kierowca prosi o czynność w aucie lub o nawigację, dodaj po tekście dokładnie jeden blok " +
            "w osobnych wierszach: ```bydmate-action, potem jedna linia JSON, potem ```. " +
            "Linia JSON: {\"schema\":\"bydmate.action/1\",\"requestId\":\"<znacznik z końca tej wiadomości, bez #>\"," +
            "\"proposalId\":\"<nowy unikalny identyfikator, 8-64 znaków A-Za-z0-9_->\",\"capabilities\":\"<version z listy>\"," +
            "\"type\":\"action\",\"action\":{\"kind\":\"vehicle\",\"id\":\"<id z listy>\",\"value\":<liczba, tylko dla " +
            "poleceń z zakresem>}}. Dla nawigacji action to {\"kind\":\"navigate\",\"query\":\"<adres>\"} albo " +
            "{\"kind\":\"navigate\",\"shortcut\":\"home\"} (także \"work\"). " +
            "Blok nigdy nie jest potrzebny w zwykłej rozmowie ani dla czegoś, czego nie ma na liście."

    /** Splits a final reply into prose and at most one `bydmate-action` block. Other fenced blocks are prose. */
    fun extract(reply: String): Extraction {
        val lines = reply.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val before = mutableListOf<String>()
        val after = mutableListOf<String>()
        var json: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val info = fenceInfo(line)
            val sink = if (json == null) before else after
            if (info == null) {
                sink += line
                i++
                continue
            }
            val close = (i + 1 until lines.size).firstOrNull { lines[it].trim() == "```" }
            if (info == MARKER) {
                if (json != null || close == null) return Extraction.Malformed
                json = lines.subList(i + 1, close).joinToString("\n")
                i = close + 1
            } else {
                val end = close ?: lines.lastIndex
                sink += lines.subList(i, end + 1)
                i = end + 1
            }
        }
        val raw = json ?: return Extraction.NoBlock(reply)
        val prose = listOf(before, after).map { it.joinToString("\n").trim() }.filter { it.isNotEmpty() }
        return Extraction.Block(prose.joinToString("\n"), raw)
    }

    /** Text after the opening backticks of a fence line, or null when the line does not open a fence. */
    private fun fenceInfo(line: String): String? {
        val t = line.trim()
        return if (t.startsWith("```")) t.removePrefix("```").trim() else null
    }

    @Suppress("CyclomaticComplexMethod") // Ordered protocol checks preserve the precise rejection reason for each invalid envelope.
    fun parseEnvelope(rawJson: String): ParsedEnvelope {
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_ENVELOPE_BYTES) return malformed()
        val root = StrictJson.parseObject(rawJson, MAX_DEPTH) ?: return malformed()
        val schema = root["schema"] as? String ?: return malformed()
        if (schema != SCHEMA) return ParsedEnvelope.Invalid(ActionRejection.UNSUPPORTED_VERSION)
        if (!TOP_KEYS.containsAll(root.keys)) return ParsedEnvelope.Invalid(ActionRejection.UNEXPECTED_FIELD)

        val type = (root["type"] as? String)?.takeIf { it == TYPE_ACTION || it == TYPE_ALIAS } ?: return malformed()
        val requestId = (root["requestId"] as? String)?.takeIf(REQUEST_ID::matches) ?: return malformed()
        val proposalId = (root["proposalId"] as? String)?.takeIf(PROPOSAL_ID::matches) ?: return malformed()
        val capabilities = (root["capabilities"] as? String)?.takeIf(REQUEST_ID::matches) ?: return malformed()
        @Suppress("UNCHECKED_CAST")
        val action = root["action"] as? Map<String, Any?> ?: return malformed()

        var phrase: String? = null
        if (type == TYPE_ALIAS) {
            phrase = (root["phrase"] as? String)
                ?.takeIf { it.length in 1..MAX_PHRASE_CHARS && it.none(Char::isISOControl) } ?: return malformed()
        } else if ("phrase" in root) {
            return ParsedEnvelope.Invalid(ActionRejection.UNEXPECTED_FIELD)
        }
        if (!ACTION_KEYS.containsAll(action.keys)) return ParsedEnvelope.Invalid(ActionRejection.UNEXPECTED_FIELD)

        val kind = action["kind"] as? String ?: return malformed()
        val id = optionalString(action, "id") ?: return malformed()
        val query = optionalString(action, "query") ?: return malformed()
        val shortcut = optionalString(action, "shortcut") ?: return malformed()
        val value = optionalInt(action, "value") ?: return malformed()
        val candidate = ActionCandidate(kind, id.orNull(), value.orNull(), query.orNull(), shortcut.orNull())
        return ParsedEnvelope.Ok(Envelope(requestId, proposalId, capabilities, type, phrase, candidate))
    }

    private fun malformed() = ParsedEnvelope.Invalid(ActionRejection.MALFORMED)

    /** Absent key gives [Present] of null; a present non-string (including JSON null) gives null (malformed). */
    private class Present<T>(val value: T?) {
        fun orNull(): T? = value
    }

    private fun optionalString(map: Map<String, Any?>, key: String): Present<String>? {
        if (key !in map) return Present(null)
        return (map[key] as? String)?.let { Present(it) }
    }

    private fun optionalInt(map: Map<String, Any?>, key: String): Present<Int>? {
        if (key !in map) return Present(null)
        val n = map[key] as? Long ?: return null
        return if (n in Int.MIN_VALUE..Int.MAX_VALUE) Present(n.toInt()) else null
    }

    /**
     * The capability list sent with every action-capable turn. One line per executable id:
     * `id|c-or-x|range|label` is compact on purpose (the 110 lines alone are about 5.9 kB, and the cap is 6,500 bytes
     * in total); the legend for `c` and `x` is in `rules`.
     * No telemetry and no claim that a result was checked against the car.
     */
    fun manifest(registry: ActionRegistry, aliasTeaching: Boolean): String {
        val rules = buildString {
            append("rules: Jedna akcja na odpowiedź. Używaj tylko id z listy vehicle; gdy czegoś nie ma na liście, ")
            append("powiedz kierowcy, że tego nie obsługujesz. Kolumny: id|ryzyko|zakres|opis. ")
            append("Ryzyko: c = wykonuje się od razu, x = kierowca musi potwierdzić na ekranie. ")
            append("Zakres min..max dotyczy pola value, pusty zakres = bez value.")
            if (aliasTeaching) {
                append(" Gdy kierowca prosi o zapamiętanie frazy jako skrótu, użyj type alias_proposal z polem phrase")
                append(" (do 40 znaków) i tym samym action; wtedy nic się nie wykonuje.")
            }
        }
        return buildList {
            add("$MANIFEST_SCHEMA version=${registry.version}")
            add(rules)
            add("vehicle:")
            for (d in registry.descriptors) {
                val risk = if (d.risk == ActionRisk.CONFIRM) "x" else "c"
                val range = d.range?.let { "${it.first}..${it.last}" } ?: ""
                add("${d.id}|$risk|$range|${d.labelPl.filterNot(Char::isISOControl).replace('|', '/')}")
            }
            add("navigate: query<=200 | shortcut=home|work")
        }.joinToString("\n")
    }

    /** One coded line for the start of the next turn. `verified` is the constant "none" in v1. */
    fun reportLine(proposalId: String, status: ReportStatus, reason: ReportReason): String =
        "bydmate.result {\"proposalId\":\"${jsonEscape(proposalId)}\",\"status\":\"${status.code}\"," +
            "\"reason\":\"${reason.code}\",\"verified\":\"none\"}"

    private fun jsonEscape(text: String): String = buildString {
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < 0x20 -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }
}

/**
 * Strict JSON for the one object the assistant may send. `org.json` is lenient (single quotes, comments,
 * trailing commas, duplicate keys) and the device uses the platform copy, so it is not used here.
 * Integers are plain `-?(0|[1-9][0-9]*)` and come back as [Long]; `-0`, overflow, fractions and exponents are
 * not integers (fractions and exponents come back as [Double]). Any violation yields null.
 */
private object StrictJson {
    private class Failure : Exception(null, null, false, false)

    fun parseObject(text: String, maxDepth: Int): Map<String, Any?>? = try {
        val reader = Reader(text, maxDepth)
        reader.skipWhitespace()
        val value = reader.value(1)
        reader.skipWhitespace()
        if (!reader.atEnd()) throw Failure()
        @Suppress("UNCHECKED_CAST")
        value as? Map<String, Any?>
    } catch (_: Failure) {
        null
    }

    @Suppress("TooManyFunctions") // One cursor owns recursive parsing and the strict JSON grammar.
    private class Reader(private val s: String, private val maxDepth: Int) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && s[i] in " \t\n\r") i++
        }

        fun value(depth: Int): Any? {
            if (atEnd()) throw Failure()
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, i)) throw Failure()
            i += word.length
            return result
        }

        @Suppress("ThrowsCount") // Depth, quoted keys, duplicate keys and delimiters are distinct required rejection points.
        private fun obj(depth: Int): Map<String, Any?> {
            if (depth > maxDepth) throw Failure()
            i++
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                i++
                return map
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw Failure()
                val key = string()
                if (key in map) throw Failure()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = value(depth + 1)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw Failure()
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            if (depth > maxDepth) throw Failure()
            i++
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                i++
                return list
            }
            while (true) {
                skipWhitespace()
                list += value(depth + 1)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw Failure()
                }
            }
        }

        private fun string(): String {
            i++
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw Failure()
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c.code < 0x20 -> throw Failure()
                    c == '\\' -> out.append(escape())
                    else -> out.append(c)
                }
            }
        }

        private fun escape(): Char {
            if (atEnd()) throw Failure()
            return when (s[i++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> unicodeEscape()
                else -> throw Failure()
            }
        }

        private fun unicodeEscape(): Char {
            if (i + 4 > s.length) throw Failure()
            val hex = s.substring(i, i + 4)
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw Failure()
            i += 4
            return hex.toInt(16).toChar()
        }

        // Each branch enforces strict numeric grammar, including leading zeros, -0, fractions, exponents and overflow.
        @Suppress("CyclomaticComplexMethod", "ThrowsCount")
        private fun number(): Any {
            val start = i
            if (peek() == '-') i++
            val intStart = i
            when (peek()) {
                '0' -> i++
                in '1'..'9' -> while (peek() in '0'..'9') i++
                else -> throw Failure()
            }
            if (peek() in '0'..'9') throw Failure() // leading zero
            val intPart = s.substring(intStart, i)
            var fractional = false
            if (peek() == '.') {
                fractional = true
                i++
                if (peek() !in '0'..'9') throw Failure()
                while (peek() in '0'..'9') i++
            }
            if (peek() == 'e' || peek() == 'E') {
                fractional = true
                i++
                if (peek() == '+' || peek() == '-') i++
                if (peek() !in '0'..'9') throw Failure()
                while (peek() in '0'..'9') i++
            }
            val token = s.substring(start, i)
            if (fractional) return token.toDoubleOrNull() ?: throw Failure()
            if (token.startsWith("-") && intPart == "0") throw Failure() // -0
            return token.toLongOrNull() ?: throw Failure()
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun next(): Char = if (i < s.length) s[i++] else throw Failure()

        private fun expect(c: Char) {
            if (next() != c) throw Failure()
        }
    }
}
