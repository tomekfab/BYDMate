package com.bydmate.app.voice

import com.bydmate.app.data.remote.diParsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleQuestionTest {

    @Test fun `Polish plural forms`() {
        val forms = listOf(0, 1, 2, 4, 5, 11, 12, 14, 21, 22, 25, 101, 111, 112, 121)
            .associateWith { PolishVoiceReplies.plural(it, "stopień", "stopnie", "stopni") }
        assertEquals(
            mapOf(
                0 to "stopni", 1 to "stopień", 2 to "stopnie", 4 to "stopnie", 5 to "stopni",
                11 to "stopni", 12 to "stopni", 14 to "stopni", 21 to "stopni", 22 to "stopnie",
                25 to "stopni", 101 to "stopni", 111 to "stopni", 112 to "stopni", 121 to "stopni",
            ),
            forms,
        )
    }

    @Test fun `outside temperature says Polish degrees and minus`() {
        fun say(t: Int) = VehicleQuestion.OUTSIDE_TEMP.answer(diParsData(exteriorTemp = t), null)
        assertEquals("Na zewnątrz jest 19 stopni.", say(19))
        assertEquals("Na zewnątrz jest 21 stopni.", say(21))
        assertEquals("Na zewnątrz jest 0 stopni.", say(0))
        assertEquals("Na zewnątrz jest minus 5 stopni.", say(-5))
        assertEquals("Na zewnątrz jest minus 1 stopień.", say(-1))
        assertEquals("Na zewnątrz jest minus 22 stopnie.", say(-22))
    }

    @Test fun `cabin temperature`() {
        assertEquals("W kabinie jest 22 stopnie.", VehicleQuestion.CABIN_TEMP.answer(diParsData(insideTemp = 22), null))
        assertEquals("W kabinie jest 11 stopni.", VehicleQuestion.CABIN_TEMP.answer(diParsData(insideTemp = 11), null))
    }

    @Test fun `battery charge in percent`() {
        fun say(soc: Int) = VehicleQuestion.SOC.answer(diParsData(soc = soc), null)
        assertEquals("Poziom baterii wynosi 65 procent.", say(65))
        assertEquals("Poziom baterii wynosi 1 procent.", say(1))
        assertEquals("Poziom baterii wynosi 23 procent.", say(23))
        assertEquals("Poziom baterii wynosi 100 procent.", say(100))
    }

    @Test fun `range in kilometres from the range the dashboard shows`() {
        fun say(km: Double) = VehicleQuestion.RANGE.answer(diParsData(soc = 65), km)
        assertEquals("Zasięg wynosi 214 kilometrów.", say(214.4))
        assertEquals("Zasięg wynosi 221 kilometrów.", say(220.6))
        assertEquals("Zasięg wynosi 32 kilometry.", say(32.0))
    }

    @Test fun `climate set temperature`() {
        assertEquals("Klimatyzacja jest ustawiona na 19 stopni.", VehicleQuestion.AC_TEMP.answer(diParsData(acTemp = 19), null))
        assertEquals("Klimatyzacja jest ustawiona na 21 stopni.", VehicleQuestion.AC_TEMP.answer(diParsData(acTemp = 21), null))
    }

    @Test fun `an unknown value has no answer, never a zero`() {
        val empty = diParsData()
        VehicleQuestion.entries.forEach { q ->
            assertNull("$q without a snapshot", q.answer(null, null))
            assertNull("$q with nothing read", q.answer(empty, null))
        }
        // A cold-start 0 is not a charge or a climate setpoint.
        assertNull(VehicleQuestion.SOC.answer(diParsData(soc = 0), null))
        assertNull(VehicleQuestion.AC_TEMP.answer(diParsData(acTemp = 0), null))
    }

    @Test fun `ASK in the dictionary runs the question`() {
        val d = VoiceDictionary.parse("solo jaki poziom baterii => ASK soc")
        assertEquals(ParseResult.Ask(VehicleQuestion.SOC), d.match(VoiceDictionary.words("jaki poziom baterii")))
    }

    @Test fun `an unknown question in the dictionary is a loader error`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            VoiceDictionary.parse("solo jaki poziom paliwa => ASK fuel")
        }
        assertTrue(e.message, "fuel" in e.message.orEmpty())
    }

    @Test fun `every question is asked by some phrase of the dictionary`() {
        val asked = VoiceDictionary.load().phrases().mapNotNull { (it.second as? ParseResult.Ask)?.question }.toSet()
        assertEquals(VehicleQuestion.entries.toSet(), asked)
    }
}
