package com.bydmate.app.voice.openclaw

import com.bydmate.app.agent.ActionCandidate
import com.bydmate.app.agent.ActionRegistry
import com.bydmate.app.agent.ActionRejection
import com.bydmate.app.agent.ActionValidation
import com.bydmate.app.agent.RegistryActionValidator
import com.bydmate.app.data.vehicle.WriteAllowlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

class OpenClawActionProtocolTest {

    private val registry: ActionRegistry by lazy {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/assets/competitor-actions.json").exists() }
            ?: error("Cannot find app/src/main/assets/competitor-actions.json from ${File(".").canonicalPath}")
        ActionRegistry(allowlist = WriteAllowlist.loadProduction { File(root, "app/src/main/assets/competitor-actions.json").readText() })
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream(path)) { "missing resource $path" }
            .bufferedReader(Charsets.UTF_8).readText()

    private fun verdict(reply: String): String = when (val e = OpenClawActionProtocol.extract(reply)) {
        is Extraction.NoBlock -> "NoBlock"
        Extraction.Malformed -> "Malformed"
        is Extraction.Block -> when (val p = OpenClawActionProtocol.parseEnvelope(e.rawJson)) {
            is ParsedEnvelope.Ok -> "Ok:${p.envelope.type}"
            is ParsedEnvelope.Invalid -> "Invalid:${p.reason}"
        }
    }

    private fun envelopeOf(reply: String): Envelope {
        val block = blockOf(reply)
        val parsed = OpenClawActionProtocol.parseEnvelope(block.rawJson)
        return (parsed as? ParsedEnvelope.Ok ?: failure("expected Ok but got $parsed")).envelope
    }

    private fun blockOf(reply: String): Extraction.Block {
        val e = OpenClawActionProtocol.extract(reply)
        return e as? Extraction.Block ?: failure("expected Block but got $e")
    }

    private fun okOf(json: String): Envelope {
        val p = parse(json)
        return (p as? ParsedEnvelope.Ok ?: failure("expected Ok but got $p")).envelope
    }

    private fun env(
        action: String = """{"kind":"vehicle","id":"hud_on"}""",
        requestId: String = "\"a1b2c3d4e5f6\"",
        proposalId: String = "\"prop-0001\"",
        extra: String = "",
        type: String = "action",
    ) = """{"schema":"bydmate.action/1","requestId":$requestId,"proposalId":$proposalId,"capabilities":"0123456789ab","type":"$type"$extra,"action":$action}"""

    private fun failure(message: String): Nothing = throw AssertionError(message)

    private fun parse(json: String) = OpenClawActionProtocol.parseEnvelope(json)

    private fun assertInvalid(reason: ActionRejection, json: String) =
        assertEquals(json, ParsedEnvelope.Invalid(reason), parse(json))

    // ---- fixtures ----

    @Test fun `every fixture matches expected tsv and every expected row has a fixture`() {
        val expected = resource("openclaw-actions/expected.tsv").lines().filter { it.isNotBlank() }
            .associate { it.substringBefore('\t') to it.substringAfter('\t') }
        assertEquals(26, expected.size)
        for ((name, want) in expected) {
            assertEquals("fixture $name", want, verdict(resource("openclaw-actions/$name.txt")))
        }
        val dir = File(javaClass.classLoader!!.getResource("openclaw-actions/expected.tsv")!!.toURI()).parentFile!!
        val onDisk = dir.listFiles { f -> f.name.endsWith(".txt") }!!.map { it.name.removeSuffix(".txt") }.toSet()
        assertEquals(expected.keys, onDisk)
    }

    @Test fun `valid fixtures carry the fields the candidate needs`() {
        val ranged = envelopeOf(resource("openclaw-actions/valid-vehicle-ranged.txt"))
        assertEquals(ActionCandidate(kind = "vehicle", id = "ac_set_temp", value = 22), ranged.candidate)
        assertEquals("a1b2c3d4e5f6", ranged.requestId)
        assertEquals("prop-0001", ranged.proposalId)
        assertEquals("0123456789ab", ranged.capabilities)
        assertNull(ranged.phrase)
        val query = envelopeOf(resource("openclaw-actions/valid-navigate-query.txt"))
        assertEquals("Lecha i Marii 3, Kraków", query.candidate.query)
        val shortcut = envelopeOf(resource("openclaw-actions/valid-navigate-shortcut.txt"))
        assertEquals("home", shortcut.candidate.shortcut)
        val alias = envelopeOf(resource("openclaw-actions/valid-alias.txt"))
        assertEquals("alias_proposal", alias.type)
        assertEquals("nawiguj do rodziców", alias.phrase)
    }

    @Test fun `raw command fid shell and massage ids parse as candidates but the validator rejects them`() {
        val validator = RegistryActionValidator(registry)
        val want = mapOf(
            "raw-chinese-command" to ActionRejection.UNKNOWN_COMMAND,
            "raw-fid" to ActionRejection.UNKNOWN_COMMAND,
            "shell-string" to ActionRejection.UNKNOWN_COMMAND,
            "massage-id" to ActionRejection.NOT_EXECUTABLE,
        )
        for ((name, reason) in want) {
            val candidate = envelopeOf(resource("openclaw-actions/$name.txt")).candidate
            assertEquals(name, ActionValidation.Invalid(reason), validator.validate(candidate, registry.version))
        }
    }

    // ---- extract ----

    @Test fun `prose around the block is joined and the block is not prose`() {
        val e = blockOf(resource("openclaw-actions/prose-before-and-after.txt"))
        assertEquals("Włączam wyświetlacz.\nMiłej jazdy!", e.prose)
        assertTrue(e.rawJson.startsWith("{\"schema\""))
    }

    @Test fun `a plain reply is returned unchanged`() {
        val text = "Cześć, jak się masz?"
        assertEquals(Extraction.NoBlock(text), OpenClawActionProtocol.extract(text))
    }

    @Test fun `another fence before the marker is prose and the marker inside another fence is ignored`() {
        val json = env()
        val reply = "```json\n{\"a\":1}\n```\n```bydmate-action\n$json\n```"
        val e = blockOf(reply)
        assertEquals("```json\n{\"a\":1}\n```", e.prose)
        assertEquals(json, e.rawJson)
        val inside = "```text\n```bydmate-action\n```\nkoniec"
        assertTrue(OpenClawActionProtocol.extract(inside) is Extraction.NoBlock)
    }

    @Test fun `windows line endings are accepted`() {
        val reply = "Ok.\r\n```bydmate-action\r\n${env()}\r\n```\r\n"
        assertEquals("Ok.", blockOf(reply).prose)
    }

    // ---- strict parser and envelope ----

    @Test fun `plain valid envelope parses`() {
        val envelope = okOf(env())
        assertEquals(ActionCandidate(kind = "vehicle", id = "hud_on"), envelope.candidate)
    }

    @Test fun `lexical violations are malformed`() {
        val bad = listOf(
            env().replace("\"a1b2c3d4e5f6\"", "'a1b2c3d4e5f6'"),
            env() + " x",
            env() + "{}",
            "/* c */" + env(),
            env(action = """{"kind":"vehicle","id":"ac_set_temp","value":NaN}"""),
            env(action = """{"kind":"vehicle","id":"ac_set_temp","value":Infinity}"""),
            env(action = """{"kind":"vehicle","id":"ac_set_temp","value":022}"""),
            env(action = """{"kind":"vehicle","id":"ac_set_temp","value":-0}"""),
            env(action = """{"kind":"vehicle","id":"ac_set_temp","value":+22}"""),
            env(action = """{"kind":"vehicle","id":"ac_set_temp","value":22.0}"""),
            env(action = """{"kind":"vehicle","id":"ac_set_temp","value":2e1}"""),
            env(action = """{"kind":"vehicle","id":"ac_set_temp","value":99999999999}"""),
            env(action = """{"kind":"vehicle","id":"ac_set_temp","value":null}"""),
            env(action = """{"kind":"vehicle","id":"ac_set_temp","value":true}"""),
            env(action = """{"kind":"vehicle","id":"a\qb"}"""),
            env(action = "{\"kind\":\"vehicle\",\"id\":\"a\tb\"}"),
            env(action = """{"kind":"vehicle","id":"hud_on"""),
            env(action = """{"kind":"vehicle",,"id":"hud_on"}"""),
            "",
            "[]",
            "null",
            "{",
        )
        for (json in bad) assertInvalid(ActionRejection.MALFORMED, json)
    }

    @Test fun `value must be a plain integer`() {
        val envelope = okOf(env(action = """{"kind":"vehicle","id":"ac_set_temp","value":-3}"""))
        assertEquals(-3, envelope.candidate.value)
        assertInvalid(ActionRejection.MALFORMED, env(action = """{"kind":"vehicle","id":"ac_set_temp","value":"-3"}"""))
    }

    @Test fun `nesting deeper than three is malformed`() {
        assertInvalid(ActionRejection.UNEXPECTED_FIELD, env(extra = ""","x":[[1]]"""))
        assertInvalid(ActionRejection.MALFORMED, env(extra = ""","x":[[[1]]]"""))
        assertInvalid(ActionRejection.MALFORMED, env(action = """{"kind":"vehicle","id":{"a":{"b":1}}}"""))
    }

    @Test fun `escapes in strings are decoded`() {
        val envelope = okOf(env(action = """{"kind":"navigate","query":"Rynek \"Główny\" \u0041 1"}"""))
        assertEquals("Rynek \"Główny\" A 1", envelope.candidate.query)
    }

    @Test fun `envelope above 2048 bytes is malformed and exactly at the cap is read`() {
        val shell = env(action = """{"kind":"navigate","query":""}""")
        val room = OpenClawActionProtocol.MAX_ENVELOPE_BYTES - shell.toByteArray().size
        val atCap = env(action = """{"kind":"navigate","query":"${"a".repeat(room)}"}""")
        assertEquals(OpenClawActionProtocol.MAX_ENVELOPE_BYTES, atCap.toByteArray().size)
        assertTrue(parse(atCap) is ParsedEnvelope.Ok)
        assertInvalid(ActionRejection.MALFORMED, env(action = """{"kind":"navigate","query":"${"a".repeat(room + 1)}"}"""))
        // bytes, not characters: 2-byte letters push the same character count over the cap
        assertInvalid(ActionRejection.MALFORMED, env(action = """{"kind":"navigate","query":"${"ł".repeat(room)}"}"""))
    }

    @Test fun `schema is checked first and other keys are strict`() {
        assertInvalid(ActionRejection.UNSUPPORTED_VERSION, env().replace("bydmate.action/1", "bydmate.action/2"))
        assertInvalid(ActionRejection.UNSUPPORTED_VERSION, env().replace("bydmate.action/1", "bydmate.action/2").replace("\"type\":\"action\"", "\"type\":\"bogus\""))
        assertInvalid(ActionRejection.MALFORMED, env().replace("\"schema\":\"bydmate.action/1\",", ""))
        assertInvalid(ActionRejection.MALFORMED, env().replace("\"schema\":\"bydmate.action/1\"", "\"schema\":5"))
        assertInvalid(ActionRejection.UNEXPECTED_FIELD, env(extra = ""","verified":"yes""""))
        assertInvalid(ActionRejection.UNEXPECTED_FIELD, env(action = """{"kind":"vehicle","id":"hud_on","extra":1}"""))
        assertInvalid(ActionRejection.UNEXPECTED_FIELD, env(extra = ""","phrase":"x""""))
    }

    @Test fun `required keys and types`() {
        for (key in listOf("requestId", "proposalId", "capabilities", "type", "action")) {
            val json = env()
            val cut = Regex(",?\"$key\":(\"[^\"]*\"|\\{[^}]*\\})").replaceFirst(json, "")
            assertInvalid(ActionRejection.MALFORMED, cut)
        }
        assertInvalid(ActionRejection.MALFORMED, env(type = "execute"))
        assertInvalid(ActionRejection.MALFORMED, env(action = "\"hud_on\""))
        assertInvalid(ActionRejection.MALFORMED, env(action = """{"id":"hud_on"}"""))
        assertInvalid(ActionRejection.MALFORMED, env(action = """{"kind":1,"id":"hud_on"}"""))
        assertInvalid(ActionRejection.MALFORMED, env(action = """{"kind":"vehicle","id":7}"""))
        assertInvalid(ActionRejection.MALFORMED, env(action = """{"kind":"navigate","query":null}"""))
    }

    @Test fun `requestId and proposalId bounds`() {
        fun id(s: String) = "\"$s\""
        assertTrue(parse(env(requestId = id("a"))) is ParsedEnvelope.Ok)
        assertTrue(parse(env(requestId = id("A_b-9".repeat(12) + "abcd"))) is ParsedEnvelope.Ok) // 64
        assertInvalid(ActionRejection.MALFORMED, env(requestId = id("")))
        assertInvalid(ActionRejection.MALFORMED, env(requestId = id("a".repeat(65))))
        assertInvalid(ActionRejection.MALFORMED, env(requestId = id("ab cd")))
        assertInvalid(ActionRejection.MALFORMED, env(requestId = id("łódź")))
        assertInvalid(ActionRejection.MALFORMED, env(requestId = "12"))
        assertTrue(parse(env(proposalId = id("abcdefgh"))) is ParsedEnvelope.Ok)
        assertTrue(parse(env(proposalId = id("p".repeat(64)))) is ParsedEnvelope.Ok)
        assertInvalid(ActionRejection.MALFORMED, env(proposalId = id("abcdefg")))
        assertInvalid(ActionRejection.MALFORMED, env(proposalId = id("p".repeat(65))))
        assertInvalid(ActionRejection.MALFORMED, env(proposalId = id("abcd/efgh")))
        assertInvalid(ActionRejection.MALFORMED, env(proposalId = id("abcd\\nefgh")))
    }

    @Test fun `alias proposal needs a phrase of 1 to 40 characters without control characters`() {
        fun alias(phrase: String) = env(
            action = """{"kind":"navigate","shortcut":"home"}""", type = "alias_proposal", extra = ""","phrase":"$phrase"""",
        )
        assertTrue(parse(alias("a")) is ParsedEnvelope.Ok)
        assertTrue(parse(alias("ł".repeat(40))) is ParsedEnvelope.Ok)
        assertInvalid(ActionRejection.MALFORMED, alias(""))
        assertInvalid(ActionRejection.MALFORMED, alias("a".repeat(41)))
        assertInvalid(ActionRejection.MALFORMED, alias("a\\nb"))
        assertInvalid(ActionRejection.MALFORMED, alias("a\\u0007b"))
        assertInvalid(ActionRejection.MALFORMED, env(type = "alias_proposal"))
        assertInvalid(ActionRejection.MALFORMED, env(type = "alias_proposal", extra = ""","phrase":5"""))
    }

    @Test fun `codec does not validate ids kinds or values`() {
        val envelope = okOf(env(action = """{"kind":"teleport","id":"nonsense","value":99999}"""))
        assertEquals(ActionCandidate(kind = "teleport", id = "nonsense", value = 99999), envelope.candidate)
    }

    // ---- manifest ----

    @Test fun `manifest lists each of the 110 ids exactly once by line start`() {
        val text = OpenClawActionProtocol.manifest(registry, aliasTeaching = true)
        val lines = text.lines()
        val start = lines.indexOf("vehicle:")
        val end = lines.indexOfFirst { it.startsWith("navigate:") }
        assertTrue(start in 0 until end)
        val vehicleLines = lines.subList(start + 1, end)
        assertEquals(110, vehicleLines.size)
        assertEquals(registry.descriptors.map { it.id }, vehicleLines.map { it.substringBefore('|') })
        for ((line, d) in vehicleLines.zip(registry.descriptors)) {
            val range = d.range?.let { "${it.first}..${it.last}" } ?: ""
            val risk = if (d.risk == com.bydmate.app.agent.ActionRisk.CONFIRM) "x" else "c"
            assertEquals("${d.id}|$risk|$range|${d.labelPl}", line)
        }
        assertEquals(55, vehicleLines.count { it.split('|')[1] == "x" })
    }

    @Test fun `manifest header version navigate line and legend`() {
        val text = OpenClawActionProtocol.manifest(registry, aliasTeaching = false)
        assertEquals("bydmate.capabilities/1 version=${registry.version}", text.lines().first())
        assertTrue(Regex("version=[0-9a-f]{12}$").containsMatchIn(text.lines().first()))
        assertTrue(text.lines().any { it == "navigate: query<=200 | shortcut=home|work" })
        val rules = text.lines().first { it.startsWith("rules:") }
        assertTrue("legend c", rules.contains("c ="))
        assertTrue("legend x", rules.contains("x ="))
    }

    @Test fun `alias teaching text appears only when enabled`() {
        val on = OpenClawActionProtocol.manifest(registry, aliasTeaching = true)
        val off = OpenClawActionProtocol.manifest(registry, aliasTeaching = false)
        assertTrue(on.contains("alias_proposal"))
        assertFalse(off.contains("alias_proposal"))
        assertTrue(on.length > off.length)
    }

    @Test fun `manifest carries no verification or massage wording`() {
        for (teaching in listOf(true, false)) {
            val text = OpenClawActionProtocol.manifest(registry, teaching).lowercase()
            assertFalse(text.contains("verif"))
            assertFalse(text.contains("readback"))
            assertFalse(text.contains("massage"))
        }
    }

    @Test fun `worst case manifest fits the 6500 byte cap`() {
        val bytes = OpenClawActionProtocol.manifest(registry, aliasTeaching = true).toByteArray(Charsets.UTF_8).size
        println("MANIFEST_BYTES aliasTeaching=true: $bytes")
        println("MANIFEST_BYTES aliasTeaching=false: ${OpenClawActionProtocol.manifest(registry, aliasTeaching = false).toByteArray(Charsets.UTF_8).size}")
        assertTrue("manifest is $bytes bytes", bytes <= 6_500)
    }

    @Test fun `manifest is stable and follows the registry version`() {
        assertEquals(
            OpenClawActionProtocol.manifest(registry, true),
            OpenClawActionProtocol.manifest(ActionRegistry(allowlist = registryAllowlist()), true),
        )
    }

    private fun registryAllowlist(): WriteAllowlist {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "app/src/main/assets/competitor-actions.json").exists() }
        return WriteAllowlist.loadProduction { File(root, "app/src/main/assets/competitor-actions.json").readText() }
    }

    // ---- instruction ----

    @Test fun `action instruction describes the block and its limits`() {
        val text = OpenClawActionProtocol.ACTION_INSTRUCTION
        assertTrue(text.contains("```bydmate-action"))
        assertTrue(text.contains("bydmate.action/1"))
        assertTrue(text.contains("tylko gdy kierowca"))
        assertTrue(text.contains("dokładnie jeden"))
        assertTrue(text.contains("nigdy"))
        assertTrue(text.contains("requestId"))
        assertFalse(text.lowercase().contains("verif"))
        assertFalse(text.lowercase().contains("massage"))
    }

    // ---- report line ----

    @Test fun `report line is a coded line that always says verified none`() {
        for (status in ReportStatus.entries) {
            for (reason in ReportReason.entries) {
                val line = OpenClawActionProtocol.reportLine("prop-0001", status, reason)
                assertTrue(line, line.startsWith("bydmate.result {"))
                assertTrue(line, line.endsWith("\"verified\":\"none\"}"))
                assertTrue(line.contains("\"status\":\"${status.code}\""))
                assertTrue(line.contains("\"reason\":\"${reason.code}\""))
                assertEquals(1, line.lines().size)
            }
        }
        assertEquals(
            """bydmate.result {"proposalId":"prop-0001","status":"done","reason":"ok","verified":"none"}""",
            OpenClawActionProtocol.reportLine("prop-0001", ReportStatus.DONE, ReportReason.OK),
        )
    }

    @Test fun `report line escapes the proposal id`() {
        val line = OpenClawActionProtocol.reportLine("a\"b\\c\nd", ReportStatus.FAILED, ReportReason.DISPATCH_FAILED)
        assertTrue(line, line.contains("\"proposalId\":\"a\\\"b\\\\c\\nd\""))
        assertEquals(1, line.lines().size)
    }

    @Test fun `status codes match the contract list`() {
        assertEquals(
            listOf("done", "failed", "blocked", "declined", "expired", "cancelled", "uncertain", "rejected", "saved", "dismissed"),
            ReportStatus.entries.map { it.code },
        )
    }

    @Test fun `every rejection has a report reason and codes are unique lowercase tokens`() {
        val names = ReportReason.entries.map { it.name }.toSet()
        for (r in ActionRejection.entries) assertTrue("no reason for $r", r.name in names)
        for (extra in listOf("ALIAS_UNAVAILABLE", "LEDGER_UNAVAILABLE")) assertTrue(extra, extra in names)
        assertEquals(ReportReason.entries.size, ReportReason.entries.map { it.code }.toSet().size)
        for (r in ReportReason.entries) assertTrue(r.code, Regex("[a-z_]+").matches(r.code))
    }

    // ---- probe replies (only after an authorized probe) ----

    @Test fun `probe replies verdicts`() {
        val dir = System.getenv("BYDMATE_PROBE_REPLIES")
        Assume.assumeNotNull(dir)
        val files = File(dir!!).listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }
        for (f in files) println("PROBE ${f.name}: ${verdict(f.readText(Charsets.UTF_8))}")
    }
}
