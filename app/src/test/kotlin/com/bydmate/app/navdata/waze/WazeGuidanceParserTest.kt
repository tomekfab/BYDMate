package com.bydmate.app.navdata.waze

import com.bydmate.app.navdata.waze.WazeIgnoreReason.EMPTY
import com.bydmate.app.navdata.waze.WazeIgnoreReason.FORMAT
import com.bydmate.app.navdata.waze.WazeIgnoreReason.MISSING
import com.bydmate.app.navdata.waze.WazeIgnoreReason.ZERO
import org.junit.Assert.assertEquals
import org.junit.Test

class WazeGuidanceParserTest {
    @Test fun `valid distances parse to metres`() {
        val table = mapOf(
            "530 m" to 530, "99 m" to 99, "9 m" to 9, "990 m" to 990,
            "5.6 km" to 5600, "5 km" to 5000, "1 km" to 1000, "2.7 km" to 2700,
        )
        for ((text, metres) in table) {
            assertEquals(text, WazeParse.Guidance(metres, ""), WazeGuidanceParser.parse(text, null))
        }
    }

    @Test fun `zero distance is ignored as ZERO`() {
        assertEquals(WazeParse.Ignore(ZERO), WazeGuidanceParser.parse("0 m", null))
        assertEquals(WazeParse.Ignore(ZERO), WazeGuidanceParser.parse("0 m", "S0"))
    }

    @Test fun `missing and blank distance`() {
        assertEquals(WazeParse.Ignore(MISSING), WazeGuidanceParser.parse(null, null))
        assertEquals(WazeParse.Ignore(MISSING), WazeGuidanceParser.parse(null, "S0"))
        assertEquals(WazeParse.Ignore(EMPTY), WazeGuidanceParser.parse("", null))
        assertEquals(WazeParse.Ignore(EMPTY), WazeGuidanceParser.parse("   ", "S0"))
    }

    @Test fun `forms the capture never showed are FORMAT`() {
        val bad = listOf(
            "1,2 km", "1 km", "530 m", "5.6km", "5.6 KM", "0.3 mi", "500 ft", "-5 m", "5.5 m",
            "abc", "9999999 m", "530 m ", " 530 m", "530  m", "530 M", "5.6.1 km", "5. km", ".5 km", "530",
        )
        for (text in bad) {
            assertEquals(text, WazeParse.Ignore(FORMAT), WazeGuidanceParser.parse(text, "S0"))
        }
    }

    @Test fun `street passes through with a valid distance`() {
        assertEquals(WazeParse.Guidance(530, ""), WazeGuidanceParser.parse("530 m", null))
        assertEquals(WazeParse.Guidance(530, ""), WazeGuidanceParser.parse("530 m", ""))
        assertEquals(WazeParse.Guidance(530, "S0"), WazeGuidanceParser.parse("530 m", "S0"))
        assertEquals(WazeParse.Guidance(5600, "S0"), WazeGuidanceParser.parse("5.6 km", "S0"))
    }

    @Test fun `invalid distance with a street is still ignored`() {
        assertEquals(WazeParse.Ignore(FORMAT), WazeGuidanceParser.parse("5.6km", "S0"))
        assertEquals(WazeParse.Ignore(MISSING), WazeGuidanceParser.parse(null, "S0"))
    }
}
