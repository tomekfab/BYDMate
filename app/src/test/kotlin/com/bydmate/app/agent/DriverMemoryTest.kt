package com.bydmate.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory mode (prefs = null): the storage layer is SharedPreferences-only glue. */
class DriverMemoryTest {

    private fun memory() = DriverMemory(prefs = null)

    @Test fun remember_trims_and_collapses_whitespace() {
        val mem = memory()
        assertTrue(mem.remember("  Kierowca   ma na imię\nAndrzej  ") is RememberResult.Stored)
        assertEquals(listOf("Kierowca ma na imię Andrzej"), mem.facts())
    }

    @Test fun remember_caps_fact_length() {
        val mem = memory()
        mem.remember("a".repeat(DriverMemory.MAX_FACT_CHARS + 40))
        assertEquals(DriverMemory.MAX_FACT_CHARS, mem.facts().single().length)
    }

    @Test fun blank_fact_is_rejected() {
        val mem = memory()
        val result = mem.remember("   ")
        assertTrue(result is RememberResult.Rejected)
        assertTrue(mem.facts().isEmpty())
    }

    @Test fun duplicate_ignores_case_and_does_not_grow_the_list() {
        val mem = memory()
        mem.remember("Lubi 22 stopnie w kabinie")
        assertEquals(RememberResult.Duplicate, mem.remember("lubi 22 STOPNIE w kabinie"))
        assertEquals(1, mem.facts().size)
    }

    @Test fun overflow_evicts_the_oldest_fact() {
        val mem = memory()
        repeat(DriverMemory.MAX_FACTS) { mem.remember("fakt $it") }
        val result = mem.remember("nowy fakt")
        assertEquals("fakt 0", (result as RememberResult.Stored).evicted)
        assertEquals(DriverMemory.MAX_FACTS, mem.facts().size)
        assertEquals("fakt 1", mem.facts().first())
        assertEquals("nowy fakt", mem.facts().last())
    }

    @Test fun stored_below_the_cap_evicts_nothing() {
        val mem = memory()
        assertNull((mem.remember("Kierowca ma na imię Andrzej") as RememberResult.Stored).evicted)
    }

    @Test fun forget_matches_substring_ignoring_case() {
        val mem = memory()
        mem.remember("Kierowca ma na imię Andrzej")
        mem.remember("Lubi 22 stopnie w kabinie")
        assertEquals(listOf("Kierowca ma na imię Andrzej"), mem.forget("andrzej"))
        assertEquals(listOf("Lubi 22 stopnie w kabinie"), mem.facts())
    }

    @Test fun forget_with_blank_query_changes_nothing() {
        val mem = memory()
        mem.remember("Kierowca ma na imię Andrzej")
        assertTrue(mem.forget("  ").isEmpty())
        assertEquals(1, mem.facts().size)
    }

    @Test fun forget_all_reports_the_count() {
        val mem = memory()
        mem.remember("jeden")
        mem.remember("dwa")
        assertEquals(2, mem.forgetAll())
        assertTrue(mem.facts().isEmpty())
        assertEquals(0, mem.forgetAll())
    }

    @Test fun prompt_block_is_empty_without_facts() {
        assertEquals("", memory().promptBlock())
    }

    @Test fun prompt_block_lists_facts_as_bullets() {
        val mem = memory()
        mem.remember("Kierowca ma na imię Andrzej")
        mem.remember("Lubi 22 stopnie w kabinie")
        val block = mem.promptBlock()
        assertTrue(block.contains("O KIEROWCY"))
        assertTrue(block.contains("- Kierowca ma na imię Andrzej"))
        assertTrue(block.contains("- Lubi 22 stopnie w kabinie"))
    }
}
