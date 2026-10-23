package com.bydmate.app.voice.openclaw

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenClawPrefsTest {
    private lateinit var prefs: SharedPreferences
    private val ownerGateway = "wss://bobs-mac-mini-openclaw.tail3549c7.ts.net/"

    @Before fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("openclaw_prefs_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test fun `fresh install offers the owner gateway without enabling online access`() {
        val store = SharedPreferencesOpenClawPrefs(prefs)
        assertEquals(ownerGateway, store.endpointText())
        assertFalse(store.isEnabled())
    }

    @Test fun `existing empty endpoint gets the owner gateway after update`() {
        prefs.edit().putString("endpoint", "  ").commit()
        assertEquals(ownerGateway, SharedPreferencesOpenClawPrefs(prefs).endpointText())
    }

    @Test fun `saved custom gateway survives restart`() {
        SharedPreferencesOpenClawPrefs(prefs).setEndpointText(" wss://custom.example/ ")
        assertEquals("wss://custom.example/", SharedPreferencesOpenClawPrefs(prefs).endpointText())
    }
}
