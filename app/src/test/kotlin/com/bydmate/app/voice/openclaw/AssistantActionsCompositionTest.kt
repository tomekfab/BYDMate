package com.bydmate.app.voice.openclaw

import android.app.NotificationManager
import android.content.Context
import com.bydmate.app.agent.ActionConfirmer
import com.bydmate.app.agent.ActionRegistry
import com.bydmate.app.agent.ConfirmResult
import com.bydmate.app.agent.DispatcherActionExecutor
import com.bydmate.app.agent.RegistryActionValidator
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.data.vehicle.WriteAllowlist
import com.bydmate.app.voice.AssistantReply
import com.bydmate.app.voice.VoiceJournalEntry.Outcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The whole assistant action path with the real parts joined: [OpenClawAssistant] (extraction and mapping),
 * [LedgerAssistantActionHandler] (validation, ledger, report), [DispatcherActionExecutor] and a real
 * [ActionDispatcher] over a mocked [VehicleApi]. Only the transport and the confirmation overlay are fake.
 */
class AssistantActionsCompositionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val allowlist: WriteAllowlist by lazy {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/assets/competitor-actions.json").exists() }
            ?: error("Cannot find app/src/main/assets/competitor-actions.json from ${File(".").canonicalPath}")
        WriteAllowlist.loadProduction { File(root, "app/src/main/assets/competitor-actions.json").readText() }
    }
    private val registry: ActionRegistry by lazy { ActionRegistry(allowlist = allowlist) }

    private val vehicleApi = mockk<VehicleApi>(relaxed = true) {
        coEvery { dispatch(any()) } returns Result.success(Unit)
    }
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        every { createConfigurationContext(any()) } answers { self as Context }
        every { getString(any<Int>()) } returns "gate"
        every { getString(any<Int>(), *anyVararg()) } returns "gate"
    }
    private val dispatcher = ActionDispatcher(
        vehicleApi, mockk<HelperClient>(relaxed = true), context,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
        mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
        mockk<com.bydmate.app.util.AppStrings>(relaxed = true), dagger.Lazy { mockk(relaxed = true) },
    ).also { it.readSpeedNow = { 0 } }

    private class FakeConfirmer(var result: ConfirmResult = ConfirmResult.CONFIRMED) : ActionConfirmer {
        var calls = 0
        override suspend fun confirm(title: String, summary: String): ConfirmResult {
            calls++
            return result
        }
    }

    private val confirmer = FakeConfirmer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private var identity: String? = "device-a"
    private fun dir() = File(tmp.root, "openclaw")
    private fun newLedger() = ActionLedger(dir(), { identity })

    /** One "app run": a ledger and a handler over the shared directory. A second call is a process restart. */
    private inner class Stack(val ledger: ActionLedger = newLedger()) {
        val handler = LedgerAssistantActionHandler(
            registry, RegistryActionValidator(registry), ledger,
            DispatcherActionExecutor(registry, dispatcher, confirmer) { diParsData(speed = 0) },
            AliasProposalSinkHolder(), executionScope = scope,
        )
    }

    private class Prefs : OpenClawPrefs {
        override fun isEnabled() = true
        override fun setEnabled(enabled: Boolean) = Unit
        override fun endpointText() = "wss://gateway.example.ts.net/"
        override fun setEndpointText(text: String) = Unit
    }

    private class Credentials : OpenClawCredentials {
        override fun exist() = true
        override fun clear() = Unit
    }

    private class ScriptedChat(val script: (Int) -> OpenClawTurnResult) : OpenClawChat {
        var calls = 0
        override val linkState: StateFlow<OpenClawLinkState> = MutableStateFlow(OpenClawLinkState.Ready)
        override suspend fun connect(): OpenClawLinkState = OpenClawLinkState.Ready
        override suspend fun pair(): OpenClawLinkState = OpenClawLinkState.Ready
        override suspend fun ask(text: String, onDelta: (String) -> Unit): OpenClawTurnResult = script(++calls)
        override suspend fun resendUncertain(onDelta: (String) -> Unit): OpenClawTurnResult = script(++calls)
        override suspend fun cancel() = Unit
        override suspend fun resetConversation() = Unit
        override suspend fun disconnect() = Unit
    }

    private val fence = "```"
    private fun turnKey(n: Int) = "aaaaaaaa-%04d-0000-0000-000000000000".format(n)

    private fun answerText(n: Int, action: String, proposalId: String = "prop-%04d".format(n)): String =
        "NIEZAUFANA PROZA\n${fence}bydmate-action\n" +
            """{"schema":"bydmate.action/1","requestId":"${OpenClawProtocol.turnTag(turnKey(n))}","proposalId":"$proposalId",""" +
            """"capabilities":"${registry.version}","type":"action","action":$action}""" +
            "\n$fence"

    private val setTemp = """{"kind":"vehicle","id":"ac_set_temp","value":22}"""
    private val openWindows = """{"kind":"vehicle","id":"windows_open_all"}"""

    private fun assistant(chat: OpenClawChat, lifecycle: AssistantActionLifecycle = AssistantActionLifecycle.None) =
        OpenClawAssistant(Prefs(), { chat }, Credentials(), actionLifecycle = lifecycle)

    /** Asks and hands any proposal to [handler], like the controller. */
    private suspend fun turn(assistant: OpenClawAssistant, handler: AssistantActionHandler): AssistantActionResult {
        val reply = assistant.ask("ustaw temperaturę")
        assertTrue("expected a proposal but was $reply", reply is AssistantReply.Proposal)
        reply as AssistantReply.Proposal
        return handler.handle(ProposalInput(reply.turnKey, reply.envelope, reply.recovered, reply.startedAtMs))
    }

    private fun spoken(r: AssistantActionResult): AssistantActionResult.Spoken {
        assertTrue("expected Spoken but was $r", r is AssistantActionResult.Spoken)
        return r as AssistantActionResult.Spoken
    }

    @Test fun `a valid comfort proposal dispatches once and the result rides on the next turn once`() = runBlocking {
        val stack = Stack()
        val chat = ScriptedChat { n -> OpenClawTurnResult.ActionAnswer(answerText(n, setTemp), recovered = false, turnKey = turnKey(n)) }

        val result = spoken(turn(assistant(chat), stack.handler))

        coVerify(exactly = 1) { vehicleApi.dispatch("设置温度22") }
        assertTrue(result.line, result.line.startsWith("Wykonano"))
        assertFalse(result.line, "NIEZAUFANA" in result.line)
        assertEquals(Outcome.OK, result.outcome)
        assertEquals(0, confirmer.calls)

        val next = stack.handler.forTurn("next-turn")
        assertTrue(next, "bydmate.result" in next)
        assertTrue(next, "\"status\":\"done\"" in next)
        assertTrue(next, "\"verified\":\"none\"" in next)
        stack.handler.onAdmitted("next-turn")
        assertFalse(stack.handler.forTurn("turn-after").contains("bydmate.result"))
    }

    @Test fun `a recovered proposal never dispatches`() = runBlocking {
        val stack = Stack()
        val chat = ScriptedChat { n -> OpenClawTurnResult.ActionAnswer(answerText(n, setTemp), recovered = true, turnKey = turnKey(n)) }

        val result = spoken(turn(assistant(chat), stack.handler))

        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
        assertEquals(PolishActionText.RECOVERED, result.line)
    }

    @Test fun `replaying the same answer for the same turn dispatches nothing more`() = runBlocking {
        val stack = Stack()
        // Both asks come back under the same turn key and proposal id, like a duplicate delivery.
        val chat = ScriptedChat { OpenClawTurnResult.ActionAnswer(answerText(1, setTemp), recovered = false, turnKey = turnKey(1)) }
        val assistant = assistant(chat)

        spoken(turn(assistant, stack.handler))
        val replay = turn(assistant, stack.handler)

        assertEquals(AssistantActionResult.Silent, replay)
        coVerify(exactly = 1) { vehicleApi.dispatch(any()) }
    }

    @Test fun `a declined confirmation dispatches nothing and the next turn reports declined`() = runBlocking {
        val stack = Stack()
        confirmer.result = ConfirmResult.DECLINED
        val chat = ScriptedChat { n -> OpenClawTurnResult.ActionAnswer(answerText(n, openWindows), recovered = false, turnKey = turnKey(n)) }

        val result = spoken(turn(assistant(chat), stack.handler))

        assertEquals(1, confirmer.calls)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
        assertEquals(PolishActionText.DECLINED, result.line)
        assertTrue(stack.handler.forTurn("next-turn").contains("\"status\":\"declined\""))
    }

    @Test fun `a restart or reconnect keeps the ledger so the replayed answer dispatches nothing`() = runBlocking {
        val first = Stack()
        val chat = ScriptedChat { OpenClawTurnResult.ActionAnswer(answerText(1, setTemp), recovered = false, turnKey = turnKey(1)) }
        spoken(turn(assistant(chat), first.handler))
        coVerify(exactly = 1) { vehicleApi.dispatch(any()) }

        // Same directory, same identity: the entry and the unsent report are still there.
        val second = Stack()
        assertNotNull(second.ledger.entry(turnKey(1)))
        assertTrue(second.handler.forTurn("after-restart").contains("\"status\":\"done\""))

        val replay = turn(assistant(chat), second.handler)

        assertEquals(AssistantActionResult.Silent, replay)
        coVerify(exactly = 1) { vehicleApi.dispatch(any()) }
    }

    @Test fun `forgetting the identity through the credentials clears the ledger and a new identity starts empty`() = runBlocking {
        val stack = Stack()
        val chat = ScriptedChat { n -> OpenClawTurnResult.ActionAnswer(answerText(n, setTemp), recovered = false, turnKey = turnKey(n)) }
        spoken(turn(assistant(chat), stack.handler))
        assertNotNull(stack.ledger.entry(turnKey(1)))

        StoredOpenClawCredentials(mockk(relaxed = true), mockk(relaxed = true), onForget = stack.handler::onIdentityForgotten).clear()
        identity = "device-b"

        assertNull(stack.ledger.entry(turnKey(1)))
        assertNull(stack.ledger.pendingReport())
        val fresh = Stack()
        assertNull(fresh.ledger.entry(turnKey(1)))
        assertFalse(fresh.handler.forTurn("t").contains("bydmate.result"))
    }
}
