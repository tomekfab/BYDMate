package com.bydmate.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceChunkerTest {

    @Test
    fun `two sentences split on terminator plus space`() {
        val c = SentenceChunker()
        val out = c.feed("Poziom naładowania to 80 procent. Wystarczy do Warszawy.")
        assertEquals(listOf("Poziom naładowania to 80 procent."), out)
        assertEquals("Wystarczy do Warszawy.", c.flush())
    }

    @Test
    fun `decimal number does not split`() {
        val c = SentenceChunker()
        val out = c.feed("Temperatura to 22.5 stopnia. Jest ciepło.")
        assertEquals(listOf("Temperatura to 22.5 stopnia."), out)
        assertEquals("Jest ciepło.", c.flush())
    }

    @Test
    fun `char by char feeding emits sentence once boundary is complete`() {
        val c = SentenceChunker()
        val collected = mutableListOf<String>()
        "Tak. Nie.".forEach { ch -> collected += c.feed(ch.toString()) }
        assertEquals(listOf("Tak."), collected)
        assertEquals("Nie.", c.flush())
    }

    @Test
    fun `newline is a boundary`() {
        val c = SentenceChunker()
        val out = c.feed("Pierwsza linia\nDruga linia")
        assertEquals(listOf("Pierwsza linia"), out)
        assertEquals("Druga linia", c.flush())
    }

    @Test
    fun `terminator run stays together`() {
        val c = SentenceChunker()
        val out = c.feed("Naprawdę?! Tak. ")
        assertEquals(listOf("Naprawdę?!", "Tak."), out)
        assertNull(c.flush())
    }

    @Test
    fun `ellipsis char is a terminator`() {
        val c = SentenceChunker()
        val out = c.feed("Poczekaj… Już sprawdzam.")
        assertEquals(listOf("Poczekaj…"), out)
        assertEquals("Już sprawdzam.", c.flush())
    }

    @Test
    fun `flush on empty buffer returns null and resets`() {
        val c = SentenceChunker()
        c.feed("Gotowe. ")
        assertNull(c.flush())
    }
}
