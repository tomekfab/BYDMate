package com.bydmate.app.voice.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OpenClawConversationStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir() = File(tmp.root, "openclaw")
    private fun store(agent: String = "tomek") = OpenClawConversationStore(dir(), agent)

    @Test
    fun `key is the tomek agent with a separate bydmate car conversation`() {
        val key = store().sessionKey("pl")
        assertTrue(key, Regex("agent:tomek:bydmate-car-[0-9a-f]{8,}").matches(key))
        assertNotEquals("the old smoke-test conversation must not be reused", "agent:tomek:bydmate-car-test", key)
        assertTrue(!key.contains(":main"))
    }

    @Test
    fun `key is stable across calls and store instances`() {
        val first = store().sessionKey("pl")
        assertEquals(first, store().sessionKey("pl"))
        assertEquals(first, store().sessionKey("pl"))
    }

    @Test
    fun `explicit reset starts a new conversation that then stays stable`() {
        val store = store()
        val old = store.sessionKey("pl")
        val fresh = store.reset("pl")
        assertNotEquals(old, fresh)
        assertEquals(fresh, store().sessionKey("pl"))
    }

    @Test
    fun `a language change starts a new conversation`() {
        val store = store()
        val polish = store.sessionKey("pl")
        val russian = store.sessionKey("ru")
        assertNotEquals(polish, russian)
        assertEquals(russian, store().sessionKey("ru"))
        assertNotEquals(polish, store().sessionKey("pl"))
    }

    @Test
    fun `a different agent starts a new conversation`() {
        val tomek = store("tomek").sessionKey("pl")
        val other = store("jola").sessionKey("pl")
        assertTrue(other.startsWith("agent:jola:bydmate-car-"))
        assertNotEquals(tomek, other)
    }

    @Test
    fun `clear forgets the conversation`() {
        val store = store()
        val old = store.sessionKey("pl")
        store.clear()
        assertTrue(dir().listFiles().orEmpty().isEmpty())
        assertNotEquals(old, store.sessionKey("pl"))
    }

    @Test
    fun `a corrupt file yields a fresh conversation instead of a crash`() {
        val store = store()
        val old = store.sessionKey("pl")
        dir().listFiles()!!.single { it.isFile }.writeText("{{{")
        val fresh = store().sessionKey("pl")
        assertTrue(Regex("agent:tomek:bydmate-car-[0-9a-f]{8,}").matches(fresh))
        assertNotEquals(old, fresh)
    }

    @Test
    fun `the file holds no credential`() {
        val store = store()
        store.sessionKey("pl")
        val text = dir().listFiles()!!.single { it.isFile }.readText().lowercase()
        assertTrue(!text.contains("token") && !text.contains("seed") && !text.contains("secret"))
    }
}
