package com.bydmate.app.ui.trips

import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.nativestack.VehicleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HybridTripCellsTest {

    // The cells format with the device locale (as every number on the screen does).
    private fun f(v: Double) = "%.1f".format(v)

    /** Today's EV cell: «%.1f» of kWh/100, coloured; «—» uncoloured without a figure. */
    @Test
    fun `a trip without fuel keeps today's kWh per 100`() {
        assertEquals(Per100Cell(f(15.0), colored = true), per100Cell(15.0, null, 21.3, "l"))
        assertEquals(Per100Cell(f(15.0), colored = true), per100Cell(15.0, 0.0, 21.3, "l"))
        assertEquals(Per100Cell("—", colored = false), per100Cell(null, null, 21.3, "l"))
    }

    @Test
    fun `a trip with fuel shows litres per 100 km of the whole trip, uncoloured`() {
        assertEquals(Per100Cell("${f(4.375)} l", colored = false), per100Cell(6.4, 2.8, 64.0, "l"))
    }

    @Test
    fun `fuel without distance falls back to the kWh cell`() {
        assertEquals(Per100Cell(f(6.4), colored = true), per100Cell(6.4, 2.8, 0.0, "l"))
        assertEquals(Per100Cell(f(6.4), colored = true), per100Cell(6.4, 2.8, null, "l"))
    }

    @Test
    fun `the litres cell shows one decimal or a dash`() {
        assertEquals(f(2.8), litersCell(2.8))
        assertEquals(f(0.0), litersCell(0.0))
        assertEquals("—", litersCell(null))
    }

/** Dashboard on an EV: time 2.5 and five columns of 1, exactly as before the hybrid work. */
    @Test
    fun `EV and unconfirmed cars keep today's six column weights`() {
        val today = listOf(2.5f, 1f, 1f, 1f, 1f, 1f)
        assertEquals(today, columnWeights(VehicleKind.EV))
        assertEquals(today, columnWeights(VehicleKind.UNKNOWN))
        assertEquals(listOf(2.5f, 1f, 1f, 1f, 1f, 1f, 1f), columnWeights(VehicleKind.HYBRID))
    }

    @Test
    fun `EV and unconfirmed cars keep today's trip list widths`() {
        val today = listOf(44, 48, 58, 44, 44, 56)
        assertEquals(today, tripsColumnWidthsDp(VehicleKind.EV))
        assertEquals(today, tripsColumnWidthsDp(VehicleKind.UNKNOWN))
        assertEquals(listOf(44, 48, 58, 44, 44, 56, 56), tripsColumnWidthsDp(VehicleKind.HYBRID))
    }

    @Test
    fun `day and month litres sum only the trips that carry them`() {
        assertNull(sumLiters(listOf(TripEntity(startTs = 1L))))
        assertEquals(
            4.0,
            sumLiters(listOf(TripEntity(startTs = 1L, fuelLiters = 2.8), TripEntity(startTs = 2L), TripEntity(startTs = 3L, fuelLiters = 1.2)))!!,
            1e-9,
        )
    }

    @Test
    fun `day and month litres per 100 use the km of the trips that know their litres`() {
        val trips = listOf(
            TripEntity(startTs = 1L, distanceKm = 10.0, fuelLiters = 1.0),
            TripEntity(startTs = 2L, distanceKm = 90.0),
        )
        assertEquals(10.0, litersKm(trips)!!, 1e-9)
        assertEquals("%.1f l".format(10.0), per100Cell(15.0, sumLiters(trips), litersKm(trips), "l").text)
        assertNull(litersKm(listOf(TripEntity(startTs = 1L, distanceKm = 90.0))))
        assertEquals(
            30.0,
            litersKm(listOf(TripEntity(startTs = 1L, distanceKm = 30.0, fuelLiters = 0.0), TripEntity(startTs = 2L)))!!,
            1e-9,
        )
    }

    @Test
    fun `Trips electric-only reading includes its complete unit`() {
        assertEquals(Per100Cell("${f(15.0)} kWh/100 km", true), tripsPer100Cell(15.0, null, 20.0, "kWh/100 km", "L/100 km"))
    }

    @Test
    fun `Trips fuel-only reading includes its complete fuel unit`() {
        assertEquals(Per100Cell("${f(5.0)} L/100 km", false), tripsPer100Cell(null, 1.0, 20.0, "kWh/100 km", "L/100 km"))
    }

    @Test
    fun `Trips mixed reading keeps the fuel value and matching unit`() {
        assertEquals(Per100Cell("${f(5.0)} L/100 km", false), tripsPer100Cell(15.0, 1.0, 20.0, "kWh/100 km", "L/100 km"))
    }

    @Test
    fun `Trips zero distance fallback carries only its electric unit`() {
        assertEquals(Per100Cell("${f(15.0)} kWh/100 km", true), tripsPer100Cell(15.0, 1.0, 0.0, "kWh/100 km", "L/100 km"))
        assertEquals(Per100Cell("—", false), tripsPer100Cell(null, 1.0, 0.0, "kWh/100 km", "L/100 km"))
    }

    @Test
    fun `Trips unavailable reading remains a dash without an invented unit`() {
        assertEquals(Per100Cell("—", false), tripsPer100Cell(null, null, null, "kWh/100 km", "L/100 km"))
    }

    @Test
    fun `narrow Trips table keeps measured columns and both insets scrollable`() {
        assertEquals(600f, tripsTableContentWidthDp(320f, 120f, listOf(60f, 60f, 72f, 60f, 120f, 60f)), 0f)
    }

    @Test
    fun `enlarged Trips text keeps measured columns instead of shrinking them`() {
        assertEquals(1152f, tripsTableContentWidthDp(500f, 240f, listOf(120f, 120f, 144f, 120f, 240f, 120f)), 0f)
        assertEquals(1400f, tripsTableContentWidthDp(1400f, 240f, listOf(120f, 120f, 144f, 120f, 240f, 120f)), 0f)
    }

    @Test
    fun `Trips panels stack when the chart cannot retain its minimum scaled width`() {
        assertEquals(true, tripsPanelsStacked(600f, 1f))
        assertEquals(false, tripsPanelsStacked(1200f, 1f))
        assertEquals(true, tripsPanelsStacked(1200f, 2f))
        assertEquals(true, tripsPanelsStacked(800f, 1f))
        assertEquals(false, tripsPanelsStacked(812f, 1f))
    }


}
