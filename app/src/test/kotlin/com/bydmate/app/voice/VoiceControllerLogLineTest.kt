package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceControllerLogLineTest {

    @Test fun `every value stays on one line with quotes escaped`() {
        val e = VoiceJournalEntry(
            transcript = "powiedz \"cześć\"\njeszcze", route = VoiceJournalEntry.Route.AUTOMATION, detail = "",
            outcome = VoiceJournalEntry.Outcome.OK, command = "Dom\n\"wieczór\"\r\n  noc", refusal = null,
        )
        assertEquals(
            "heard=\"powiedz 'cześć' jeszcze\" route=automation cmd=Dom 'wieczór' noc reason=-",
            VoiceController.logLine(e),
        )
    }

    @Test fun `the detail line next to it is flattened and capped too`() {
        assertEquals("automation fired: heard='otwórz okno' rule=Dom",
            VoiceController.logDetail("automation fired: heard=\"otwórz\n okno\" rule=Dom"))
        val line = VoiceController.logDetail("powiedz \"x\"\n" + "a".repeat(1_000))
        assertTrue(line, '\n' !in line && '"' !in line)
        assertTrue(line.length.toString(), line.length < 600)
    }

    @Test fun `the busy drop line is flattened and capped`() {
        assertEquals("Utterance dropped while busy: otwórz 'okno'", VoiceController.busyDropLine("otwórz\n\"okno\""))
        val line = VoiceController.busyDropLine("a".repeat(1_000))
        assertTrue(line.length.toString(), line.length < 600)
    }

    @Test fun `long values are capped`() {
        val long = "a".repeat(1_000)
        val line = VoiceController.logLine(VoiceJournalEntry(transcript = long, route = VoiceJournalEntry.Route.NLU,
            detail = "", outcome = VoiceJournalEntry.Outcome.OK, command = long))
        assertTrue(line.length.toString(), line.length < 500)
        assertTrue(line, '\n' !in line)
    }
}
