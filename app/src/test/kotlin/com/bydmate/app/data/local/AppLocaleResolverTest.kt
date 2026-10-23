package com.bydmate.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocaleResolverTest {

    @Test
    fun supported_regional_tags_resolve_to_base_language() {
        mapOf("en-US" to "en", "pt-BR" to "pt", "zh-CN" to "zh",
            "zh-Hant-TW" to "zh", "be-BY" to "be", "pl-PL" to "pl",
            "  EN-us  " to "en").forEach { (tag, expected) ->
            assertEquals(tag, expected, AppLocaleResolver.resolve(tag))
        }
    }

    @Test
    fun `missing Russian malformed and unsupported tags resolve to Polish`() {
        listOf(null, "ru", "ru-RU", "", "  ", "de", "not-a-language").forEach { stored ->
            assertEquals("pl", AppLocaleResolver.resolve(stored))
        }
    }

    @Test
    fun `each retained UI locale is preserved`() {
        listOf("pl", "en", "zh", "pt", "be").forEach { retained ->
            assertEquals(retained, AppLocaleResolver.resolve(retained))
        }
    }

    @Test
    fun `resolving a restored Russian value remains Polish after an earlier migration`() {
        assertEquals("pl", AppLocaleResolver.resolve("ru"))
        assertEquals("pl", AppLocaleResolver.resolve("pl"))
        assertEquals("pl", AppLocaleResolver.resolve("ru"))
    }
}
