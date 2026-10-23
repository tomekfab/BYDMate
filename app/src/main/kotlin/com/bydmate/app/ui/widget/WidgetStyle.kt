package com.bydmate.app.ui.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.bydmate.app.voice.VoiceStage
import com.bydmate.app.voice.VoiceStatus
import kotlin.math.pow

/**
 * Visual tokens for the floating widget, matched to the car UI: warm dark surface, soft neutral
 * border, amber/coral only for real warnings. The fixed footprint is load-bearing: WidgetController
 * sizes the window, the drag clamp and the expanded-button window from [WIDTH_DP] x [HEIGHT_DP].
 *
 * The surface is [SURFACE_ALPHA] opaque, so text contrast is checked against the worst backdrops
 * (pure white and pure black) through [surfaceOver] — see WidgetStyleTest.
 */
internal object WidgetStyle {
    const val WIDTH_DP = 260
    const val HEIGHT_DP = 108
    const val CORNER_DP = 16
    const val SURFACE_ALPHA = 0.92f

    val surface = Color(0xFF2B2A28)
    val border = Color(0x26FFFFFF)
    val divider = Color(0x1AFFFFFF)
    val textPrimary = Color(0xFFF4F1EC)
    val textSecondary = Color(0xFFB9B4AC)
    val warn = Color(0xFFF2B53F)
    val critical = Color(0xFFFF8A80)
}

/** What the widget body shows: vehicle data, vehicle data with the listening snake, or the voice status panel. */
internal enum class WidgetPanelMode { DATA, DATA_LISTENING, VOICE }

/**
 * VOICE for every busy and terminal stage, so vehicle data is hidden while a command is in flight
 * and for the terminal dwell. DATA_LISTENING while the session listens (snake border), else DATA.
 */
internal fun widgetPanelMode(listening: Boolean, voice: VoiceStatus): WidgetPanelMode = when {
    voice.stage == VoiceStage.PREPARING ||
        voice.stage == VoiceStage.PROCESSING ||
        voice.stage == VoiceStage.RUNNING_COMMAND ||
        voice.stage == VoiceStage.WAITING_ASSISTANT ||
        voice.stage.isTerminal -> WidgetPanelMode.VOICE
    voice.stage == VoiceStage.LISTENING ||
        (voice.stage == VoiceStage.IDLE && listening) -> WidgetPanelMode.DATA_LISTENING
    else -> WidgetPanelMode.DATA
}

/** Neutral for OK and NO_DATA; amber or coral only for a real warning. Never green. */
internal fun widgetBorderColor(status: Status): Color = when (status) {
    Status.OK, Status.NO_DATA -> WidgetStyle.border
    Status.WARN -> WidgetStyle.warn
    Status.CRIT -> WidgetStyle.critical
}

/** WCAG 2 contrast ratio (1..21). A translucent [foreground] is composited over [background] first. */
internal fun contrastRatio(foreground: Color, background: Color): Double {
    val fg = foreground.compositeOver(background)
    val a = relativeLuminance(fg)
    val b = relativeLuminance(background)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

/** The opaque colour the translucent widget surface shows over [backdrop]. */
internal fun surfaceOver(backdrop: Color): Color =
    WidgetStyle.surface.copy(alpha = WidgetStyle.SURFACE_ALPHA).compositeOver(backdrop)

private fun relativeLuminance(c: Color): Double =
    0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)

private fun linear(channel: Float): Double {
    val v = channel.toDouble()
    return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}
