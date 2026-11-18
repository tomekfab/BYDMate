package com.bydmate.app.ui.dashboard

internal enum class BatterySummary { CRITICAL, WARNING, UNAVAILABLE, PARTIAL, NO_WARNINGS }

/** Keeps the existing diagnostics, while distinguishing absent readings from a complete check. */
internal fun batterySummary(state: DashboardUiState): BatterySummary {
    val soh = state.currentSoh ?: 100f
    val insulation = state.insulationKohm ?: 1000
    val readings = listOf(state.currentSoh, state.avgBatTemp, state.voltage12v,
        state.cellVoltageMin, state.cellVoltageMax, state.cellVoltageDelta, state.insulationKohm)
    val reportedCount = readings.count { it != null }
    return when {
        soh < 80 || state.hasDiagnosticStatus("critical") || insulation < 500 -> BatterySummary.CRITICAL
        soh < 90 || state.hasDiagnosticStatus("warning") || insulation < 1000 -> BatterySummary.WARNING
        reportedCount == 0 -> BatterySummary.UNAVAILABLE
        reportedCount != readings.size || state.soc == null -> BatterySummary.PARTIAL
        else -> BatterySummary.NO_WARNINGS
    }
}

private fun DashboardUiState.hasDiagnosticStatus(status: String): Boolean =
    batteryHealthStatus == status || voltage12vStatus == status
