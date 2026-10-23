package com.bydmate.app.agent

import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** askDetached(): automation-origin agent turn — isolated from the live conversation. */
class AgentOrchestratorDetachedTest {

    // Reuse the fake backend / settings factory pattern from AgentOrchestratorTest.kt:
    // a stub AgentBackend whose chat() returns a scripted reply, mockk SettingsRepository
    // with isAgentEnabled() true, mockk AgentTools with schemas() returning JSONArray().

    private class FakeBackend(
        var configured: Boolean = true,
        val reply: Result<AgentReply>? = null,
    ) : AgentBackend {
        val requests = mutableListOf<List<AgentMessage>>()
        override suspend fun isConfigured() = configured
        override suspend fun chat(
            messages: List<AgentMessage>,
            tools: JSONArray?,
            onDelta: ((String) -> Unit)?,
        ): Result<AgentReply> {
            requests += messages
            return reply ?: Result.failure(IllegalStateException("no scripted reply"))
        }
    }

    private fun answer(text: String) = Result.success(AgentReply(text, emptyList()))

    private fun defaultTools(): AgentTools = mockk<AgentTools>().also { t ->
        coEvery { t.schemas(includeAutomationTools = false) } returns JSONArray()
    }

    private fun makeOrchestrator(
        reply: Result<AgentReply>? = null,
        agentEnabled: Boolean = true,
        configured: Boolean = true,
        tools: AgentTools = defaultTools(),
        backend: FakeBackend = FakeBackend(configured = configured, reply = reply),
    ): AgentOrchestrator {
        val repo = mockk<SettingsRepository>()
        coEvery { repo.isAgentEnabled() } returns agentEnabled
        return AgentOrchestrator(backend, tools, repo, isMoving = { moving }).also { it.nowMs = { clock } }
    }

    private var moving = false
    private var clock = java.util.Calendar.getInstance().apply {
        clear(); set(2026, java.util.Calendar.SEPTEMBER, 25, 7, 30)
    }.timeInMillis

    // A detached turn talks to an automation rule, not the driver: the memory tools are
    // withheld there, and the driver facts must not ride in its system prompt either.
    @Test
    fun `detached prompt carries no driver memory block`() = runTest {
        val backend = FakeBackend(reply = answer("ok"))
        val repo = mockk<SettingsRepository>()
        coEvery { repo.isAgentEnabled() } returns true
        val orch = AgentOrchestrator(
            backend, defaultTools(), repo, isMoving = { false },
            memoryBlock = { "\nO KIEROWCY (fakty):\n- Kierowca ma na imię Andrzej" },
        )
        orch.askDetached("podsumowanie")
        val sys = (backend.requests.single().first() as AgentMessage.System).content
        assertFalse(sys.contains("Kierowca ma na imię Andrzej"))
    }

    @Test
    fun `answer is returned without touching the shared history`() = runTest {
        val orch = makeOrchestrator(reply = answer("Pogoda jest słoneczna"))
        val r = orch.askDetached("podsumowanie pogody")
        assertEquals("Pogoda jest słoneczna", (r as AgentResult.Answer).text)
        // Shared conversation stays untouched: no follow-up window opened.
        assertFalse(orch.expectsFollowUp())
    }

    @Test
    fun `detached question does not open a follow-up window`() = runTest {
        val orch = makeOrchestrator(reply = answer("Które okno?"))  // ends with "?"
        orch.askDetached("otwórz okno")
        assertFalse(orch.expectsFollowUp())
    }

    @Test
    fun `disabled agent returns Disabled`() = runTest {
        val orch = makeOrchestrator(agentEnabled = false)
        assertEquals(AgentResult.Disabled, orch.askDetached("cześć"))
    }

    @Test
    fun `unconfigured backend returns Error`() = runTest {
        val orch = makeOrchestrator(configured = false)
        assertTrue(orch.askDetached("cześć") is AgentResult.Error)
    }

    @Test
    fun `blank prompt returns Disabled`() = runTest {
        assertEquals(AgentResult.Disabled, makeOrchestrator(reply = answer("x")).askDetached("   "))
    }

    // An automation prompt is not the driver talking: the moving tag would be a lie about who
    // is asking, and it would push the answer to a single word.
    @Test
    fun `detached prompt is not tagged while the car moves`() = runTest {
        moving = true
        val backend = FakeBackend(reply = answer("Pogoda jest słoneczna"))
        makeOrchestrator(backend = backend).askDetached("podsumowanie pogody")
        val user = backend.requests.single().last() as AgentMessage.User
        assertEquals("podsumowanie pogody (czas 07:30)", user.content)
    }

    // A scheduled "morning summary" needs the clock as much as the driver does.
    @Test
    fun `detached prompt carries the local time`() = runTest {
        val backend = FakeBackend(reply = answer("Dzień dobry"))
        makeOrchestrator(backend = backend).askDetached("poranne podsumowanie")
        val user = backend.requests.single().last() as AgentMessage.User
        assertEquals("poranne podsumowanie (czas 07:30)", user.content)
    }

    // No memory rides in a detached prompt, so both system messages must be byte-identical
    // between two turns minutes apart: the time lives only on the user line.
    @Test
    fun `detached system messages do not change with the clock`() = runTest {
        val first = FakeBackend(reply = answer("ok"))
        makeOrchestrator(backend = first).askDetached("podsumowanie")
        clock += 17 * 60_000L
        val second = FakeBackend(reply = answer("ok"))
        makeOrchestrator(backend = second).askDetached("podsumowanie")
        val a = first.requests.single().filterIsInstance<AgentMessage.System>()
        val b = second.requests.single().filterIsInstance<AgentMessage.System>()
        assertEquals(2, a.size)
        assertEquals(a, b)
        assertEquals("podsumowanie (czas 07:47)", (second.requests.single().last() as AgentMessage.User).content)
    }

    @Test
    fun `schemas are requested without automation tools`() = runTest {
        // verify tools.schemas(includeAutomationTools = false) was called (mockk coVerify)
        val tools = mockk<AgentTools>(relaxed = true)
        coEvery { tools.schemas(includeAutomationTools = false) } returns JSONArray()
        val orch = makeOrchestrator(reply = answer("ok"), tools = tools)
        orch.askDetached("test")
        io.mockk.coVerify(exactly = 1) { tools.schemas(includeAutomationTools = false) }
    }
}
