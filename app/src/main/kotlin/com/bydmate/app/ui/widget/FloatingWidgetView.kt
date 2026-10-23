package com.bydmate.app.ui.widget

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Battery6Bar
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.TrendingFlat
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.domain.calculator.Trend
import com.bydmate.app.voice.VoiceStage
import com.bydmate.app.voice.VoiceStageLabels
import com.bydmate.app.voice.VoiceStatus
import com.bydmate.app.voice.voiceStatusDescription
import kotlinx.coroutines.delay

/**
 * Car-matching layout — [WidgetStyle.WIDTH_DP] × [WidgetStyle.HEIGHT_DP] dp, 3 rows, SOC row vertically centered.
 *
 * Row 1 (top, service, three content-sized slots):
 *   left — trip duration (⏱), center — trip distance (🗺 route), right — range per 1% SOC (km/%). 14sp.
 * Row 2 (center, main): SOC% (20sp) · range km (30sp, unit 16sp secondary) · consumption + trend (18sp).
 * Row 3 (bottom, service): cabin temp (🚗), battery temperature (🔋), 12V (⚡) — 14sp.
 *
 * Neutral by default: amber/coral appear only for a real warning (SOC or 12 V below the
 * [widgetSocStatus] / [widgetVoltStatus] thresholds), and a warning also draws an icon so it is
 * never color-only. Numbers use sans-serif with tabular figures (`tnum`).
 *
 * DM-i hybrid (#184, [kind] HYBRID): the top right slot shows the tank level, the middle right
 * slot the car's range on fuel; [rangeKm] then carries the car's range on battery.
 */
@Composable
fun FloatingWidgetView(
    soc: Int?,
    rangeKm: Double?,
    consumption: Double?,
    trend: Trend,
    sessionStartedAt: Long?,
    tripDistanceKm: Double?,
    insideTemp: Int?,
    batTemp: Int?,
    voltage12v: Double?,
    alpha: Float,
    scaleFactor: Float = 1.0f,
    listening: Boolean = false,
    voice: VoiceStatus = VoiceStatus.IDLE,
    kind: VehicleKind = VehicleKind.EV,
    fuelPercent: Int? = null,
    fuelRangeKm: Int? = null,
) {
    val showFuel = widgetShowsFuel(kind)
    val status = widgetStatus(soc, voltage12v)
    val borderColor = widgetBorderColor(status)
    val snakeColor = when (status) {
        Status.WARN -> WidgetStyle.warn
        Status.CRIT -> WidgetStyle.critical
        else -> WidgetStyle.textPrimary
    }
    val shape = RoundedCornerShape(WidgetStyle.CORNER_DP.dp)

    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density * scaleFactor,
        fontScale = baseDensity.fontScale,
    )

    // Snake border while a continuous voice session (Wave B) is listening; static border
    // otherwise. The snake turns amber/coral on a real warning, else it is the neutral text color.
    val mode = widgetPanelMode(listening, voice)
    val borderModifier = if (mode == WidgetPanelMode.DATA_LISTENING) {
        val phase = rememberSnakePhase()
        Modifier.drawBehind { drawSnakeBorder(color = snakeColor, phase = phase) }
    } else {
        Modifier.border(1.dp, borderColor, shape)
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        Column(
            modifier = Modifier
                .alpha(alpha.coerceIn(0.3f, 1.0f))
                .size(width = WidgetStyle.WIDTH_DP.dp, height = WidgetStyle.HEIGHT_DP.dp)
                .shadow(elevation = 6.dp, shape = shape)
                .background(WidgetStyle.surface.copy(alpha = WidgetStyle.SURFACE_ALPHA), shape)
                .then(borderModifier)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (mode == WidgetPanelMode.VOICE) {
                VoiceStatusPanel(voice)
            } else {
                RowTrip(
                    sessionStartedAt = sessionStartedAt,
                    tripDistanceKm = tripDistanceKm,
                    soc = soc,
                    rangeKm = rangeKm,
                    fuelPercent = if (showFuel) fuelPercent else null,
                    showFuel = showFuel,
                )
                WidgetDivider()
                // First 300 m of an active session: the trend stat is still based on the
                // PREVIOUS trip's buffer (current trip hasn't moved enough to enter the
                // 2-km short window yet). A confident DOWN/UP arrow here would imply
                // "your driving right now is going green/red", which misleads the user
                // when they've just started or are idle. Suppress the trend until they've
                // actually driven 300 m — by then the buffer has fresh rows for this trip.
                // The numeric value still renders (Trend.NONE colors it secondary gray) so
                // the user can still eyeball range, just without a confident verdict.
                val effectiveTrend = if (
                    sessionStartedAt != null &&
                    (tripDistanceKm ?: 0.0) < TRIP_DISTANCE_TREND_THRESHOLD_KM
                ) Trend.NONE else trend
                RowEnergy(
                    soc = soc, rangeKm = rangeKm, consumption = consumption, trend = effectiveTrend,
                    fuelRangeKm = if (showFuel) fuelRangeKm else null, showFuel = showFuel,
                )
                WidgetDivider()
                RowService(insideTemp = insideTemp, batTemp = batTemp, voltage12v = voltage12v)
            }
        }
    }
}

internal const val TRIP_DISTANCE_TREND_THRESHOLD_KM = 0.3

// ---- Voice status panel (command progress, replaces the data rows) ----

/** Same footprint as the data layout. Icon + label say the stage; colour only adds to it. */
@Composable
private fun VoiceStatusPanel(voice: VoiceStatus) {
    val failure = voice.stage.isVoiceWarning()
    val color = if (failure) WidgetStyle.warn else WidgetStyle.textPrimary
    Column(
        modifier = Modifier
            .fillMaxSize()
            .semantics(mergeDescendants = true) {
                contentDescription = voiceStatusDescription(voice)
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = voiceStageIcon(voice.stage),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = VoiceStageLabels.of(voice.stage, voice.language),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (voice.speaking) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Outlined.VolumeUp,
                    contentDescription = null,
                    tint = WidgetStyle.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        WidgetDivider()
        voice.heard?.let { heard ->
            Text(
                text = heard,
                fontSize = 16.sp,
                color = WidgetStyle.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun VoiceStage.isVoiceWarning(): Boolean =
    this == VoiceStage.REFUSED || this == VoiceStage.NOT_UNDERSTOOD || this == VoiceStage.FAILED

private fun voiceStageIcon(stage: VoiceStage): ImageVector = when (stage) {
    VoiceStage.PREPARING -> Icons.Outlined.HourglassEmpty
    VoiceStage.PROCESSING -> Icons.Outlined.Autorenew
    VoiceStage.RUNNING_COMMAND -> Icons.Outlined.Bolt
    VoiceStage.WAITING_ASSISTANT -> Icons.Outlined.ChatBubbleOutline
    VoiceStage.STARTED -> Icons.Outlined.PlayCircleOutline
    VoiceStage.CONFIRM_ON_SCREEN -> Icons.Outlined.TouchApp
    VoiceStage.DONE, VoiceStage.ANSWERED -> Icons.Outlined.CheckCircleOutline
    VoiceStage.REFUSED -> Icons.Outlined.Block
    VoiceStage.NOT_UNDERSTOOD -> Icons.Outlined.HelpOutline
    VoiceStage.FAILED -> Icons.Outlined.ErrorOutline
    // IDLE and LISTENING never reach the panel (widgetPanelMode keeps the data layout).
    VoiceStage.IDLE, VoiceStage.LISTENING -> Icons.Outlined.CheckCircleOutline
}

// ---- Snake border (Wave B listening indication) ----

private const val SNAKE_PERIOD_MS = 1200
private const val SNAKE_FRACTION = 0.35f
private val SNAKE_STROKE_WIDTH = 2.dp
private val SNAKE_CORNER_RADIUS = WidgetStyle.CORNER_DP.dp

/** Loops phase 0f..1f every [SNAKE_PERIOD_MS] -- the moving segment's position along the border. */
@Composable
private fun rememberSnakePhase(): Float {
    val transition = rememberInfiniteTransition(label = "widgetSnake")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(SNAKE_PERIOD_MS, easing = LinearEasing)),
        label = "widgetSnakePhase",
    )
    return phase
}

/** Draws a dim full outline plus a brighter moving segment on top, tracing the same rounded-rect
 *  the static border() would have drawn. Pure DrawScope logic (no Compose state) so it only needs
 *  [phase] as input. */
private fun DrawScope.drawSnakeBorder(color: Color, phase: Float) {
    val strokeWidthPx = SNAKE_STROKE_WIDTH.toPx()
    val cornerPx = SNAKE_CORNER_RADIUS.toPx()
    val path = Path().apply {
        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(cornerPx)))
    }
    val measure = PathMeasure().apply { setPath(path, forceClosed = true) }
    val length = measure.length

    drawPath(path, color = color.copy(alpha = 0.25f), style = Stroke(width = strokeWidthPx))

    val (start, end) = snakeSegment(phase, length, SNAKE_FRACTION)
    val segment = Path()
    if (end >= start) {
        measure.getSegment(start, end, segment, true)
    } else {
        // Wrapped past the path's end -- draw tail then head so the segment stays visually
        // continuous across the 0-length seam instead of jumping.
        measure.getSegment(start, length, segment, true)
        measure.getSegment(0f, end, segment, true)
    }
    drawPath(segment, color = color, style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round))
}

/** Pure fractional-position math, kept separate from DrawScope so it's unit-testable without a
 *  Compose runtime. Returns (start, end) offsets along a path of [length], both in [0, length];
 *  end < start signals the segment wrapped past the path's end (see drawSnakeBorder). */
internal fun snakeSegment(phase: Float, length: Float, fraction: Float): Pair<Float, Float> {
    if (length <= 0f) return 0f to 0f
    val start = phase.mod(1f) * length
    val segmentLength = fraction * length
    val rawEnd = start + segmentLength
    val end = if (rawEnd > length) rawEnd - length else rawEnd
    return start to end
}

@Composable
private fun RowEnergy(
    soc: Int?,
    rangeKm: Double?,
    consumption: Double?,
    trend: Trend,
    fuelRangeKm: Int? = null,
    showFuel: Boolean = false,
) {
    val socStatus = widgetSocStatus(soc)
    val socColor = when {
        soc == null -> WidgetStyle.textSecondary
        else -> statusTextColor(socStatus)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = soc?.let { "$it%" } ?: "—",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                style = WidgetNumberStyle,
                color = socColor,
            )
            MetricWarning(socStatus)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = rangeKm?.let { "~${"%.0f".format(it)}" } ?: "~—",
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                style = WidgetNumberStyle,
                color = WidgetStyle.textPrimary,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = stringResource(R.string.widget_unit_km),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                style = WidgetNumberStyle,
                color = WidgetStyle.textSecondary,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        if (showFuel) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.LocalGasStation,
                    contentDescription = null,
                    tint = WidgetStyle.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = widgetFuelRangeText(fuelRangeKm),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    style = WidgetNumberStyle,
                    color = WidgetStyle.textPrimary,
                    maxLines = 1,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Neutral arrow: its shape alone shows the direction, color never does.
                if (trend != Trend.NONE) {
                    Icon(
                        imageVector = when (trend) {
                            Trend.DOWN -> Icons.Outlined.TrendingDown
                            Trend.UP -> Icons.Outlined.TrendingUp
                            else -> Icons.Outlined.TrendingFlat
                        },
                        contentDescription = null,
                        tint = WidgetStyle.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    text = consumption?.let { "%.1f".format(it) } ?: "—",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    style = WidgetNumberStyle,
                    color = if (trend == Trend.NONE) WidgetStyle.textSecondary else WidgetStyle.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun RowTrip(
    sessionStartedAt: Long?,
    tripDistanceKm: Double?,
    soc: Int?,
    rangeKm: Double?,
    fuelPercent: Int? = null,
    showFuel: Boolean = false,
) {
    val context = LocalContext.current
    val durationText by produceState(initialValue = formatDurationShort(context, sessionStartedAt), sessionStartedAt) {
        while (true) {
            value = formatDurationShort(context, sessionStartedAt)
            delay(15_000L)
        }
    }
    // Let each value use its actual width. Equal thirds wrap the distance at larger
    // system font scales, consuming the height needed by the bottom service row.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconText(icon = Icons.Outlined.Schedule, text = durationText)
        IconText(icon = Icons.Outlined.Route, text = formatTripKm(context, tripDistanceKm))
        if (showFuel) {
            IconText(icon = Icons.Outlined.LocalGasStation, text = widgetFuelPercentText(fuelPercent))
        } else {
            IconText(
                icon = Icons.Outlined.Speed,
                text = formatKmPerPercent(context, widgetKmPerPercent(rangeKm, soc)),
            )
        }
    }
}

@Composable
private fun RowService(
    insideTemp: Int?,
    batTemp: Int?,
    voltage12v: Double?,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconText(icon = Icons.Outlined.DirectionsCar, text = insideTemp?.let { "$it°" } ?: "—")
        IconText(icon = Icons.Outlined.Battery6Bar, text = batTemp?.let { "$it°" } ?: "—")
        IconText(
            icon = Icons.Outlined.Bolt,
            text = voltage12v?.let { context.getString(R.string.widget_voltage_value, it) } ?: "—",
            textColor = statusTextColor(widgetVoltStatus(voltage12v)),
            warningStatus = widgetVoltStatus(voltage12v),
        )
    }
}

/** Icon secondary, value primary (or a status color), 14sp. */
@Composable
private fun IconText(
    icon: ImageVector,
    text: String,
    textColor: Color = WidgetStyle.textPrimary,
    warningStatus: Status = Status.OK,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = WidgetStyle.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            style = WidgetNumberStyle,
            color = textColor,
        )
        MetricWarning(warningStatus)
    }
}

/** Keep a non-color warning beside the value that caused it. */
@Composable
private fun MetricWarning(status: Status) {
    if (status != Status.WARN && status != Status.CRIT) return
    Spacer(Modifier.width(3.dp))
    Icon(
        imageVector = Icons.Outlined.WarningAmber,
        contentDescription = stringResource(
            if (status == Status.CRIT) R.string.widget_cd_critical else R.string.widget_cd_warning,
        ),
        tint = statusTextColor(status),
        modifier = Modifier.size(14.dp),
    )
}

@Composable
private fun WidgetDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(WidgetStyle.divider),
    )
}

/** Sans-serif with tabular figures, so digits keep a fixed width as values change. */
private val WidgetNumberStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontFeatureSettings = "tnum",
)

/** Neutral text unless the status is a real warning: amber for WARN, coral for CRIT. */
private fun statusTextColor(status: Status): Color = when (status) {
    Status.WARN -> WidgetStyle.warn
    Status.CRIT -> WidgetStyle.critical
    else -> WidgetStyle.textPrimary
}

internal fun formatDurationShort(context: Context, sessionStartedAt: Long?): String {
    if (sessionStartedAt == null) return "—"
    val elapsed = System.currentTimeMillis() - sessionStartedAt
    val totalMin = (elapsed / 60_000L).toInt().coerceAtLeast(0)
    val hours = totalMin / 60
    val minutes = totalMin % 60
    // Compact form in hours mode ("1 h 25 min") keeps label narrow enough to fit in
    // the top row's 1/3-width slot alongside trip distance and cabin temp.
    return if (hours > 0) {
        context.getString(R.string.widget_duration_hours, hours, minutes)
    } else {
        context.getString(R.string.widget_duration_minutes, minutes)
    }
}

internal fun formatTripKm(context: Context, km: Double?): String {
    if (km == null || km.isNaN() || km.isInfinite() || km < 0.0) return "—"
    return context.getString(R.string.widget_trip_km_value, km)
}

/**
 * Range-per-percent: how many km the current range estimate buys per 1% SOC.
 * Reuses the already-computed [rangeKm] (same trend-aware logic feeding the
 * big number) divided by [soc] — no separate recomputation. Returns null
 * (rendered as "—") for missing/zero SOC or a non-finite range.
 */
internal fun widgetKmPerPercent(rangeKm: Double?, soc: Int?): Double? {
    if (rangeKm == null || soc == null || soc <= 0) return null
    if (rangeKm.isNaN() || rangeKm.isInfinite() || rangeKm < 0.0) return null
    return rangeKm / soc
}

/** The fuel slots replace km/% and the consumption only on a confirmed hybrid. */
internal fun widgetShowsFuel(kind: VehicleKind): Boolean = kind == VehicleKind.HYBRID

/** Top right slot on a hybrid: the tank level as the car reports it. */
internal fun widgetFuelPercentText(fuelPercent: Int?): String = fuelPercent?.let { "$it%" } ?: "—"

/** Middle right slot on a hybrid: the car's range on fuel, km without the unit. */
internal fun widgetFuelRangeText(fuelRangeKm: Int?): String = fuelRangeKm?.toString() ?: "—"

internal fun formatKmPerPercent(context: Context, value: Double?): String {
    if (value == null) return "—"
    return context.getString(R.string.widget_km_per_percent, value)
}

// ---- Status model (covered by WidgetStatusTest) ----

internal enum class Status { OK, WARN, CRIT, NO_DATA }

internal fun widgetStatus(soc: Int?, v12: Double?): Status {
    if (soc == null && v12 == null) return Status.NO_DATA
    val socStatus = widgetSocStatus(soc)
    val vStatus = widgetVoltStatus(v12)
    return listOf(socStatus, vStatus)
        .filter { it != Status.NO_DATA }
        .maxByOrNull { severity(it) }
        ?: Status.NO_DATA
}

internal fun widgetSocStatus(soc: Int?): Status = when {
    soc == null -> Status.NO_DATA
    soc < 15 -> Status.CRIT
    soc < 30 -> Status.WARN
    else -> Status.OK
}

internal fun widgetVoltStatus(v12: Double?): Status = when {
    v12 == null -> Status.OK
    v12 < 12.0 -> Status.CRIT
    v12 < 12.5 -> Status.WARN
    else -> Status.OK
}

private fun severity(s: Status): Int = when (s) {
    Status.NO_DATA -> -1
    Status.OK -> 0
    Status.WARN -> 1
    Status.CRIT -> 2
}
