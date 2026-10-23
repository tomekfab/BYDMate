package com.bydmate.app.voice

import com.bydmate.app.data.vehicle.CommandTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Polish-only exhaustive grammar fixture.  Its oracle is self-contained so retired Russian
 * corpus code cannot influence the remaining command grammar. */
object PolishGoldenCorpus {
    const val UNKNOWN = "UNKNOWN"
    const val REFUSED = "REFUSED:"

    data class Row(val utterance: String, val expected: String, val category: String, val danger: Boolean)

    fun load(): List<Row> {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("voice/pl_commands_golden.tsv"))
        return stream.bufferedReader(Charsets.UTF_8).readText().lines().drop(1).filter { it.isNotBlank() }.map { line ->
            val cols = line.split('\t')
            Row(cols[0], cols[1], cols[2], cols.getOrNull(3) == "danger")
        }
    }

    fun actual(utterance: String): String = when (val verdict = NluParser.classify(utterance, VoiceLanguage.PL)) {
        is NluVerdict.Match -> render(verdict.result)
        NluVerdict.Unknown -> UNKNOWN
        is NluVerdict.Refused -> REFUSED + verdict.reason
    }

    fun render(result: ParseResult): String = when (result) {
        is ParseResult.Command -> result.commands.joinToString("+")
        is ParseResult.RelativeTemp -> "TEMP:" + (if (result.delta > 0) "+" else "") + result.delta
        is ParseResult.Volume -> "VOL:" + result.payload
        is ParseResult.Ask -> "ASK:" + result.question.id
        ParseResult.Music -> "MUSIC"
        ParseResult.NavigateHome -> "NAVIGATE_HOME"
        ParseResult.NavigateWork -> "NAVIGATE_WORK"
        is ParseResult.NavigateDestination -> "NAVIGATE_DESTINATION:" + result.destination
        ParseResult.Unrecognized -> "UNRECOGNIZED"
    }

    fun doesMoreThanExpected(expected: String, actual: String): Boolean {
        val wanted = apertures(expected)
        return apertures(actual).any { (target, percent) -> percent > (wanted[target] ?: 0) }
    }

    private fun apertures(rendered: String): Map<String, Int> {
        if (rendered.startsWith("TEMP:") || rendered.startsWith("VOL:")) return emptyMap()
        return buildMap {
            rendered.split('+').flatMap { CommandTranslator.resolve(it) }.forEach { write ->
                val aperture = apertureFor(write)
                if (aperture != null) put(aperture.first, aperture.second)
            }
        }
    }

    private fun apertureFor(write: CommandTranslator.Resolved): Pair<String, Int>? {
        val action = write.actionName
        if (action.startsWith("sunroof_")) {
            val percent = when (write.value) { 1, 6 -> 100; 3 -> 50; 5 -> 7; else -> 0 }
            return "sunroof" to percent
        }
        if (!action.startsWith("window_")) return null
        return when {
            action.endsWith("_open") -> action.removeSuffix("_open") to 100
            action.endsWith("_close") -> action.removeSuffix("_close") to 0
            action.endsWith("_pos") -> action.removeSuffix("_pos") to write.value
            else -> null
        }
    }
}

@RunWith(Parameterized::class)
class PolishCommandGoldenTest(private val row: PolishGoldenCorpus.Row) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{index}: {0}")
        fun rows(): List<PolishGoldenCorpus.Row> = PolishGoldenCorpus.load()
    }

    @Test fun resolves_exactly_what_the_fixture_says() {
        assertEquals("\"${row.utterance}\"", row.expected, PolishGoldenCorpus.actual(row.utterance))
    }

    @Test fun a_refused_or_unknown_phrase_dispatches_nothing() {
        if (row.expected == PolishGoldenCorpus.UNKNOWN || row.expected.startsWith(PolishGoldenCorpus.REFUSED)) {
            assertEquals(ParseResult.Unrecognized, NluParser.parse(row.utterance, VoiceLanguage.PL))
        }
    }

    @Test fun never_opens_more_than_asked() {
        if (row.danger) assertFalse(
            "\"${row.utterance}\" opens more than ${row.expected}",
            PolishGoldenCorpus.doesMoreThanExpected(row.expected, PolishGoldenCorpus.actual(row.utterance)),
        )
    }
}

class PolishCommandCoverageTest {
    private companion object { val polish = VoiceDictionary.load(VoiceLanguage.PL) }
    // The dictionary may also emit the local-only strings, which stay out of VoiceCatalog on purpose.
    private val catalog: Set<String> = VoiceCatalog.ALL.flatMapTo(HashSet()) { spec ->
        spec.value?.let { value -> (value.min..value.max).map { spec.command(it) } } ?: listOf(spec.command(null))
    } + CommandTranslator.LOCAL_VOICE_ONLY

    @Test fun corpus_is_big_and_covers_every_category() {
        val rows = PolishGoldenCorpus.load()
        val categories = rows.map { it.category }.toSet()
        assertTrue(categories.toString(), categories.containsAll(listOf(
            "windows", "windows-except", "sunroof", "seats", "steering-mirrors", "climate", "temperature", "fan", "media",
            "locks", "lights", "hud", "drive", "questions", "compound", "numbers", "aliases", "negation", "quotation",
            "unsupported", "partial-compound", "harmless", "navigation", "natural",
        )))
        assertTrue(rows.size >= 400)
    }

    @Test fun no_two_templates_disagree() = assertTrue(polish.conflicts().joinToString("\n"), polish.conflicts().isEmpty())

    @Test fun every_command_the_dictionary_emits_is_in_the_catalog() {
        val unknown = polish.phrases().flatMap { (phrase, result) ->
            ((result as? ParseResult.Command)?.commands.orEmpty()).map { phrase to it }
        }.filter { it.second !in catalog }.toList()
        assertTrue(unknown.joinToString("\n"), unknown.isEmpty())
    }

    @Test fun every_dictionary_phrase_parses_to_its_own_result() {
        val wrong = polish.phrases().filter { (phrase, result) ->
            NluParser.parse(phrase, VoiceLanguage.PL) != result
        }.map { (phrase, result) -> "\"$phrase\" => ${NluParser.parse(phrase, VoiceLanguage.PL)} (want $result)" }.toList()
        assertTrue(wrong.take(20).joinToString("\n"), wrong.isEmpty())
    }

    @Test fun every_phrase_holds_a_word_the_refusal_check_knows() {
        val blind = polish.phrases().map { it.first }.toSet().filter {
            PolishVoiceTextRules.commandLikeRefusal("nie $it") == null
        }
        assertTrue(blind.take(20).joinToString("\n"), blind.isEmpty())
    }

    @Test fun polish_phrase_stays_a_polish_command() {
        assertEquals(ParseResult.Command("主驾打开100"), NluParser.parse("otwórz okno", VoiceLanguage.PL))
    }
}
