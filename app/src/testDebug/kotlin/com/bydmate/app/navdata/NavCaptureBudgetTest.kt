package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs under Robolectric so `JSONObject.quote` is the platform implementation the store uses. */
@RunWith(RobolectricTestRunner::class)
class NavCaptureBudgetTest {
    @Test
    fun `serialized bytes follow the store escaping`() {
        assertEquals(0, NavCaptureBudget.serializedBytes(""))
        assertEquals(3, NavCaptureBudget.serializedBytes("abc"))
        assertEquals(2, NavCaptureBudget.serializedBytes("ł"))
        assertEquals(2, NavCaptureBudget.serializedBytes("\n"))
        assertEquals(6, NavCaptureBudget.serializedBytes("\u0001"))
        assertEquals(4, NavCaptureBudget.serializedBytes("😀"))
    }

    @Test
    fun `truncate returns identical text when it fits`() {
        val (text, cut) = NavCaptureBudget.truncate("Zażółć gęślą jaźń", 100)
        assertEquals("Zażółć gęślą jaźń", text)
        assertFalse(cut)
        val (exact, exactCut) = NavCaptureBudget.truncate("ł".repeat(5), 10)
        assertEquals("ł".repeat(5), exact)
        assertFalse(exactCut)
    }

    @Test
    fun `truncate never splits a surrogate pair and stays within the budget`() {
        val input = "😀".repeat(10)
        for (max in 0..41) {
            val (text, cut) = NavCaptureBudget.truncate(input, max)
            assertTrue("max=$max bytes=${NavCaptureBudget.serializedBytes(text)}", NavCaptureBudget.serializedBytes(text) <= max)
            assertEquals("max=$max", "😀".repeat(minOf(max / 4, 10)), text)
            assertEquals("max=$max", max < 40, cut)
        }
    }

    @Test
    fun `truncate counts escape expansion`() {
        val (text, cut) = NavCaptureBudget.truncate("\n".repeat(10), 5)
        assertEquals("\n\n", text)
        assertTrue(cut)
        val (control, controlCut) = NavCaptureBudget.truncate("\u0001\u0001", 11)
        assertEquals("\u0001", control)
        assertTrue(controlCut)
    }

    @Test
    fun `bound extras keeps order and stays within the budget`() {
        val extras = linkedMapOf("a" to "x".repeat(100), "b" to "y".repeat(100), "c" to "z".repeat(100))
        val (fits, fitsCut) = NavCaptureBudget.boundExtras(extras, 1000, 64)
        assertEquals(extras, fits)
        assertEquals(extras.keys.toList(), fits.keys.toList())
        assertFalse(fitsCut)

        val (bounded, cut) = NavCaptureBudget.boundExtras(extras, 150, 64)
        assertTrue(cut)
        assertEquals(listOf("a", "b"), bounded.keys.toList())
        assertEquals("x".repeat(100), bounded["a"])
        assertEquals("y".repeat(48), bounded["b"])
        assertEquals(150, bounded.entries.sumOf { NavCaptureBudget.serializedBytes(it.key) + NavCaptureBudget.serializedBytes(it.value) })
    }

    @Test
    fun `bound extras flags a key count cut and a key that cannot fit`() {
        val many = linkedMapOf("k1" to "v", "k2" to "v", "k3" to "v")
        val (limited, limitedCut) = NavCaptureBudget.boundExtras(many, 1000, 2)
        assertEquals(listOf("k1", "k2"), limited.keys.toList())
        assertTrue(limitedCut)

        val (dropped, droppedCut) = NavCaptureBudget.boundExtras(linkedMapOf("k".repeat(50) to "v"), 10, 64)
        assertTrue(dropped.isEmpty())
        assertTrue(droppedCut)
    }

    @Test
    fun `tree and text budgets leave room for the envelope`() {
        assertTrue(NavCaptureBudget.TREE_BYTES + 2048 <= NavCaptureStore.MAX_RECORD_BYTES)
        assertTrue(2 * NavCaptureBudget.TEXT_BYTES + NavCaptureBudget.EXTRAS_BYTES + 2048 <= NavCaptureStore.MAX_RECORD_BYTES)
    }
}
