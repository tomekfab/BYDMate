package com.bydmate.app.navdata

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavCaptureBridgeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = NavCaptureBridge.resetForTest()

    @Test
    fun `debug bridge exposes only the non-exported capture activity`() {
        val intent = NavCaptureBridge.captureActivityIntent(context)
        assertNotNull(intent)
        assertEquals(NavCaptureActivity::class.java.name, intent!!.component!!.className)
    }

    @Test
    fun `capture controls use Polish labels`() {
        val polishContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("pl"))
        })
        assertEquals("Rozpocznij", polishContext.getString(R.string.nav_capture_start))
        assertEquals("Zapisz lokalne przechwycenie", polishContext.getString(R.string.nav_capture_save))
        assertEquals("Skręt", polishContext.getString(R.string.nav_capture_marker_turn))
    }

    @Test
    fun `fixed marker reaches the backend without arbitrary text`() {
        var captured: Pair<String, NavCaptureMarker>? = null
        NavCaptureBridge.replaceForTest(object : NavCaptureBackend {
            override fun notification(record: NavCaptureRecord) = Unit
            override fun accessibility(record: NavCaptureRecord) = Unit
            override fun captureActivityIntent(context: Context) = null
            override fun marker(packageName: String, marker: NavCaptureMarker) { captured = packageName to marker }
        })

        recordNavCaptureMarker("com.waze", NavCaptureMarker.REROUTE)

        assertEquals("com.waze" to NavCaptureMarker.REROUTE, captured)
    }

    @Test
    fun `marker controls use the fixed supported state set`() {
        assertEquals(
            setOf("guiding", "foreground", "background", "turn", "reroute", "arrived", "cancel"),
            NavCaptureMarker.entries.map { it.wireName }.toSet(),
        )
    }

    @Test
    fun `bridge copies primitive notification data and contains backend exceptions`() {
        val records = mutableListOf<NavCaptureRecord>()
        NavCaptureBridge.replaceForTest(object : NavCaptureBackend {
            override fun notification(record: NavCaptureRecord) { records += record; error("capture failure") }
            override fun accessibility(record: NavCaptureRecord) = Unit
            override fun captureActivityIntent(context: Context) = null
        })

        NavCaptureNotificationCallbacks.onPosted(NavCaptureNotification(
            packageName = "com.google.android.apps.maps",
            id = 8,
            channelId = "nav",
            title = StringBuilder("private street"),
            text = StringBuilder("turn right"),
            extras = mapOf("count" to 2, "unsupported" to Any()),
        ))

        assertEquals(1, records.size)
        assertEquals("private street", records.single().title)
        assertEquals(mapOf("count" to "2"), records.single().extras)
        assertFalse(records.single().containsFrameworkObject)
    }

    @Test
    fun `ordinary diagnostics redact raw route text`() {
        assertNull(navCaptureOrdinaryDiagnostics.firstOrNull { it.contains("Aleje Jerozolimskie") })
    }
}
