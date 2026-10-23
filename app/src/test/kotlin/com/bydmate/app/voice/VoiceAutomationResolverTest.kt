package com.bydmate.app.voice

import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.RuleEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceAutomationResolverTest {
    private fun voiceRule(id: Long, phrase: String) = RuleEntity(
        id = id, name = "r$id", enabled = true, triggerLogic = "AND",
        triggers = """[{"param":"Voice","chineseName":"语音","operator":"==","value":"$phrase","displayName":"$phrase","kind":"voice"}]""",
        actions = """[{"command":"","displayName":"x","kind":"app_launch","payload":"{}"}]""",
    )

    private fun resolver(rules: List<RuleEntity>): VoiceAutomationResolver {
        val dao = mockk<RuleDao> { coEvery { getEnabled() } returns rules }
        return VoiceAutomationResolver(dao)
    }

    @Test fun `match returns the rule on normalized equality`() = runBlocking {
        assertEquals(VoiceAutomationMatch(1L, "r1"), resolver(listOf(voiceRule(1, "nawigacja"))).match("Nawigacja"))
    }

    @Test fun `match returns null when nothing matches`() = runBlocking {
        assertNull(resolver(listOf(voiceRule(1, "nawigacja"))).match("muzyka"))
    }

    @Test fun `only the whole utterance matches and Polish fillers are ignored`() = runBlocking {
        val r = resolver(listOf(voiceRule(1, "tryb nocny")))
        assertNull(r.match("ej włącz mi tryb nocny proszę"))
        assertEquals(VoiceAutomationMatch(1L, "r1"), r.match("ej tryb nocny proszę"))
    }

    @Test fun `no partial-word match`() = runBlocking {
        assertNull(resolver(listOf(voiceRule(1, "okno"))).match("okienkować"))
    }

    @Test fun `each phrase matches only itself`() = runBlocking {
        val r = resolver(listOf(voiceRule(1, "okna"), voiceRule(2, "okna domu")))
        assertEquals(2L, r.match("okna domu")?.ruleId)
        assertEquals(1L, r.match("okna")?.ruleId)
        assertNull(r.match("otwórz okna domu"))
    }

    @Test fun `on a tie the first rule wins`() = runBlocking {
        val r = resolver(listOf(voiceRule(1, "scena"), voiceRule(2, "scena")))
        assertEquals(1L, r.match("scena")?.ruleId)
    }
}
