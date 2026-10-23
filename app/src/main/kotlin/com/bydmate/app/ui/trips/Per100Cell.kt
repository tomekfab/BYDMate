package com.bydmate.app.ui.trips

import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.nativestack.VehicleKind

/** Text of the «/100» cell; [colored] = paint it with the consumption scale. */
internal data class Per100Cell(val text: String, val colored: Boolean)

/**
 * The «/100» cell of a trip row (DM-i, #184). A trip without fuel keeps today's kWh/100 with the
 * consumption colour; a trip that burned fuel shows litres per 100 km of the whole trip with
 * [litersUnit], uncoloured: one column, no split between battery and engine km.
 */
internal fun per100Cell(
    kwhPer100: Double?,
    fuelLiters: Double?,
    distanceKm: Double?,
    litersUnit: String,
): Per100Cell {
    val km = distanceKm?.takeIf { it > 0.0 }
    val litersPer100 = fuelLiters?.takeIf { it > 0.0 }?.let { liters -> km?.let { liters / it * 100.0 } }
    return if (litersPer100 != null) {
        Per100Cell("%.1f %s".format(litersPer100, litersUnit), colored = false)
    } else {
        Per100Cell(kwhPer100?.let { "%.1f".format(it) } ?: "—", colored = kwhPer100 != null)
    }
}

/** The "l" cell: litres of the trip, "—" when either counter edge was missing. */
internal fun litersCell(fuelLiters: Double?): String = fuelLiters?.let { "%.1f".format(it) } ?: "—"

/**
 * Weights of the trip table on Dashboard: time | duration | km | kWh | (l) | /100 | currency.
 * The "l" column exists only on a hybrid; EV and UNKNOWN keep today's six columns.
 */
internal fun columnWeights(kind: VehicleKind): List<Float> =
    if (kind == VehicleKind.HYBRID) listOf(2.5f, 1f, 1f, 1f, 1f, 1f, 1f)
    else listOf(2.5f, 1f, 1f, 1f, 1f, 1f)

/**
 * Widths (dp) of the Trips columns after the time cell: duration | km | SOC | kWh | (l) | /100 | currency.
 * A hybrid gets the "l" column and a wider "/100" that holds "4.4 l".
 */
internal fun tripsColumnWidthsDp(kind: VehicleKind): List<Int> =
    if (kind == VehicleKind.HYBRID) listOf(44, 48, 58, 44, 44, 56, 56)
    else listOf(44, 48, 58, 44, 44, 56)

/** Sum of the trips' litres, null when none of them carries a litre figure. */
internal fun sumLiters(trips: List<TripEntity>): Double? =
    trips.mapNotNull { it.fuelLiters }.takeIf { it.isNotEmpty() }?.sum()

/**
 * Km of the trips that know their litres (a zero-litre trip counts), the denominator of
 * [sumLiters] per 100 km; null when none does. A trip with unknown litres would dilute it.
 */
internal fun litersKm(trips: List<TripEntity>): Double? =
    trips.filter { it.fuelLiters != null }.takeIf { it.isNotEmpty() }?.sumOf { it.distanceKm ?: 0.0 }
