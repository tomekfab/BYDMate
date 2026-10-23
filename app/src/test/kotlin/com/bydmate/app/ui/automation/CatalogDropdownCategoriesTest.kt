package com.bydmate.app.ui.automation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The catalog opens collapsed except where the driver already is — otherwise the list of
 * commands is a page they have to scroll through to find anything.
 */
class CatalogDropdownCategoriesTest {

    private val items = listOf("Szyba kierowcy", "Szyba pasażera", "Klimatyzacja", "Bagażnik")
    private val categories = listOf("Szyby", "Szyby", "Klimatyzacja", "Nadwozie")

    @Test
    fun `only the category holding the current pick starts open`() {
        assertEquals(setOf("Klimatyzacja"), expandedCategoriesFor("Klimatyzacja", items, categories))
        assertEquals(setOf("Szyby"), expandedCategoriesFor("Szyba pasażera", items, categories))
    }

    @Test
    fun `a selection outside the catalog opens nothing`() {
        assertEquals(emptySet<String>(), expandedCategoriesFor("", items, categories))
        assertEquals(emptySet<String>(), expandedCategoriesFor("发送CAN 123", items, categories))
    }

    @Test
    fun `an empty catalog opens nothing`() {
        assertEquals(emptySet<String>(), expandedCategoriesFor("Bagażnik", emptyList(), emptyList()))
    }
}
