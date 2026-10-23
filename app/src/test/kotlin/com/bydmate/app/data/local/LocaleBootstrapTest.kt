package com.bydmate.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleBootstrapTest {

    @Test
    fun `startup gets Polish`() {
        assertEquals("pl", decideLanguage())
    }
}
