package com.bydmate.app.ui.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.ui.theme.AccentGreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class WidgetStyleTest {

    private val backdrops = listOf("white" to Color.White, "black" to Color.Black)

    private fun assertContrast(name: String, fg: Color) {
        for ((label, backdrop) in backdrops) {
            val ratio = contrastRatio(fg, surfaceOver(backdrop))
            assertTrue("$name over $label backdrop: contrast $ratio < 4.5", ratio >= 4.5)
        }
    }

    @Test fun `contrast helper matches WCAG reference values`() {
        assertEquals(21.0, contrastRatio(Color.White, Color.Black), 0.01)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 0.001)
    }

    @Test fun `surfaceOver composites the translucent surface on the backdrop`() {
        val onWhite = surfaceOver(Color.White)
        val onBlack = surfaceOver(Color.Black)
        assertEquals(1f, onWhite.alpha, 0.001f)
        assertTrue("surface over white must be lighter than over black", onWhite.red > onBlack.red)
        assertNotEquals(Color.White, onWhite)
        assertNotEquals(Color.Black, onBlack)
    }

    @Test fun `text primary has 4_5 contrast on worst backdrops`() =
        assertContrast("textPrimary", WidgetStyle.textPrimary)

    @Test fun `text secondary has 4_5 contrast on worst backdrops`() =
        assertContrast("textSecondary", WidgetStyle.textSecondary)

    @Test fun `warn has 4_5 contrast on worst backdrops`() =
        assertContrast("warn", WidgetStyle.warn)

    @Test fun `critical has 4_5 contrast on worst backdrops`() =
        assertContrast("critical", WidgetStyle.critical)

    @Test fun `surface is at least 90 percent opaque`() {
        assertTrue(WidgetStyle.SURFACE_ALPHA >= 0.9f)
    }

    @Test fun `OK and NO_DATA borders are the neutral border, never green`() {
        assertEquals(WidgetStyle.border, widgetBorderColor(Status.OK))
        assertEquals(WidgetStyle.border, widgetBorderColor(Status.NO_DATA))
        assertNotEquals(AccentGreen, widgetBorderColor(Status.OK))
        assertNotEquals(AccentGreen, widgetBorderColor(Status.NO_DATA))
    }

    @Test fun `WARN and CRIT borders use warn and critical`() {
        assertEquals(WidgetStyle.warn, widgetBorderColor(Status.WARN))
        assertEquals(WidgetStyle.critical, widgetBorderColor(Status.CRIT))
    }

    @Test fun `soc status boundaries`() {
        assertEquals(Status.CRIT, widgetSocStatus(14))
        assertEquals(Status.WARN, widgetSocStatus(15))
        assertEquals(Status.WARN, widgetSocStatus(29))
        assertEquals(Status.OK, widgetSocStatus(30))
        assertEquals(Status.NO_DATA, widgetSocStatus(null))
    }

    @Test fun `12V status boundaries`() {
        assertEquals(Status.CRIT, widgetVoltStatus(11.9))
        assertEquals(Status.WARN, widgetVoltStatus(12.0))
        assertEquals(Status.WARN, widgetVoltStatus(12.4))
        assertEquals(Status.OK, widgetVoltStatus(12.5))
        assertEquals(Status.OK, widgetVoltStatus(null))
    }

    @Test fun `widget footprint is unchanged`() {
        assertEquals(260, WidgetStyle.WIDTH_DP)
        assertEquals(108, WidgetStyle.HEIGHT_DP)
    }

    private fun assertWarningStrings(locale: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val warning = context.getString(R.string.widget_cd_warning)
        val critical = context.getString(R.string.widget_cd_critical)
        assertTrue("$locale widget_cd_warning is blank", warning.isNotBlank())
        assertTrue("$locale widget_cd_critical is blank", critical.isNotBlank())
        assertNotEquals("$locale warning and critical must differ", warning, critical)
    }

    @Config(qualifiers = "ru") @Test fun `warning strings ru`() = assertWarningStrings("ru")
    @Config(qualifiers = "en") @Test fun `warning strings en`() = assertWarningStrings("en")
    @Config(qualifiers = "pl") @Test fun `warning strings pl`() = assertWarningStrings("pl")
    @Config(qualifiers = "be") @Test fun `warning strings be`() = assertWarningStrings("be")
    @Config(qualifiers = "pt") @Test fun `warning strings pt`() = assertWarningStrings("pt")
    @Config(qualifiers = "zh") @Test fun `warning strings zh`() = assertWarningStrings("zh")
}
