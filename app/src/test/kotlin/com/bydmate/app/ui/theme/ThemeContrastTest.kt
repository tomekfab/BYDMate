package com.bydmate.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG 1.4.3: small labels need 4.5:1 against the surface they render on. */
class ThemeContrastTest {
    private val surfaces = listOf(
        "CardSurface" to CardSurface,
        "CardSurfaceElevated" to CardSurfaceElevated,
        "NavyDark" to NavyDark,
        "NavyDeep" to NavyDeep,
    )

    @Test fun `muted text remains readable on all content surfaces`() {
        surfaces.forEach { (name, surface) -> assertTextContrast("TextMuted on $name", TextMuted, surface) }
    }

    @Test fun `secondary text remains readable on all content surfaces`() {
        surfaces.forEach { (name, surface) -> assertTextContrast("TextSecondary on $name", TextSecondary, surface) }
    }

    @Test fun `primary text remains readable on all content surfaces`() {
        surfaces.forEach { (name, surface) -> assertTextContrast("TextPrimary on $name", TextPrimary, surface) }
    }

    @Test fun `active accent text remains readable on all content surfaces`() {
        surfaces.forEach { (name, surface) -> assertTextContrast("AccentGreen on $name", AccentGreen, surface) }
    }

    @Test fun `primary button text remains readable on its fill`() {
        // Theme.kt sets onPrimary to NavyDark and primary to AccentGreen.
        assertTextContrast("primary button", NavyDark, AccentGreen)
    }

    @Test fun `selected chip text remains readable on its actual fill`() {
        // Dashboard, Trips, Charges, Welcome and Settings explicitly set this foreground.
        assertTextContrast("selected chip", NavyDark, AccentGreen)
    }

    @Test fun `accent labels remain readable on translucent selected chips`() {
        // Dashboard insight chips are 25% accent; Trips metric chips are 15%, both on CardSurface.
        listOf(0.25f, 0.15f).forEach { alpha ->
            val renderedFill = AccentGreen.copy(alpha = alpha).compositeOver(CardSurface)
            assertTextContrast("accent label on $alpha selected fill", AccentGreen, renderedFill)
        }
    }

    @Test fun `warning text remains readable on all content surfaces`() {
        listOf("SocRed" to SocRed, "ConsumptionBad" to ConsumptionBad).forEach { (token, foreground) ->
            surfaces.forEach { (name, surface) -> assertTextContrast("$token on $name", foreground, surface) }
        }
    }

    private fun assertTextContrast(name: String, foreground: Color, background: Color) {
        val foregroundLuminance = luminance(foreground)
        val backgroundLuminance = luminance(background)
        val ratio = (maxOf(foregroundLuminance, backgroundLuminance) + 0.05) /
            (minOf(foregroundLuminance, backgroundLuminance) + 0.05)
        assertTrue("$name contrast $ratio must be >= 4.5:1", ratio >= 4.5)
    }

    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double = channel.toDouble().let {
            if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
