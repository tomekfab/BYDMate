package com.bydmate.app.navdata.waze

import java.math.BigDecimal

/** Why a Waze nav-bar read is not an observation. */
enum class WazeIgnoreReason { MISSING, EMPTY, ZERO, FORMAT }

/** One parsed nav-bar read: a [Guidance] is an observation, an [Ignore] is not. */
sealed interface WazeParse {
    /** Distance to the next maneuver in metres and the street line as Waze shows it ("" when empty). */
    data class Guidance(val distanceM: Int, val road: String) : WazeParse
    data class Ignore(val reason: WazeIgnoreReason) : WazeParse
}

/** Only the one distance form the capture showed is read: `N`, `N.N` or `N.NN`, one U+0020, `m` or `km`.
 *  Comma decimals, NBSP, miles, feet and other casings are [WazeIgnoreReason.FORMAT]; nothing is guessed. */
object WazeGuidanceParser {
    private val DISTANCE = Regex("""(\d{1,6})(?:\.(\d{1,2}))? (m|km)""")
    private val KM_TO_M = BigDecimal(1000)

    fun parse(distanceText: String?, streetText: String?): WazeParse {
        if (distanceText == null) return WazeParse.Ignore(WazeIgnoreReason.MISSING)
        if (distanceText.isBlank()) return WazeParse.Ignore(WazeIgnoreReason.EMPTY)
        val match = DISTANCE.matchEntire(distanceText) ?: return WazeParse.Ignore(WazeIgnoreReason.FORMAT)
        val (whole, fraction, unit) = match.destructured
        val metres = if (unit == "km") {
            BigDecimal(if (fraction.isEmpty()) whole else "$whole.$fraction").multiply(KM_TO_M).toInt()
        } else {
            // A fractional metre is not a form Waze showed.
            if (fraction.isNotEmpty()) return WazeParse.Ignore(WazeIgnoreReason.FORMAT)
            whole.toInt()
        }
        if (metres <= 0) return WazeParse.Ignore(WazeIgnoreReason.ZERO)
        return WazeParse.Guidance(metres, streetText ?: "")
    }
}
