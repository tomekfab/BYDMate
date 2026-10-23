package com.bydmate.app.voice

import com.bydmate.app.data.remote.DiParsData

/**
 * A read question of the built-in dictionary (`ASK <id>`), answered from the car's own readings
 * without the agent. [answer] is the short Polish sentence to say, or null when the car does
 * not report the value: the phrase then goes to the agent like an unrecognized one. Pure.
 */
enum class VehicleQuestion(val id: String) {
    OUTSIDE_TEMP("outside_temp"),
    CABIN_TEMP("cabin_temp"),
    SOC("soc"),
    RANGE("range"),
    AC_TEMP("ac_temp");

    /** [rangeKm] is the estimate the dashboard shows: the snapshot itself holds no range. A
     *  charge or a climate setpoint of 0 is a cold-start reading, not a value. */
    fun answer(d: DiParsData?, rangeKm: Double?): String? = PolishVoiceReplies.answer(this, d, rangeKm)

    companion object {
        fun of(id: String): VehicleQuestion? = entries.firstOrNull { it.id == id }

    }
}
