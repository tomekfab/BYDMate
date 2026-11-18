package com.bydmate.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class BatterySummaryTest {
    private val full = DashboardUiState(currentSoh = 95f, avgBatTemp = 25, voltage12v = 13.2,
        cellVoltageMin = 3.20, cellVoltageMax = 3.21, cellVoltageDelta = 0.01, soc = 50, insulationKohm = 1200)

    @Test fun `critical outranks every warning`() {
        assertEquals(BatterySummary.CRITICAL, batterySummary(full.copy(currentSoh = 79f, voltage12vStatus = "warning")))
        assertEquals(BatterySummary.CRITICAL, batterySummary(full.copy(currentSoh = 85f, batteryHealthStatus = "critical")))
        assertEquals(BatterySummary.CRITICAL, batterySummary(full.copy(currentSoh = 85f, voltage12vStatus = "critical")))
        assertEquals(BatterySummary.CRITICAL, batterySummary(full.copy(currentSoh = 85f, insulationKohm = 499)))
    }
    @Test fun `each existing warning contributor is retained`() {
        listOf(full.copy(currentSoh = 89f), full.copy(batteryHealthStatus = "warning", avgBatTemp = 46),
            full.copy(batteryHealthStatus = "warning", cellVoltageDelta = 0.05),
            full.copy(voltage12vStatus = "warning", voltage12v = 12.3), full.copy(insulationKohm = 999))
            .forEach { assertEquals(BatterySummary.WARNING, batterySummary(it)) }
    }
    @Test fun `threshold boundaries retain existing severity`() {
        assertEquals(BatterySummary.NO_WARNINGS, batterySummary(full.copy(currentSoh = 90f, insulationKohm = 1000)))
        assertEquals(BatterySummary.WARNING, batterySummary(full.copy(currentSoh = 80f, insulationKohm = 500)))
    }
    @Test fun `full telemetry can report no warnings`() {
        assertEquals(BatterySummary.NO_WARNINGS, batterySummary(full))
    }
    @Test fun `partial telemetry does not claim a complete check`() {
        assertEquals(BatterySummary.PARTIAL, batterySummary(DashboardUiState(currentSoh = 95f)))
        assertEquals(BatterySummary.PARTIAL, batterySummary(full.copy(voltage12v = null)))
        assertEquals(BatterySummary.PARTIAL, batterySummary(full.copy(soc = null)))
    }
    @Test fun `missing telemetry is unavailable despite default ok flags`() {
        assertEquals(BatterySummary.UNAVAILABLE, batterySummary(DashboardUiState()))
    }
    @Test fun `known warning wins over partial telemetry`() {
        assertEquals(BatterySummary.WARNING, batterySummary(DashboardUiState(insulationKohm = 750)))
    }
}
