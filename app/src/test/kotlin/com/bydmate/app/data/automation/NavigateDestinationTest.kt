package com.bydmate.app.data.automation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigateDestinationTest {

    @Test
    fun `valid raw destination remains unchanged in the check and mapper payload`() {
        val raw = "  Łódź, ul. Lecha i Marii  "

        val valid = NavDestinationRules.check(raw) as NavDestinationCheck.Valid
        val action = NavigateActionMapper.toActionDef(valid, go = true)
        val payload = JSONObject(action.payload!!)

        assertEquals(raw, valid.raw)
        assertEquals("navigate", action.kind)
        assertEquals(raw, payload.getString("query"))
        assertTrue(payload.getBoolean("go"))
        assertFalse(payload.has("shortcut"))
    }

    @Test
    fun `blank oversized and control-character destinations are invalid`() {
        assertInvalid("")
        assertInvalid("   ")
        assertInvalid("a".repeat(NavDestinationRules.MAX_QUERY_CHARS + 1))
        assertInvalid("ulica\u0000 82")
    }

    @Test
    fun `URI-looking destinations are invalid`() {
        assertInvalid("geo:52,21")
        assertInvalid("waze://trasa")
        assertInvalid("https://example.com")
    }

    @Test
    fun `ordinary Polish destinations including a colon remain valid`() {
        assertValid("Lecha i Marii")
        assertValid("ulica: 82")
    }

    private fun assertValid(raw: String) {
        val result = NavDestinationRules.check(raw)
        assertTrue("expected valid result for: $raw", result is NavDestinationCheck.Valid)
        assertEquals(raw, (result as NavDestinationCheck.Valid).raw)
    }

    private fun assertInvalid(raw: String) {
        assertTrue("expected invalid result for: $raw", NavDestinationRules.check(raw) is NavDestinationCheck.Invalid)
    }
}
