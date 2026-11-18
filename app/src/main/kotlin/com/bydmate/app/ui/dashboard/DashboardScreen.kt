package com.bydmate.app.ui.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingFlat
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydmate.app.R
import com.bydmate.app.data.autoservice.AdbVerdict
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.remote.DynamicMetric
import com.bydmate.app.data.trips.TripAutoResetMode
import com.bydmate.app.data.trips.TripCounterUi
import com.bydmate.app.domain.calculator.Trend
import com.bydmate.app.ui.components.AppAlertDialog
import com.bydmate.app.ui.components.AdbVerdictDialog
import com.bydmate.app.ui.components.CardDetailDialog
import com.bydmate.app.ui.components.SocGauge
import com.bydmate.app.ui.components.TripCard
import com.bydmate.app.ui.components.adbVerdictColor
import com.bydmate.app.ui.components.adbVerdictText
import com.bydmate.app.ui.components.consumptionColor
import com.bydmate.app.ui.components.rememberSocGaugeMinSize
import com.bydmate.app.ui.theme.*
import com.bydmate.app.ui.widget.TRIP_DISTANCE_TREND_THRESHOLD_KM
import com.bydmate.app.ui.widget.formatDurationShort
import com.bydmate.app.ui.widget.formatTripKm
import kotlinx.coroutines.delay
import kotlin.math.ceil

@Composable
fun DashboardScreen(
    onOpenTechPanel: () -> Unit,
    onOpenTrips: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTariffPeriods: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The verdict the dialog was opened for; cleared when the problem goes away so the dialog
    // cannot pop up by itself the next time a verdict appears.
    var openAdbVerdict by remember { mutableStateOf<AdbVerdict?>(null) }
    var fuelExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(state.adbVerdict) {
        if (state.adbVerdict == null || state.adbVerdict == AdbVerdict.OK) openAdbVerdict = null
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        TopBar(
            isServiceRunning = state.isServiceRunning,
            vehicleDataConnected = state.vehicleDataConnected,
            adbVerdict = state.adbVerdict,
            adbChecking = state.adbChecking,
            onAdbTap = { openAdbVerdict = state.adbVerdict },
        )
        val adbVerdict = state.adbVerdict
        if (openAdbVerdict != null && adbVerdict != null && adbVerdict != AdbVerdict.OK) {
            AdbVerdictDialog(
                verdict = adbVerdict,
                restoreEnabled = viewModel.adbRestoreEnabled,
                restoreSupported = viewModel.adbRestoreSupported,
                onCheck = { viewModel.recheckAdb() },
                onEnableRestore = { viewModel.enableAdbRestore() },
                onOpenDiagnostics = onOpenSettings,
                onDismiss = { openAdbVerdict = null },
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // LEFT COLUMN: blocks keep their own height, only the gauge yields when space runs out
            BoxWithConstraints(modifier = Modifier.weight(0.4f)) {
                val scrollState = rememberScrollState()
                val contentMinHeight = with(LocalDensity.current) { constraints.maxHeight.toDp() }
                GaugeYieldingColumn(
                    gaugeMinSize = rememberSocGaugeMinSize(state.isCharging),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .heightIn(min = contentMinHeight)
                        .padding(vertical = 4.dp),
                ) {
                    SocGauge(soc = state.soc, isCharging = state.isCharging)
                    LiveReadings(state)

                    // Battery summary and counters precede the optional insight.
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Battery details remain reachable from the compact summary.
                        val sohStatus = when {
                            (state.currentSoh ?: 100f) < 80f -> "critical"
                            (state.currentSoh ?: 100f) < 90f -> "warning"
                            else -> "ok"
                        }
                        val sohColor = when {
                            state.currentSoh == null -> TextMuted
                            sohStatus == "critical" -> SocRed
                            sohStatus == "warning" -> SocYellow
                            else -> TextPrimary
                        }
                        val tempColor = if (state.avgBatTemp == null) TextMuted else when (state.batteryHealthStatus) {
                            "critical" -> SocRed; "warning" -> SocYellow; else -> TextPrimary
                        }
                        val voltageColor = if (state.voltage12v == null) TextMuted else when (state.voltage12vStatus) {
                            "critical" -> SocRed; "warning" -> SocYellow; else -> TextPrimary
                        }
                        // MΩ: ≥1 healthy, 0.5-1 watch, <0.5 the pack is leaking to the body.
                        val insulationMohm = state.insulationKohm?.let { it / 1000.0 }
                        val insulationStatus = when {
                            insulationMohm == null -> "ok"
                            insulationMohm >= 1.0 -> "ok"
                            insulationMohm >= 0.5 -> "warning"
                            else -> "critical"
                        }
                        val insulationColor = when (insulationStatus) {
                            "critical" -> SocRed; "warning" -> SocYellow
                            else -> TextPrimary
                        }
                        val summary = batterySummary(state)
                        val summaryColor = batterySummaryColor(summary)
                        BatteryCompactCard(
                            sohText = state.currentSoh?.let { "%.0f%%".format(it) } ?: "—",
                            sohColor = sohColor,
                            tempText = state.avgBatTemp?.let { "${it}°" } ?: "—",
                            tempColor = tempColor,
                            voltageText = state.voltage12v?.let { stringResource(R.string.dashboard_voltage_value, it) } ?: "—",
                            voltageColor = voltageColor,
                            cellDeltaText = state.cellVoltageDelta
                                ?.let { stringResource(R.string.tech_value_mv, Math.round(it * 1000.0).toInt()) } ?: "—",
                            cellDeltaColor = com.bydmate.app.ui.tech.cellDeltaColor(state.cellVoltageDelta, state.soc)
                                .let { if (it == AccentGreen) TextPrimary else it },
                            insulationText = insulationMohm
                                ?.let { stringResource(R.string.tech_value_mohm, it) } ?: "—",
                            insulationColor = insulationColor,
                            summary = summary,
                            borderColor = summaryColor,
                            onClick = onOpenTechPanel
                        )
                        if (state.vehicleKind == VehicleKind.HYBRID) {
                            FuelCompactCard(
                                tankText = state.fuelPercent?.let { "$it%" } ?: "—",
                                rangeText = state.fuelRangeKm
                                    ?.let { "$it ${stringResource(R.string.dashboard_unit_km)}" } ?: "—",
                                onClick = { fuelExpanded = true },
                            )
                        }
                        // TRIP 1 / TRIP 2 resettable counters row
                        TripCounterRow(
                            state = state,
                            onToggle = viewModel::toggleTripExpanded,
                            onRequestReset = viewModel::requestTripReset,
                        )
                        // AI Insight card
                        val insightColor = when (state.effectiveInsightTone) {
                            "critical" -> SocRed
                            "warning" -> SocYellow
                            else -> TextPrimary
                        }
                        InsightCard(
                            title = state.insightTitle,
                            summary = state.insightSummary,
                            borderColor = insightColor,
                            onClick = { viewModel.toggleInsightExpanded() }
                        )

                    }

                    // Pop-up dialogs
                    if (fuelExpanded) {
                        FuelDetailDialog(
                            state = state,
                            onEditPrice = { fuelExpanded = false; onOpenTariffPeriods() },
                            onDismiss = { fuelExpanded = false },
                        )
                    }
                    if (state.insightExpanded) {
                        val insightDialogColor = when (state.effectiveInsightTone) {
                            "critical" -> SocRed
                            "warning" -> SocYellow
                            else -> TextPrimary
                        }
                        CardDetailDialog(
                            title = null,
                            borderColor = insightDialogColor,
                            onDismiss = { viewModel.toggleInsightExpanded() }
                        ) {
                            val dynamics = state.insightDynamics
                            val insights = state.insightInsights
                            val hasContent = dynamics.isNotEmpty() || insights.isNotEmpty()
                            if (hasContent) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = state.insightPeriodDays == 7,
                                        onClick = { viewModel.setInsightPeriod(7) },
                                        label = { Text(stringResource(R.string.insight_period_week), fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = AccentGreen.copy(alpha = 0.25f),
                                            selectedLabelColor = AccentGreen
                                        ),
                                    )
                                    FilterChip(
                                        selected = state.insightPeriodDays == 30,
                                        onClick = { viewModel.setInsightPeriod(30) },
                                        label = { Text(stringResource(R.string.insight_period_month), fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = AccentGreen.copy(alpha = 0.25f),
                                            selectedLabelColor = AccentGreen
                                        ),
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                // Dynamics table
                                if (dynamics.isNotEmpty()) {
                                    Column {
                                        dynamics.forEach { metric ->
                                            if (metric.section != null) {
                                                Text(
                                                    metric.section,
                                                    color = TextMuted,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
                                                )
                                            }
                                            DynamicsRow(metric = metric)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                }
                                // Divider
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(Color(0xFF2A2A2E))
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                // Insights as bullet points
                                if (insights.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        insights.forEach { text ->
                                            Row(modifier = Modifier.fillMaxWidth()) {
                                                Box(
                                                    modifier = Modifier
                                                        .padding(top = 7.dp, end = 8.dp)
                                                        .size(5.dp)
                                                        .background(
                                                            insightDialogColor.copy(alpha = 0.5f),
                                                            shape = CircleShape
                                                        )
                                                )
                                                StyledInsightText(text = text, bulletColor = insightDialogColor)
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                // Footer
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    state.insightDate?.let {
                                        Text(it, color = TextMuted, fontSize = 11.sp)
                                    }
                                }
                            } else {
                                Text(stringResource(R.string.dashboard_insight_no_data), color = TextMuted, fontSize = 13.sp)
                            }
                        }
                    }
                    state.pendingTripReset?.let { counter ->
                        TripResetConfirmation(counter, viewModel::cancelTripReset, viewModel::confirmTripReset)
                    }
                    // TRIP 1 popup
                    state.trip1?.let { t ->
                        if (state.trip1Expanded) TripCounterDialog(
                            stringResource(R.string.dashboard_trip1_label), t, state.currencySymbol,
                            mode = state.trip1AutoReset,
                            onModeChange = { viewModel.setTripAutoReset(1, it) },
                            onRequestReset = { viewModel.requestTripReset(1) },
                        ) { viewModel.toggleTripExpanded(1) }
                    }
                    // TRIP 2 popup
                    state.trip2?.let { t ->
                        if (state.trip2Expanded) TripCounterDialog(
                            stringResource(R.string.dashboard_trip2_label), t, state.currencySymbol,
                            mode = state.trip2AutoReset,
                            onModeChange = { viewModel.setTripAutoReset(2, it) },
                            onRequestReset = { viewModel.requestTripReset(2) },
                        ) { viewModel.toggleTripExpanded(2) }
                    }
                }
            }

            // RIGHT COLUMN — period filter + 4 cards + recent trips
            Column(
                modifier = Modifier.weight(0.6f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Period chips
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DashboardPeriodChip(stringResource(R.string.dashboard_period_day), state.period == DashboardPeriod.TODAY) { viewModel.setPeriod(DashboardPeriod.TODAY) }
                    DashboardPeriodChip(stringResource(R.string.dashboard_period_week), state.period == DashboardPeriod.WEEK) { viewModel.setPeriod(DashboardPeriod.WEEK) }
                    DashboardPeriodChip(stringResource(R.string.dashboard_period_month), state.period == DashboardPeriod.MONTH) { viewModel.setPeriod(DashboardPeriod.MONTH) }
                    DashboardPeriodChip(stringResource(R.string.dashboard_period_year), state.period == DashboardPeriod.YEAR) { viewModel.setPeriod(DashboardPeriod.YEAR) }
                    DashboardPeriodChip(stringResource(R.string.dashboard_period_all), state.period == DashboardPeriod.ALL) { viewModel.setPeriod(DashboardPeriod.ALL) }
                }

                // 4 stat cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.vehicleKind == VehicleKind.HYBRID) {
                        HybridStatCards(state)
                    } else {
                        StatCard(stringResource(R.string.dashboard_stat_distance), stringResource(R.string.dashboard_stat_km_value, state.totalKm), stringResource(R.string.dashboard_trip_count, state.tripCount), Color.White, Modifier.weight(1f))
                        StatCard(stringResource(R.string.dashboard_stat_energy), stringResource(R.string.dashboard_stat_kwh_value, state.totalKwh), null, TextPrimary, Modifier.weight(1f))
                        val consColor = if (state.avgConsumption > 0) consumptionColor(state.avgConsumption) else TextSecondary
                        StatCard(stringResource(R.string.dashboard_stat_consumption), if (state.avgConsumption > 0) "%.1f/100".format(state.avgConsumption) else "—", null, consColor, Modifier.weight(1f))
                        StatCard(stringResource(R.string.dashboard_stat_cost), "%.2f %s".format(state.totalCost, state.currencySymbol), null, TextPrimary, Modifier.weight(1f))
                    }
                }

                RecentTripsSection(state, onOpenTrips = onOpenTrips, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Charge/range lead; current-trip readings stay grouped below. */
@Composable
private fun LiveReadings(state: DashboardUiState) {
    val context = LocalContext.current
    val durationText by produceState(formatDurationShort(context, state.sessionStartedAt), state.sessionStartedAt) {
        while (true) {
            value = formatDurationShort(context, state.sessionStartedAt)
            delay(15_000L)
        }
    }
    val hybrid = state.vehicleKind == VehicleKind.HYBRID
    val rangeText = if (hybrid) state.evRangeKm?.let { "~$it" } ?: "—"
        else state.estimatedRangeKm?.let { "~${"%.0f".format(it)}" } ?: "—"
    val effectiveTrend = if (state.sessionStartedAt != null &&
        (state.tripDistanceKm ?: 0.0) < TRIP_DISTANCE_TREND_THRESHOLD_KM) Trend.NONE else state.consumptionTrend
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(rangeText, color = TextPrimary, style = MaterialTheme.typography.headlineLarge,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.dashboard_unit_km), color = TextSecondary,
                    style = MaterialTheme.typography.bodyLarge)
            }
            Text(stringResource(if (hybrid) R.string.dashboard_range_label_battery else R.string.dashboard_range_label),
                color = TextMuted, style = MaterialTheme.typography.labelMedium)
        }
        Text(stringResource(R.string.dashboard_current_trip), color = TextSecondary,
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CornerStat(Icons.Outlined.Route, formatTripKm(context, state.tripDistanceKm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (effectiveTrend != Trend.NONE) {
                    Icon(when (effectiveTrend) {
                        Trend.DOWN -> Icons.Outlined.TrendingDown
                        Trend.UP -> Icons.Outlined.TrendingUp
                        else -> Icons.Outlined.TrendingFlat
                    }, contentDescription = null,
                        tint = when (effectiveTrend) { Trend.DOWN -> AccentGreen; Trend.UP -> SocYellow; else -> TextSecondary },
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(state.consumption?.let { "%.1f".format(it) } ?: "—",
                        color = state.consumption?.let { consumptionColor(it) } ?: TextMuted,
                        style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.summary_unit_kwh_per_100km), color = TextMuted,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { CornerStat(Icons.Outlined.Schedule, durationText) }
            Text(state.odometer?.let { "%.1f km".format(it) } ?: "— km", color = TextSecondary,
                style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                CornerStat(Icons.Outlined.DirectionsCar, formatCabinOutside(state.insideTemp, state.exteriorTemp), iconLast = true)
            }
        }
    }
}

@Composable
internal fun RecentTripsSection(
    state: DashboardUiState,
    onOpenTrips: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dashboard_recent_trips_title), color = TextSecondary,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenTrips) { Text(stringResource(R.string.dashboard_view_trips)) }
        }
        // DM-i adds the "l" column between kWh and /100; EV keeps today's six weights.
        val showFuel = state.vehicleKind == VehicleKind.HYBRID
        val weights = com.bydmate.app.ui.trips.columnWeights(state.vehicleKind)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.dashboard_col_time), color = TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(weights[0]))
            Text(stringResource(R.string.dashboard_col_duration), color = TextMuted, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(weights[1]))
            Text(stringResource(R.string.dashboard_unit_km), color = TextMuted, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(weights[2]))
            Text(stringResource(R.string.dashboard_unit_kwh), color = TextMuted, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(weights[3]))
            if (showFuel) {
                Text(stringResource(R.string.dashboard_unit_liters), color = TextMuted, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End,
                    maxLines = 1, modifier = Modifier.weight(weights[4]))
            }
            Text(stringResource(R.string.dashboard_col_per100), color = TextMuted, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(weights[weights.size - 2]))
            Text(state.currencySymbol, color = TextMuted, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(weights.last()))
        }
        if (state.recentTrips.isNotEmpty()) {
            // The bottom padding matches the left column's, so the last row ends level
            // with the TRIP cards.
            WholeRowsColumn(
                rowSpacing = 4.dp,
                maxRows = DASHBOARD_TRIP_ROWS,
                modifier = Modifier.weight(1f).padding(bottom = 4.dp),
            ) {
                state.recentTrips.take(DASHBOARD_TRIP_ROWS).forEach { trip ->
                    TripCard(
                        trip = trip,
                        onClick = onOpenTrips,
                        currencySymbol = state.currencySymbol,
                        verticalPadding = 6.dp,
                        showFuel = showFuel,
                    )
                }
            }
        } else {
            PlaceholderText(text = stringResource(R.string.dashboard_empty_no_trips))
        }
    }
}

private val GaugeMaxSize = 180.dp

private const val DASHBOARD_TRIP_ROWS = 3

/**
 * Left column of Główna. [content] emits the gauge, the rows under it and the card stack, in
 * that order. Rows and cards keep their own height and the cards sit at the bottom; the gauge
 * takes what is left, between [gaugeMinSize] (its text still clear of the arc) and [GaugeMaxSize].
 * When even the smallest gauge does not fit the minimum height, the column grows taller than
 * it and the caller's scroll takes over.
 */
@Composable
private fun GaugeYieldingColumn(
    gaugeMinSize: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val free = Constraints(maxWidth = width)
        val rows = measurables[1].measure(free)
        val cards = measurables[2].measure(free)
        val target = constraints.minHeight
        val gaugeSize = (target - rows.height - cards.height)
            .coerceIn(ceil(gaugeMinSize.toPx()).toInt(), maxOf(gaugeMinSize, GaugeMaxSize).roundToPx())
            .coerceAtMost(width)
        val gauge = measurables[0].measure(Constraints.fixed(gaugeSize, gaugeSize))
        val needed = gaugeSize + rows.height + cards.height
        val height = constraints.constrainHeight(maxOf(target, needed))
        layout(width, height) {
            gauge.place((width - gaugeSize) / 2, 0)
            rows.place((width - rows.width) / 2, gaugeSize)
            cards.place((width - cards.width) / 2, height - cards.height)
        }
    }
}

/**
 * Vertical list of at most [maxRows] rows that only shows rows which fit completely within the
 * available height; a row cut in half by the bottom edge is never shown. When the list is full
 * (capped by [maxRows] or by the height), the height left over is shared out among the rows so
 * the last one ends exactly at the bottom edge. Rows keep [rowSpacing] between them.
 */
@Composable
private fun WholeRowsColumn(
    rowSpacing: Dp,
    maxRows: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Rows are measured twice: once at their natural height to pick the count, then at the fitted
    // height. A measurable can be measured only once per pass, hence two subcompositions.
    SubcomposeLayout(modifier) { constraints ->
        val spacingPx = rowSpacing.roundToPx()
        val width = constraints.maxWidth
        val natural = subcompose(WholeRowsSlot.Natural, content)
            .take(maxRows)
            .map { it.measure(Constraints(maxWidth = width)).height }
        val heights = fitWholeRows(natural, spacingPx, constraints.maxHeight, maxRows)
        val placeables = subcompose(WholeRowsSlot.Fitted, content)
            .zip(heights) { measurable, h -> measurable.measure(Constraints(maxWidth = width, minHeight = h, maxHeight = h)) }
        val usedHeight = heights.sum() + spacingPx * (heights.size - 1).coerceAtLeast(0)
        layout(width, constraints.constrainHeight(usedHeight)) {
            var y = 0
            placeables.forEach { placeable ->
                placeable.place(0, y)
                y += placeable.height + spacingPx
            }
        }
    }
}

private enum class WholeRowsSlot { Natural, Fitted }

/**
 * Heights of the rows [WholeRowsColumn] shows, given their [naturalHeights], the [spacing] between
 * them and the [availableHeight] ([Constraints.Infinity] when unbounded). Takes as many whole rows
 * as fit, at most [maxRows]. When the list is full ([maxRows] rows, or fewer because the height
 * ran out), the leftover is spread over the shown rows so they fill [availableHeight] exactly; the
 * first rows take the remainder pixels. A list shorter than [maxRows] that fits entirely keeps its
 * natural heights, and so does an unbounded one.
 */
internal fun fitWholeRows(naturalHeights: List<Int>, spacing: Int, availableHeight: Int, maxRows: Int): List<Int> {
    val candidates = naturalHeights.take(maxRows)
    if (availableHeight == Constraints.Infinity) return candidates
    var count = 0
    var used = 0
    for (height in candidates) {
        val next = used + (if (count > 0) spacing else 0) + height
        if (next > availableHeight) break
        used = next
        count++
    }
    if (count == 0) return emptyList()
    if (count == candidates.size && count < maxRows) return candidates
    val leftover = availableHeight - used
    val extra = leftover / count
    val remainder = leftover % count
    return candidates.take(count).mapIndexed { i, height -> height + extra + if (i < remainder) 1 else 0 }
}

@Composable
private fun TopBar(
    isServiceRunning: Boolean,
    vehicleDataConnected: Boolean,
    adbVerdict: AdbVerdict?,
    adbChecking: Boolean,
    onAdbTap: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "BYDMate",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )

        // Status side yields width to the brand: long verdict texts ellipsize instead of wrapping.
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // The ADB verdict takes the slot of «no vehicle data»: when both apply, only ADB shows.
            if (isServiceRunning && adbChecking) {
                Text(
                    stringResource(R.string.adb_verdict_checking),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else if (isServiceRunning && adbVerdict != null && adbVerdict != AdbVerdict.OK) {
                Text(
                    text = adbVerdictText(adbVerdict),
                    color = adbVerdictColor(adbVerdict),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clickable { onAdbTap() }
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else if (isServiceRunning && !vehicleDataConnected) {
                Text(
                    text = stringResource(R.string.dashboard_vehicle_data_offline),
                    color = SocYellow,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isServiceRunning) AccentGreen else TextMuted)
            )
            Text(
                text = if (isServiceRunning) stringResource(R.string.dashboard_status_online) else stringResource(R.string.dashboard_status_offline),
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
    }
}

// ============================================================================
// AI Insight card — text-based, different from value-pair cards
// ============================================================================

@Composable
private fun InsightCard(
    title: String?,
    summary: String?,
    borderColor: Color,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("✦", fontSize = 16.sp, color = if (title != null) borderColor else TextMuted)
            Spacer(modifier = Modifier.width(8.dp))
            if (title != null) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        color = borderColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (summary != null) {
                        Text(
                            summary,
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                Text(
                    stringResource(R.string.dashboard_insight_no_data),
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ============================================================================
// TRIP 1 / TRIP 2 counter button
// ============================================================================

@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TripCounterButton(
    label: String,
    ui: TripCounterUi?,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClickLabel = stringResource(R.string.dashboard_trip_reset_action),
            onLongClick = onLongClick,
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.0f".format(ui?.km ?: 0.0), color = TextPrimary,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.dashboard_unit_km), color = TextMuted, style = MaterialTheme.typography.labelMedium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.1f".format(ui?.kwh ?: 0.0), color = TextPrimary,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.dashboard_unit_kwh), color = TextMuted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BatteryCompactCard(
    sohText: String,
    sohColor: Color,
    tempText: String,
    tempColor: Color,
    voltageText: String,
    voltageColor: Color,
    cellDeltaText: String,
    cellDeltaColor: Color,
    insulationText: String,
    insulationColor: Color,
    summary: BatterySummary,
    borderColor: Color,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val statusIcon = when (summary) {
                BatterySummary.CRITICAL -> Icons.Outlined.ErrorOutline
                BatterySummary.WARNING -> Icons.Outlined.WarningAmber
                BatterySummary.UNAVAILABLE -> Icons.Outlined.HelpOutline
                BatterySummary.PARTIAL -> Icons.Outlined.Info
                BatterySummary.NO_WARNINGS -> Icons.Outlined.CheckCircle
            }
            val statusLabel = when (summary) {
                BatterySummary.CRITICAL -> R.string.dashboard_battery_status_critical
                BatterySummary.WARNING -> R.string.dashboard_battery_status_warning
                BatterySummary.UNAVAILABLE -> R.string.dashboard_battery_status_unavailable
                BatterySummary.PARTIAL -> R.string.dashboard_battery_status_partial
                BatterySummary.NO_WARNINGS -> R.string.dashboard_battery_status_clear
            }
            Icon(statusIcon, contentDescription = null, tint = borderColor, modifier = Modifier.size(20.dp))
            Text(stringResource(statusLabel), color = borderColor, style = MaterialTheme.typography.labelMedium)
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BatteryCell(value = sohText, label = "SoH", color = sohColor)
            BatteryCell(value = tempText, label = stringResource(R.string.dashboard_battery_temp_label), color = tempColor)
            BatteryCell(value = voltageText, label = stringResource(R.string.dashboard_battery_voltage_label), color = voltageColor)
            BatteryCell(value = cellDeltaText, label = stringResource(R.string.battery_health_cell_delta_label), color = cellDeltaColor)
            BatteryCell(value = insulationText, label = stringResource(R.string.dashboard_battery_insulation_label), color = insulationColor)
        }
    }
}

/** DM-i only: tank level and the car's range on fuel, same shape as [BatteryCompactCard]. */
@Composable
private fun FuelCompactCard(tankText: String, rangeText: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FuelCell(value = tankText, label = stringResource(R.string.dashboard_fuel_tank), color = TextPrimary)
            FuelCell(value = rangeText, label = stringResource(R.string.dashboard_fuel_range), color = TextPrimary)
        }
    }
}

@Composable
private fun FuelCell(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Fuel-card popup: the car's own readings only, nothing computed by the app. */
@Composable
private fun FuelDetailDialog(state: DashboardUiState, onEditPrice: () -> Unit, onDismiss: () -> Unit) {
    val km = stringResource(R.string.dashboard_unit_km)
    CardDetailDialog(title = null, borderColor = AccentOrange, onDismiss = onDismiss) {
        TripDetailRow(stringResource(R.string.dashboard_fuel_popup_tank),
            state.fuelPercent?.let { stringResource(R.string.dashboard_fuel_percent_value, it) } ?: "—")
        TripDetailRow(stringResource(R.string.dashboard_fuel_popup_fuel_range), state.fuelRangeKm?.let { "$it $km" } ?: "—")
        TripDetailRow(stringResource(R.string.dashboard_fuel_popup_ev_range), state.evRangeKm?.let { "$it $km" } ?: "—")
        val rpm = state.engineRpm
        when {
            rpm == null -> TripDetailRow(stringResource(R.string.dashboard_fuel_popup_engine_rpm), "—")
            rpm > 0 -> TripDetailRow(stringResource(R.string.dashboard_fuel_popup_engine_rpm),
                stringResource(R.string.dashboard_fuel_rpm_value, rpm), valueColor = AccentOrange)
            else -> TripDetailRow(stringResource(R.string.dashboard_fuel_popup_engine_rpm),
                stringResource(R.string.dashboard_fuel_engine_off), valueColor = TextMuted)
        }
        TripDetailRow(stringResource(R.string.dashboard_fuel_popup_mileage_hev), state.mileageHevKm?.let { "$it $km" } ?: "—")
        TripDetailRow(stringResource(R.string.dashboard_fuel_popup_mileage_ev), state.mileageEvKm?.let { "$it $km" } ?: "—")
        TripDetailRow(stringResource(R.string.dashboard_fuel_popup_fuel_total),
            state.fuelTotalL?.let { stringResource(R.string.dashboard_fuel_liters_value, it) } ?: "—")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dashboard_fuel_popup_price), color = TextSecondary, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.fuelPricePerLiter?.let { "%.2f %s".format(it, state.currencySymbol) } ?: "—",
                    color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.dashboard_fuel_price_edit), color = AccentGreen, fontSize = 13.sp,
                    modifier = Modifier.clickable { onEditPrice() })
            }
        }
        Text(stringResource(R.string.dashboard_fuel_footer), color = TextMuted, fontSize = 11.sp)
    }
}

/**
 * The four period cards on a hybrid (#184): every figure is over all km of the period, the
 * way the instrument cluster's «Accumulated AEC» counts, except litres per 100, which divide by
 * the km of the trips that know their litres. Missing sums leave their subtitle out.
 */
@Composable
private fun RowScope.HybridStatCards(state: DashboardUiState) {
    val tripCount = stringResource(R.string.dashboard_trip_count, state.tripCount)
    StatCard(
        stringResource(R.string.dashboard_stat_distance),
        stringResource(R.string.dashboard_stat_km_value, state.totalKm),
        state.periodHevKm?.let { stringResource(R.string.dashboard_distance_hev_subtitle, tripCount, it) } ?: tripCount,
        Color.White, Modifier.weight(1f), valueMaxLines = 1,
    )
    StatCard(
        stringResource(R.string.dashboard_stat_energy),
        stringResource(R.string.dashboard_stat_kwh_value, state.totalKwh),
        state.periodLiters?.let { stringResource(R.string.dashboard_energy_fuel_subtitle, it) },
        TextPrimary, Modifier.weight(1f), valueMaxLines = 1,
    )
    val litersPer100 = periodLitersPer100(state.periodLiters, state.periodLitersKm)
    if (litersPer100 != null) {
        StatCard(
            stringResource(R.string.dashboard_stat_consumption),
            stringResource(R.string.dashboard_consumption_fuel_value, litersPer100),
            if (state.avgConsumption > 0) stringResource(R.string.dashboard_consumption_elec_subtitle, state.avgConsumption) else null,
            TextPrimary, Modifier.weight(1f), valueMaxLines = 1,
        )
    } else {
        StatCard(
            stringResource(R.string.dashboard_stat_consumption),
            "—",
            if (state.avgConsumption > 0) "%.1f/100".format(state.avgConsumption) else null,
            TextSecondary, Modifier.weight(1f), valueMaxLines = 1,
        )
    }
    StatCard(
        stringResource(R.string.dashboard_stat_cost),
        "%.2f %s".format(state.totalCost, state.currencySymbol),
        state.periodFuelCost?.let {
            stringResource(R.string.dashboard_cost_split_subtitle, state.periodElecCost ?: 0.0, it)
        },
        TextPrimary, Modifier.weight(1f), valueMaxLines = 1,
    )
}

@Composable
private fun BatteryCell(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace)
        Text(label, color = TextMuted, style = MaterialTheme.typography.labelMedium)
    }
}

// ============================================================================
// TRIP counter detail popup helpers
// ============================================================================

@Composable
private fun TripDetailRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    labelColor: Color = TextSecondary,
    indent: Boolean = false,
    small: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = labelColor, fontSize = if (small) 12.sp else 13.sp,
            modifier = if (indent) Modifier.padding(start = 14.dp) else Modifier)
        Text(value, color = valueColor, fontSize = if (small) 12.sp else 13.sp,
            fontWeight = if (small) FontWeight.Normal else FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TripCounterDialog(
    label: String,
    ui: TripCounterUi,
    currencySymbol: String,
    mode: TripAutoResetMode,
    onModeChange: (TripAutoResetMode) -> Unit,
    onRequestReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    CardDetailDialog(title = null, borderColor = AccentGreen, onDismiss = onDismiss, dismissOnCardTap = false) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom) {
            Text(label, color = AccentGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (ui.resetTs == 0L) stringResource(R.string.dashboard_trip_since_all_time)
                else stringResource(R.string.dashboard_trip_since,
                    java.text.SimpleDateFormat("d MMMM", java.util.Locale.getDefault())
                        .format(java.util.Date(ui.resetTs)),
                    ((System.currentTimeMillis() - ui.resetTs) / 86_400_000L)),
                color = TextMuted, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%.1f".format(ui.km), color = TextPrimary, fontSize = 30.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.dashboard_unit_km), color = TextSecondary, fontSize = 14.sp)
        }
        val totalMin = ui.drivingMs / 60_000L
        val timeStr = if (totalMin >= 60)
            stringResource(R.string.dashboard_trip_time_hours_min, totalMin / 60, totalMin % 60)
        else stringResource(R.string.dashboard_trip_time_min, totalMin)
        TripDetailRow(stringResource(R.string.dashboard_trip_count_label), ui.tripCount.toString())
        TripDetailRow(stringResource(R.string.dashboard_trip_driving_time_label), timeStr)
        HorizontalDivider(color = CardBorder)
        TripDetailRow(stringResource(R.string.dashboard_trip_kwh_total_label),
            stringResource(R.string.dashboard_trip_kwh_value, "%.1f".format(ui.kwh)), valueColor = AccentGreen)
        TripDetailRow(stringResource(R.string.dashboard_trip_kwh_driving_label),
            stringResource(R.string.dashboard_trip_kwh_value, "%.1f".format(ui.drivingKwh)),
            labelColor = TextMuted, indent = true, small = true)
        TripDetailRow(stringResource(R.string.dashboard_trip_kwh_idle_label),
            stringResource(R.string.dashboard_trip_kwh_value, "%.1f".format(ui.idleKwh)),
            labelColor = TextMuted, indent = true, small = true)
        if (ui.km > 0.0) TripDetailRow(stringResource(R.string.dashboard_trip_avg_label),
            stringResource(R.string.dashboard_trip_avg_value, "%.1f".format(ui.kwh / ui.km * 100.0)))
        HorizontalDivider(color = CardBorder)
        TripDetailRow(stringResource(R.string.dashboard_trip_cost_label),
            "%.2f %s".format(ui.cost, currencySymbol))
        HorizontalDivider(color = CardBorder)
        Text(stringResource(R.string.dashboard_trip_auto_reset_label), color = TextSecondary, fontSize = 12.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TripAutoResetMode.entries.forEach { m ->
                val chipLabel = when (m) {
                    TripAutoResetMode.OFF -> R.string.dashboard_trip_auto_reset_off
                    TripAutoResetMode.ANY -> R.string.dashboard_trip_auto_reset_any
                    TripAutoResetMode.AC -> R.string.dashboard_trip_auto_reset_ac
                    TripAutoResetMode.DC -> R.string.dashboard_trip_auto_reset_dc
                    TripAutoResetMode.FULL -> R.string.dashboard_trip_auto_reset_full
                }
                TripAutoResetChip(stringResource(chipLabel), selected = m == mode) { onModeChange(m) }
            }
        }
        TextButton(onClick = onRequestReset, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dashboard_trip_reset_action))
        }
    }
}

@Composable
private fun DynamicsRow(metric: DynamicMetric) {
    val changeColor = when (metric.sentiment) {
        "good" -> AccentGreen
        "bad" -> SocRed
        else -> TextMuted
    }
    val arrow = when {
        metric.changePct == null -> ""
        metric.changePct > 0 -> "▲"
        metric.changePct < 0 -> "▼"
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            metric.label,
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.width(100.dp)
        )
        Text(
            metric.current,
            color = TextPrimary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
        if (metric.previous != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text("\u2190", color = TextMuted, style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                metric.previous,
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (metric.changePct != null) {
            Text(
                "$arrow${"%.0f".format(kotlin.math.abs(metric.changePct))}%",
                color = changeColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// Highlight numbers in insight text with amber color, bold text before first dash
@Composable
private fun StyledInsightText(text: String, bulletColor: Color) {
    val numberPattern = Regex("""(\d+[.,]\d+|\d+)(%| кВтч?/ч| кВтч?/100км| кВтч?| км/ч| км| мВ|°C)?""")
    val annotated = buildAnnotatedString {
        var lastEnd = 0
        // Bold: text before first " —" or " —"
        val dashIdx = text.indexOf(" — ").takeIf { it > 0 }
            ?: text.indexOf(" — ").takeIf { it > 0 }
        if (dashIdx != null && dashIdx < 40) {
            withStyle(SpanStyle(color = TextPrimary, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(0, dashIdx))
            }
            lastEnd = dashIdx
        }
        // Highlight numbers
        val remaining = text.substring(lastEnd)
        var rLastEnd = 0
        for (match in numberPattern.findAll(remaining)) {
            if (match.range.first > rLastEnd) {
                withStyle(SpanStyle(color = TextSecondary)) {
                    append(remaining.substring(rLastEnd, match.range.first))
                }
            }
            val num = match.value
            // Only highlight if it looks like a real metric (has digits)
            if (num.any { it.isDigit() } && num.length > 1) {
                withStyle(SpanStyle(color = SocYellow, fontFamily = FontFamily.Monospace, fontSize = 12.sp)) {
                    append(num)
                }
            } else {
                withStyle(SpanStyle(color = TextSecondary)) {
                    append(num)
                }
            }
            rLastEnd = match.range.last + 1
        }
        if (rLastEnd < remaining.length) {
            withStyle(SpanStyle(color = TextSecondary)) {
                append(remaining.substring(rLastEnd))
            }
        }
    }
    Text(annotated, fontSize = 13.sp, lineHeight = 19.sp)
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, fontSize = 14.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace)
    }
}

// ============================================================================
// Shared UI components
// ============================================================================

@Composable
private fun DashboardPeriodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentGreen,
            selectedLabelColor = NavyDark,
            containerColor = CardSurface,
            labelColor = TextSecondary
        ),
        border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
            borderColor = Color.Transparent,
            selectedBorderColor = Color.Transparent,
            enabled = true,
            selected = selected
        )
    )
}

// DashboardPeriodChip on an elevated container: the TRIP popup itself is CardSurface.
@Composable
private fun TripAutoResetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentGreen,
            selectedLabelColor = NavyDark,
            containerColor = CardSurfaceElevated,
            labelColor = TextSecondary
        ),
        border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
            borderColor = Color.Transparent,
            selectedBorderColor = Color.Transparent,
            enabled = true,
            selected = selected
        )
    )
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    subtitle: String?,
    accentColor: Color,
    modifier: Modifier = Modifier,
    valueMaxLines: Int = Int.MAX_VALUE,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, color = TextSecondary, style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center)
            Text(value, color = accentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, maxLines = valueMaxLines, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = TextMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PlaceholderText(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        fontWeight = FontWeight.Medium
    )
}

/**
 * Compact widget-style stat used in the top section of the dashboard left column.
 * Mirrors the IconText composable in FloatingWidgetView (icon muted gray + monospace value).
 * Set iconLast=true for the right-aligned variant where the value comes before the icon.
 */
/**
 * Cabin and outside temperature in the one slot the car icon already owns (#210): «22° / 8°»,
 * cabin first. Without an outside reading the slot shows the cabin alone, exactly as before,
 * so a car that does not report it loses nothing. Numbers only — the row carries no words.
 */
internal fun formatCabinOutside(inside: Int?, outside: Int?): String = when {
    inside == null && outside == null -> "—"
    outside == null -> "$inside°"
    inside == null -> "— / $outside°"
    else -> "$inside° / $outside°"
}

@Composable
private fun CornerStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    iconLast: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!iconLast) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary,
        )
        if (iconLast) {
            Spacer(Modifier.width(5.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Presentation entry point for the pending manual reset. */
@Composable
internal fun TripResetConfirmation(counter: Int, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val label = stringResource(if (counter == 1) R.string.dashboard_trip1_label else R.string.dashboard_trip2_label)
    AppAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.dashboard_trip_reset_title, label)) },
        text = { Text(stringResource(R.string.dashboard_trip_reset_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.dashboard_trip_reset_action)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.dashboard_trip_reset_cancel)) } },
    )
}

private fun batterySummaryColor(summary: BatterySummary): Color = when (summary) {
    BatterySummary.CRITICAL -> SocRed
    BatterySummary.WARNING -> SocYellow
    BatterySummary.UNAVAILABLE, BatterySummary.PARTIAL -> TextSecondary
    BatterySummary.NO_WARNINGS -> AccentGreen
}

@Composable
internal fun TripCounterRow(state: DashboardUiState, onToggle: (Int) -> Unit, onRequestReset: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TripCounterButton(stringResource(R.string.dashboard_trip1_label), state.trip1, Modifier.weight(1f),
            onClick = { onToggle(1) }, onLongClick = { onRequestReset(1) })
        TripCounterButton(stringResource(R.string.dashboard_trip2_label), state.trip2, Modifier.weight(1f),
            onClick = { onToggle(2) }, onLongClick = { onRequestReset(2) })
    }
}
