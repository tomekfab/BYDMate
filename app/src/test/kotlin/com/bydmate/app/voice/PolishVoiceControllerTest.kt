package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.automation.VoiceFireResult
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.util.appStringsOver
import com.bydmate.app.voice.openclaw.AssistantActionHandler
import com.bydmate.app.voice.openclaw.AssistantActionResult
import com.bydmate.app.voice.openclaw.NoAssistantActions
import com.bydmate.app.voice.openclaw.ParsedEnvelope
import com.bydmate.app.voice.openclaw.ProposalInput
import com.bydmate.app.agent.ActionConfirmer
import com.bydmate.app.agent.ActionExecutor
import com.bydmate.app.agent.ConfirmResult
import com.bydmate.app.agent.ActionOrigin
import com.bydmate.app.agent.ActionOutcome
import com.bydmate.app.agent.ActionRegistry
import com.bydmate.app.agent.ActionRejection
import com.bydmate.app.agent.ExecutionHooks
import com.bydmate.app.agent.RegistryActionValidator
import com.bydmate.app.agent.ValidatedAction
import com.bydmate.app.data.vehicle.WriteAllowlist
import com.bydmate.app.voice.openclaw.ActionLedger
import com.bydmate.app.voice.openclaw.AliasProposalSinkHolder
import com.bydmate.app.voice.openclaw.LedgerAssistantActionHandler
import com.bydmate.app.voice.openclaw.OpenClawActionProtocol
import com.bydmate.app.voice.openclaw.OpenClawProtocol
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import io.mockk.slot
import io.mockk.verify
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.json.JSONObject
import org.junit.Test
import java.util.Collections

/**
 * Saved phrases (own phrase for a built-in command, automation phrase) are exact-match overrides, but
 * a Polish negated, quoted or partial request is refused before them: it neither dispatches, fires an
 * automation, nor reaches the online assistant. An ordinary exact custom phrase still works.
 */
@Suppress("LargeClass") // Routing, refusal and cancellation scenarios share the Polish voice-session fixture.
class PolishVoiceControllerTest {

    private class FakeContinuousAsr : ContinuousAsr {
        val events = MutableSharedFlow<ContinuousAsrEvent>(extraBufferCapacity = 16)
        override fun isReady(): Boolean = true
        override fun transcribe(pcm: Flow<ShortArray>): Flow<ContinuousAsrEvent> = events
    }

    // Explicit fixture collaborators let scenarios inspect each routing and speech boundary.
    private class Rig @Suppress("LongParameterList") constructor(
        val controller: VoiceController,
        val asr: FakeContinuousAsr,
        val engine: AutomationEngine,
        val assistant: VoiceAssistant,
        val journal: VoiceJournal,
        val tts: TtsEngine,
        val assistantActions: AssistantActionHandler,
        val aliasCoordinator: VoiceAliasCoordinator,
        val aliasRuns: MutableList<VoiceAlias>,
    ) {
        val dispatched: MutableList<ActionDef> = Collections.synchronizedList(mutableListOf())
    }

    private fun awaitTrue(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    /** A snapshot that reports only the named readings; everything else is never read here. */
    private fun snapshot(ac: Int? = null, outside: Int? = null, charge: Int? = null): DiParsData =
        mockk(relaxed = true) {
            every { acTemp } returns ac
            every { exteriorTemp } returns outside
            every { soc } returns charge
        }

    /** [snapshot] and its [ageMs] are what the gate reports; [failCommand] makes the dispatcher
     *  refuse that one command, like a blocked or failed write. */
    @Suppress("LongParameterList") // Named arguments expose independent scenario inputs without changing the shared fixture.
    private fun rig(
        automationPhrase: String?,
        userPhrases: VoiceUserPhrases,
        snapshot: DiParsData? = null,
        ageMs: Long? = null,
        failCommand: String? = null,
        assistantActions: AssistantActionHandler = NoAssistantActions,
        assistantEnabled: Boolean = true,
        assistantReply: AssistantReply = AssistantReply.Answer("odpowiedź"),
        agentName: String = "",
        ttsEnabled: Boolean = false,
        aliasResult: AliasRunResult = AliasRunResult.Executed(ActionOutcome.Done),
        contacts: VoiceContacts = NoVoiceContacts,
        confirmer: ActionConfirmer = NoCallConfirmer,
    ): Rig {
        val gate = mockk<VoiceGate> {
            every { isEnabled() } returns true
            every { vehicleSnapshot() } returns snapshot
            every { snapshotAgeMs() } returns ageMs
            every { rangeKm() } returns 250.0
            every { ttsEnabled() } returns ttsEnabled
        }
        val audioCapture = mockk<AudioCapture>(relaxed = true)
        every { audioCapture.captureSession(any(), any<(ShortArray) -> Any?>()) } returns flow { }
        val rules = listOfNotNull(automationPhrase?.let(::voiceRule))
        val resolver = VoiceAutomationResolver(mockk<RuleDao> { coEvery { getEnabled() } returns rules })
        val engine = mockk<AutomationEngine>(relaxed = true)
        coEvery { engine.fireVoiceRule(any(), any()) } returns VoiceFireResult.Fired(true)
        val agent = mockk<AgentOrchestrator>(relaxed = true)
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.createConfigurationContext(any()) } returns ctx
        val tts = mockk<TtsEngine>(relaxed = true) { every { speaking } returns MutableStateFlow(false) }
        val dispatcher = mockk<ActionDispatcher>(relaxed = true)
        val assistant = mockk<VoiceAssistant> {
            every { isEnabled() } returns assistantEnabled
            coEvery { ask(any(), any()) } returns assistantReply
            coEvery { cancel() } returns Unit
        }
        val asr = FakeContinuousAsr()
        val journal = VoiceJournal()
        val aliasRuns = Collections.synchronizedList(mutableListOf<VoiceAlias>())
        val aliasCoordinator = mockk<VoiceAliasCoordinator>()
        coEvery { aliasCoordinator.run(any()) } coAnswers { aliasRuns += firstArg<VoiceAlias>(); aliasResult }
        val controller = VoiceController(
            audioCapture, dispatcher, mockk(relaxed = true), gate,
            engine, resolver, agent, ctx, tts, journal, asr,
            agentIdentity = { AgentIdentity(agentName, AgentPersona.NAVIGATOR) },
            appStrings = appStringsOver(ctx),
            aliasCoordinator = aliasCoordinator,
            userPhrases = userPhrases,
            assistant = assistant,
            assistantActions = assistantActions,
            contacts = contacts,
            confirmer = confirmer,
        )
        return Rig(controller, asr, engine, assistant, journal, tts, assistantActions, aliasCoordinator, aliasRuns).also { r ->
            coEvery { dispatcher.dispatch(any<ActionDef>(), any()) } answers {
                val action = firstArg<ActionDef>()
                r.dispatched += action
                if (action.command == failCommand) DispatchResult(false, "odmowa") else DispatchResult(true)
            }
        }
    }

    private fun voiceRule(phrase: String) = RuleEntity(
        id = 9L, name = "Moja", enabled = true, triggerLogic = "AND",
        triggers = """[{"param":"Voice","chineseName":"语音","operator":"==","value":"${phrase.replace("\"", "\\\"")}","displayName":"x","kind":"voice"}]""",
        actions = """[{"command":"","displayName":"x","kind":"app_launch","payload":"{}"}]""",
    )

    /** A built-in command that is not speed-gated, so the control dispatches with no vehicle snapshot. */
    private val command = VoiceUserPhrases.COMMANDS.first {
        !ActionDispatcher.isWindowOpenCommand(it.command) && !ActionDispatcher.isSunroofOpenCommand(it.command)
    }

    private fun phrases(phrase: String) = VoiceUserPhrases().apply { add(command.id, phrase) }

    private fun Rig.say(text: String): VoiceJournalEntry {
        controller.onPttPressed()
        awaitTrue { controller.listening.value }
        awaitTrue { asr.events.subscriptionCount.value >= 1 }
        asr.events.tryEmit(ContinuousAsrEvent.Utterance(text))
        awaitTrue { journal.entries.value.isNotEmpty() }
        return journal.entries.value.first()
    }

    private fun Rig.assertNothingRan(text: String) {
        assertEquals(text, emptyList<ActionDef>(), dispatched.toList())
        assertEquals(text, emptyList<VoiceAlias>(), aliasRuns.toList())
        coVerify(exactly = 0) { engine.fireVoiceRule(any(), any()) }
        coVerify(exactly = 0) { assistant.ask(any(), any()) }
    }

    /** Polish request text -> the refusal the grammar gives it. Preconditions are asserted, so a
     *  grammar change fails here by name instead of silently weakening the test. */
    private val unsafe = listOf(
        "nie otwórz okna" to NluRefusal.NEGATED,
        "nie nawiguj do domu" to NluRefusal.NEGATED,
        "powiedział nawiguj do domu" to NluRefusal.QUOTED,
        "\"nawiguj do domu\"" to NluRefusal.QUOTED,
        "nawiguj do domu i coś jeszcze" to NluRefusal.PARTIAL,
        "powiedział otwórz okna" to NluRefusal.QUOTED,
        "\"otwórz okna\"" to NluRefusal.QUOTED,
        "otwórz okna i otwórz maskę" to NluRefusal.PARTIAL,
        "nie nawiguj do pracy" to NluRefusal.NEGATED,
        "powiedział nawiguj do pracy" to NluRefusal.QUOTED,
        "\"nawiguj do pracy\"" to NluRefusal.QUOTED,
        "nawiguj do pracy i coś jeszcze" to NluRefusal.PARTIAL,
        "nie prowadź do domu" to NluRefusal.NEGATED,
        "\"jedźmy do domu\"" to NluRefusal.QUOTED,
        "prowadź do domu i coś jeszcze" to NluRefusal.PARTIAL,
        "nie jedźmy do pracy" to NluRefusal.NEGATED,
        "powiedział prowadź do pracy" to NluRefusal.QUOTED,
        "\"włącz nawigację do pracy\"" to NluRefusal.QUOTED,
        "jedźmy do pracy i coś jeszcze" to NluRefusal.PARTIAL,
        "nawiguj do domu potem zamknij okna" to NluRefusal.PARTIAL,
        "podnieś temperaturę potem zamknij okna" to NluRefusal.PARTIAL,
        "zrób ciszej i potem zamknij okna" to NluRefusal.PARTIAL,
        "nie obniżaj o dwa stopnie" to NluRefusal.NEGATED,
        "zamknij okna potem nie otwieraj rolety" to NluRefusal.NEGATED,
    )

    @Test fun `grammar refuses the unsafe texts these tests save as phrases`() {
        for ((text, reason) in unsafe) {
            assertEquals(text, NluVerdict.Refused(reason), NluParser.classify(text, VoiceLanguage.PL))
        }
    }

    @Test fun `a saved user phrase equal to a negated, quoted or partial command is refused locally`() {
        for ((text, reason) in unsafe) {
            val r = rig(null, phrases(text))
            val entry = r.say(text)

            assertEquals(text, VoiceJournalEntry.Route.REFUSED, entry.route)
            assertEquals(text, reason, entry.refusal)
            r.assertNothingRan(text)
        }
    }

    @Test fun `a saved automation phrase equal to a negated, quoted or partial command is refused locally`() {
        for ((text, reason) in unsafe) {
            val r = rig(text, VoiceUserPhrases())
            val entry = r.say(text)

            assertEquals(text, VoiceJournalEntry.Route.REFUSED, entry.route)
            assertEquals(text, reason, entry.refusal)
            r.assertNothingRan(text)
        }
    }

    private fun navigateAction(text: String, shortcut: String) = ActionDef(
        command = "", displayName = text, kind = "navigate",
        payload = JSONObject().put("shortcut", shortcut).put("go", true).toString(),
    )

    private fun destinationAction(query: String) = ActionDef(
        command = "", displayName = "", kind = "navigate",
        payload = JSONObject().put("query", query).put("go", true).toString(),
    )

    @Test fun local_destination_dispatches_a_validated_navigate_action() {
        val r = rig(null, VoiceUserPhrases())
        val entry = r.say("nawiguj na ulicę Janowskiego 82")

        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.OK, entry.outcome)
        assertEquals(listOf(destinationAction("ulicę Janowskiego 82")), r.dispatched.toList())
        val payload = JSONObject(r.dispatched.single().payload)
        assertEquals(setOf("query", "go"), payload.keys().asSequence().toSet())
        assertEquals("ulicę Janowskiego 82", payload.getString("query"))
        assertTrue(payload.getBoolean("go"))
        coVerify(exactly = 0) { r.assistant.ask(any(), any()) }
        coVerify(exactly = 0) { r.engine.fireVoiceRule(any(), any()) }
    }

    @Test fun glued_negated_destination_is_refused_without_running_navigation_or_ai() {
        val text = "nawiguj do Janowskiego 82, niejedź tam"
        val r = rig(null, VoiceUserPhrases())
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Route.REFUSED, entry.route)
        assertEquals(NluRefusal.NEGATED, entry.refusal)
        r.assertNothingRan(text)
    }

    @Test fun exact_saved_alias_cannot_override_a_glued_negated_destination() {
        val text = "nawiguj do Janowskiego 82, niejedź tam"
        val r = rig(null, phrases(text))
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Route.REFUSED, entry.route)
        assertEquals(NluRefusal.NEGATED, entry.refusal)
        r.assertNothingRan(text)
    }

    @Test fun exact_automation_phrase_cannot_override_a_glued_negated_destination() {
        val text = "nawiguj do Janowskiego 82, niejedź tam"
        val r = rig(text, VoiceUserPhrases())
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Route.REFUSED, entry.route)
        assertEquals(NluRefusal.NEGATED, entry.refusal)
        r.assertNothingRan(text)
    }

    @Test fun navigation_combined_with_a_car_command_is_refused_before_dispatch() {
        val r = rig(null, VoiceUserPhrases())
        val entry = r.say("nawiguj na ulicę Janowskiego 82 i otwórz okna")

        assertEquals(VoiceJournalEntry.Route.REFUSED, entry.route)
        assertEquals(NluRefusal.PARTIAL, entry.refusal)
        r.assertNothingRan("combined navigation")
    }

    @Test fun an_exact_saved_alias_overrides_an_incomplete_navigation_form() {
        val text = "nawiguj do"
        val r = rig(null, phrases(text))
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals("phrase:${command.id}", entry.command)
        assertEquals(listOf(VoiceAlias(text, AliasTarget.Vehicle(command.id))), r.aliasRuns.toList())
        assertEquals(emptyList<ActionDef>(), r.dispatched.toList())
        coVerify(exactly = 0) { r.assistant.ask(any(), any()) }
        coVerify(exactly = 0) { r.engine.fireVoiceRule(any(), any()) }
    }

    @Test fun incomplete_navigation_without_an_alias_is_refused_locally() {
        val r = rig(null, VoiceUserPhrases())
        val entry = r.say("nawiguj do")

        assertEquals(VoiceJournalEntry.Route.REFUSED, entry.route)
        assertEquals(NluRefusal.NAV_INCOMPLETE, entry.refusal)
        r.assertNothingRan("incomplete navigation")
    }

    @Test fun home_navigation_dispatches_one_complete_local_action() {
        val r = rig(null, VoiceUserPhrases())
        val entry = r.say("Nawiguj do domu.")
        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.OK, entry.outcome)
        assertEquals(listOf(navigateAction("Nawiguj do domu.", "home")), r.dispatched.toList())
        val payload = JSONObject(r.dispatched.single().payload)
        assertEquals(setOf("shortcut", "go"), payload.keys().asSequence().toSet())
        assertEquals("home", payload.getString("shortcut"))
        assertTrue(payload.getBoolean("go"))
        coVerify(exactly = 0) { r.assistant.ask(any(), any()) }
        coVerify(exactly = 0) { r.engine.fireVoiceRule(any(), any()) }
    }

    @Test fun work_navigation_dispatches_one_complete_local_action() {
        val r = rig(null, VoiceUserPhrases())
        val entry = r.say("Nawiguj do pracy.")
        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.OK, entry.outcome)
        assertEquals("navigate=work", entry.command)
        assertEquals(listOf(navigateAction("Nawiguj do pracy.", "work")), r.dispatched.toList())
        val payload = JSONObject(r.dispatched.single().payload)
        assertEquals(setOf("shortcut", "go"), payload.keys().asSequence().toSet())
        assertEquals("work", payload.getString("shortcut"))
        assertTrue(payload.getBoolean("go"))
        coVerify(exactly = 0) { r.assistant.ask(any(), any()) }
        coVerify(exactly = 0) { r.engine.fireVoiceRule(any(), any()) }
    }

    @Test fun natural_navigation_forms_dispatch_the_same_local_actions() {
        for ((text, shortcut) in listOf(
            "Prowadź do domu" to "home", "Jedźmy do domu" to "home", "Włącz nawigację do domu" to "home",
            "Prowadź do pracy" to "work", "Jedźmy do pracy" to "work", "Włącz nawigację do pracy" to "work",
        )) {
            val r = rig(null, VoiceUserPhrases())
            val entry = r.say(text)
            assertEquals(text, VoiceJournalEntry.Outcome.OK, entry.outcome)
            assertEquals(text, listOf(navigateAction(text, shortcut)), r.dispatched.toList())
            coVerify(exactly = 0) { r.assistant.ask(any(), any()) }
            coVerify(exactly = 0) { r.engine.fireVoiceRule(any(), any()) }
        }
    }

    @Test fun ambiguous_and_unsupported_requests_are_answered_locally_without_any_control() {
        for (text in listOf(
            "Zamknij wszystko", "Wyłącz nawiew", "Włącz wentylację", "Włącz ogrzewanie", "Włącz grzanie",
            "Włącz masaż fotela pasażera", "Podnieś temperaturę po stronie pasażera",
        )) {
            // With the assistant on, UNSUPPORTED goes to it (see the assistant-actions tests below).
            val r = rig(null, VoiceUserPhrases(), assistantEnabled = false)
            val entry = r.say(text)
            assertEquals(text, VoiceJournalEntry.Route.REFUSED, entry.route)
            assertEquals(text, NluRefusal.UNSUPPORTED, entry.refusal)
            r.assertNothingRan(text)
        }
    }

    @Test fun seat_massage_dispatches_locally_with_zero_assistant_calls() {
        val massageOn = ActionDef(command = "主驾座椅按摩打开", displayName = "主驾座椅按摩打开", kind = "param")
        for (assistantEnabled in listOf(true, false)) {
            for (text in listOf("Włącz masaż fotela kierowcy", "wlacz masaz fotela kierowcy")) {
                val r = rig(null, VoiceUserPhrases(), assistantEnabled = assistantEnabled)
                val entry = r.say(text)
                assertEquals(text, VoiceJournalEntry.Outcome.OK, entry.outcome)
                assertEquals(text, "seat_massage_driver_on", entry.command)
                assertEquals(text, listOf(massageOn), r.dispatched.toList())
                coVerify(exactly = 0) { r.assistant.ask(any(), any()) }
                coVerify(exactly = 0) { r.engine.fireVoiceRule(any(), any()) }
            }
        }
        val r = rig(null, VoiceUserPhrases(), assistantEnabled = false)
        val entry = r.say("Wyłącz masaż fotela kierowcy")
        assertEquals("seat_massage_driver_off", entry.command)
        assertEquals(listOf(ActionDef(command = "主驾座椅按摩关闭", displayName = "主驾座椅按摩关闭", kind = "param")), r.dispatched.toList())
    }

    @Test fun negated_quoted_and_partial_seat_massage_dispatch_nothing_and_ask_nothing() {
        for ((text, reason) in listOf(
            "nie włączaj masażu fotela kierowcy" to NluRefusal.NEGATED,
            "\"włącz masaż fotela kierowcy\"" to NluRefusal.QUOTED,
            "włącz masaż fotela kierowcy i coś jeszcze" to NluRefusal.PARTIAL,
        )) {
            val r = rig(null, VoiceUserPhrases(), assistantReply = proposal())
            val entry = r.say(text)
            assertEquals(text, reason, entry.refusal)
            r.assertNothingRan(text)
        }
    }

    @Test fun partial_prefixes_of_work_and_home_are_refused_locally_with_ai_off() {
        for (text in listOf("Prowadź do domu babci", "Jedźmy do pracy przez rynek", "Nawiguj do pracy teraz")) {
            val r = rig(null, VoiceUserPhrases(), assistantEnabled = false)
            val entry = r.say(text)
            assertEquals(text, VoiceJournalEntry.Route.REFUSED, entry.route)
            r.assertNothingRan(text)
        }
    }

    @Test fun with_ai_on_only_an_unsupported_partial_prefix_reaches_the_assistant() {
        for (text in listOf("Prowadź do domu babci", "Jedźmy do pracy przez rynek", "Nawiguj do pracy teraz")) {
            val verdict = NluParser.classify(text, VoiceLanguage.PL)
            assertTrue("$text -> $verdict", verdict is NluVerdict.Refused)
            val unsupported = (verdict as NluVerdict.Refused).reason == NluRefusal.UNSUPPORTED
            val handler = mockk<AssistantActionHandler>(relaxed = true)
            coEvery { handler.handle(any()) } returns
                AssistantActionResult.Spoken("Nie mogę tego wykonać.", VoiceJournalEntry.Outcome.NOT_UNDERSTOOD, "openclaw:rejected")
            val r = rig(null, VoiceUserPhrases(), assistantActions = handler, assistantReply = proposal())
            r.say(text)
            coVerify(exactly = if (unsupported) 1 else 0) { r.assistant.ask(any(), any()) }
            assertEquals(text, emptyList<ActionDef>(), r.dispatched.toList())
        }
    }

    // --- relative temperature: a fresh snapshot with a valid 16..33 setpoint, else nothing runs ---

    private fun Rig.assertTemperatureSet(text: String, target: Int) {
        assertEquals(text, VoiceJournalEntry.Outcome.OK, journal.entries.value.first().outcome)
        assertEquals(text, listOf(ActionDef(command = "设置温度$target", displayName = "设置温度$target", kind = "param")), dispatched.toList())
        coVerify(exactly = 0) { assistant.ask(any(), any()) }
    }

    @Test fun relative_temperature_adds_the_signed_amount_to_the_fresh_setpoint() {
        for ((text, setpoint, target) in listOf(
            Triple("Podnieś temperaturę o dwa stopnie", 22, 24),
            Triple("Obniż o dwa stopnie", 22, 20),
            Triple("Podnieś temperaturę", 22, 23),
            Triple("Obniż temperaturę", 22, 21),
            Triple("Zrób cieplej", 22, 23),
            Triple("Zrób chłodniej", 22, 21),
            Triple("Podnieś o 5 stopni", 20, 25),
            // Both clamps.
            Triple("Podnieś temperaturę o dwa stopnie", 32, 33),
            Triple("Podnieś temperaturę", 33, 33),
            Triple("Obniż o dwa stopnie", 17, 16),
            Triple("Obniż temperaturę", 16, 16),
        )) {
            val r = rig(null, VoiceUserPhrases(), snapshot(ac = setpoint), ageMs = 1_000L)
            r.say(text)
            r.assertTemperatureSet("$text @ $setpoint", target)
        }
    }

    @Test fun relative_temperature_with_no_usable_setpoint_dispatches_and_asks_nothing() {
        data class Case(val label: String, val snapshot: DiParsData?, val ageMs: Long?)
        val cases = listOf(
            Case("no snapshot", null, null),
            Case("no snapshot but an age", null, 1_000L),
            Case("setpoint missing", snapshot(ac = null), 1_000L),
            Case("setpoint zero", snapshot(ac = 0), 1_000L),
            Case("setpoint below 16", snapshot(ac = 15), 1_000L),
            Case("setpoint above 33", snapshot(ac = 34), 1_000L),
            Case("age unknown", snapshot(ac = 22), null),
            Case("stale snapshot", snapshot(ac = 22), 30_001L),
        )
        for (text in listOf("Podnieś temperaturę o dwa stopnie", "Obniż o dwa stopnie", "Zrób cieplej", "Obniż temperaturę")) {
            for (case in cases) {
                val r = rig(null, VoiceUserPhrases(), case.snapshot, case.ageMs)
                val entry = r.say(text)
                val label = "$text / ${case.label}"
                assertEquals(label, VoiceJournalEntry.Route.NLU, entry.route)
                assertEquals(label, VoiceJournalEntry.Outcome.BLOCKED, entry.outcome)
                assertEquals(label, VoiceRefusal.gate("ac_temp_unknown"), entry.refusal)
                r.assertNothingRan(label)
            }
        }
    }

    @Test fun a_setpoint_exactly_as_old_as_the_question_limit_is_still_fresh() {
        val r = rig(null, VoiceUserPhrases(), snapshot(ac = 22), ageMs = 30_000L)
        r.say("Podnieś temperaturę o dwa stopnie")
        r.assertTemperatureSet("30 s", 24)
    }

    // --- questions: answered from the car, never sent on ---

    @Test fun natural_questions_are_answered_locally() {
        for ((text, command) in listOf(
            "Ile procent baterii" to "ask:soc",
            "Jaki mamy zasięg" to "ask:range",
            "Ile jest na dworze" to "ask:outside_temp",
        )) {
            val r = rig(null, VoiceUserPhrases(), snapshot(outside = 7, charge = 80), ageMs = 1_000L)
            val entry = r.say(text)
            assertEquals(text, VoiceJournalEntry.Route.NLU, entry.route)
            assertEquals(text, command, entry.command)
            r.assertNothingRan(text)
        }
    }

    // --- sequences: independently complete parameter commands only ---

    @Test fun a_potem_sequence_dispatches_each_command_in_order() {
        for (text in listOf("Zamknij okna potem otwórz roletę", "Zamknij okna i potem otwórz roletę")) {
            val r = rig(null, VoiceUserPhrases())
            val entry = r.say(text)
            assertEquals(text, VoiceJournalEntry.Outcome.OK, entry.outcome)
            assertEquals(
                text,
                listOf(
                    ActionDef(command = "车窗关闭", displayName = "车窗关闭", kind = "param"),
                    ActionDef(command = "遮阳帘打开", displayName = "遮阳帘打开", kind = "param"),
                ),
                r.dispatched.toList(),
            )
            coVerify(exactly = 0) { r.assistant.ask(any(), any()) }
        }
    }

    @Test fun a_sequence_stops_after_the_first_dispatch_failure() {
        val r = rig(null, VoiceUserPhrases(), failCommand = "车窗关闭")
        val entry = r.say("Zamknij okna potem otwórz roletę")
        assertEquals(VoiceJournalEntry.Outcome.BLOCKED, entry.outcome)
        assertEquals(VoiceRefusal.DISPATCH_FAILED, entry.refusal)
        assertEquals(listOf("车窗关闭"), r.dispatched.map { it.command })
        coVerify(exactly = 0) { r.assistant.ask(any(), any()) }
    }

    @Test fun a_sequence_with_a_typed_part_dispatches_nothing() {
        for (text in listOf(
            "Nawiguj do domu potem zamknij okna", "Zamknij okna potem podnieś temperaturę",
            "Zamknij okna i potem zrób ciszej", "Podnieś temperaturę o dwa stopnie potem otwórz roletę",
        )) {
            val r = rig(null, VoiceUserPhrases(), snapshot(ac = 22), ageMs = 1_000L)
            val entry = r.say(text)
            assertEquals(text, VoiceJournalEntry.Route.REFUSED, entry.route)
            assertEquals(text, NluRefusal.PARTIAL, entry.refusal)
            r.assertNothingRan(text)
        }
    }

    // --- assistant actions (issue 4, step 8): UNSUPPORTED goes to the assistant, a proposal never to speech ---

    private val unsupportedPhrase = "Włącz masaż fotela pasażera"
    private val prose = "NIEZAUFANA PROZA asystenta, której nie wolno wypowiedzieć"

    private fun proposal(turnKey: String = "turn-key-1", recovered: Boolean = false, startedAtMs: Long = 4_242L) =
        AssistantReply.Proposal(prose, ParsedEnvelope.Invalid(ActionRejection.MALFORMED), recovered, turnKey, startedAtMs)

    private fun spokenLines(r: Rig): List<String> {
        val texts = mutableListOf<String>()
        verify(atLeast = 0) { r.tts.speak(capture(texts)) }
        return texts
    }

    private val allowlist: WriteAllowlist by lazy {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/assets/competitor-actions.json").exists() }
            ?: error("Cannot find app/src/main/assets/competitor-actions.json from ${File(".").canonicalPath}")
        WriteAllowlist.loadProduction { File(root, "app/src/main/assets/competitor-actions.json").readText() }
    }

    @get:Rule val tmp = TemporaryFolder()

    @Test fun an_unsupported_request_with_ai_on_goes_to_the_assistant_once_and_a_massage_proposal_runs_nothing() {
        assertEquals(NluVerdict.Refused(NluRefusal.UNSUPPORTED), NluParser.classify(unsupportedPhrase, VoiceLanguage.PL))
        val registry = ActionRegistry(allowlist = allowlist)
        val executes = java.util.concurrent.atomic.AtomicInteger()
        val executor = object : ActionExecutor {
            override suspend fun execute(action: ValidatedAction, origin: ActionOrigin, hooks: ExecutionHooks): ActionOutcome {
                executes.incrementAndGet()
                return ActionOutcome.Done
            }
        }
        val handler = LedgerAssistantActionHandler(
            registry, RegistryActionValidator(registry), ActionLedger(File(tmp.root, "openclaw"), { "device-a" }),
            executor, AliasProposalSinkHolder(),
        )
        val key = "aaaaaaaa-0001-0000-0000-000000000000"
        val envelope = OpenClawActionProtocol.parseEnvelope(
            """{"schema":"bydmate.action/1","requestId":"${OpenClawProtocol.turnTag(key)}","proposalId":"prop-0001",""" +
                """"capabilities":"${registry.version}","type":"action","action":{"kind":"vehicle","id":"seat_massage_driver"}}""",
        )
        val massage = AssistantReply.Proposal(prose, envelope, recovered = false, turnKey = key, startedAtMs = System.currentTimeMillis())
        val r = rig(null, VoiceUserPhrases(), assistantActions = handler, assistantReply = massage, ttsEnabled = true)
        val entry = r.say(unsupportedPhrase)

        assertEquals(VoiceJournalEntry.Route.AGENT, entry.route)
        assertEquals("openclaw:rejected", entry.command)
        assertEquals(VoiceJournalEntry.Outcome.NOT_UNDERSTOOD, entry.outcome)
        assertEquals(NluRefusal.UNSUPPORTED, entry.refusal)
        coVerify(exactly = 1) { r.assistant.ask(any(), any()) }
        assertEquals(0, executes.get())
        assertEquals(emptyList<ActionDef>(), r.dispatched.toList())
        assertTrue(spokenLines(r).none { prose in it })
    }

    @Test fun an_unsupported_request_with_ai_off_stays_local_and_asks_nothing() {
        val r = rig(null, VoiceUserPhrases(), assistantEnabled = false)
        val entry = r.say(unsupportedPhrase)

        assertEquals(VoiceJournalEntry.Route.REFUSED, entry.route)
        assertEquals(NluRefusal.UNSUPPORTED, entry.refusal)
        r.assertNothingRan(unsupportedPhrase)
    }

    @Test fun negated_quoted_and_partial_requests_never_reach_the_assistant_even_with_ai_on() {
        for ((text, reason) in unsafe) {
            val r = rig(null, VoiceUserPhrases(), assistantReply = proposal())
            val entry = r.say(text)
            assertEquals(text, reason, entry.refusal)
            r.assertNothingRan(text)
        }
    }

    @Test fun incomplete_navigation_with_ai_on_asks_nothing_and_keeps_its_own_refusal() {
        val r = rig(null, VoiceUserPhrases(), assistantReply = proposal())
        val entry = r.say("nawiguj do")

        assertEquals(VoiceJournalEntry.Route.REFUSED, entry.route)
        assertEquals("command_nav_incomplete", entry.refusal)
        r.assertNothingRan("incomplete navigation")
    }

    @Test fun a_proposal_is_handed_to_the_handler_once_and_only_the_handlers_line_is_spoken() {
        val line = "Wykonano: ustaw temperaturę 22"
        val handler = mockk<AssistantActionHandler>(relaxed = true)
        val input = slot<ProposalInput>()
        coEvery { handler.handle(capture(input)) } returns
            AssistantActionResult.Spoken(line, VoiceJournalEntry.Outcome.OK, "openclaw:ac_set_temp")
        val r = rig(null, VoiceUserPhrases(), assistantActions = handler,
            assistantReply = proposal(turnKey = "turn-key-5", recovered = true, startedAtMs = 777L), ttsEnabled = true)
        val entry = r.say(unsupportedPhrase)

        coVerify(exactly = 1) { handler.handle(any()) }
        assertEquals("turn-key-5", input.captured.turnKey)
        assertTrue(input.captured.recovered)
        assertEquals(777L, input.captured.startedAtMs)
        assertEquals(ParsedEnvelope.Invalid(ActionRejection.MALFORMED), input.captured.envelope)
        assertEquals(VoiceJournalEntry.Route.AGENT, entry.route)
        assertEquals("openclaw:ac_set_temp", entry.command)
        assertEquals(VoiceJournalEntry.Outcome.OK, entry.outcome)
        val spoken = spokenLines(r)
        assertEquals(listOf(line), spoken)
        assertTrue(r.journal.entries.value.none { prose in it.detail || prose in (it.answer ?: "") || prose in (it.reason ?: "") })
        assertEquals(emptyList<ActionDef>(), r.dispatched.toList())
    }

    @Test fun a_blocked_or_failed_proposal_is_journalled_with_its_outcome_and_speaks_only_the_handlers_line() {
        for (outcome in listOf(VoiceJournalEntry.Outcome.BLOCKED, VoiceJournalEntry.Outcome.ERROR)) {
            val handler = mockk<AssistantActionHandler>(relaxed = true)
            coEvery { handler.handle(any()) } returns AssistantActionResult.Spoken("Nie mogę tego wykonać.", outcome, "openclaw:rejected")
            val r = rig(null, VoiceUserPhrases(), assistantActions = handler, assistantReply = proposal(), ttsEnabled = true)
            val entry = r.say(unsupportedPhrase)

            assertEquals(outcome, entry.outcome)
            assertEquals("openclaw:rejected", entry.command)
            assertEquals(listOf("Nie mogę tego wykonać."), spokenLines(r))
        }
    }

    @Test fun a_silent_handler_result_speaks_and_journals_nothing_for_the_proposal() {
        val handler = mockk<AssistantActionHandler>(relaxed = true)
        coEvery { handler.handle(any()) } returns AssistantActionResult.Silent
        val r = rig(null, VoiceUserPhrases(), assistantActions = handler, assistantReply = proposal(), ttsEnabled = true)
        r.controller.onPttPressed()
        awaitTrue { r.controller.listening.value }
        awaitTrue { r.asr.events.subscriptionCount.value >= 1 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(unsupportedPhrase))
        awaitTrue { runCatching { coVerify(exactly = 1) { handler.handle(any()) } }.isSuccess }
        Thread.sleep(200)

        assertEquals(emptyList<String>(), spokenLines(r))
        assertEquals(emptyList<VoiceJournalEntry>(), r.journal.entries.value)
    }

    @Test fun an_alias_proposal_is_journalled_and_announced_without_the_assistants_prose() {
        val handler = mockk<AssistantActionHandler>(relaxed = true)
        val alias = com.bydmate.app.voice.openclaw.AliasProposal(
            "req", "prop", "moja fraza",
            (com.bydmate.app.agent.RegistryActionValidator(
                com.bydmate.app.agent.ActionRegistry(allowlist = com.bydmate.app.data.vehicle.WriteAllowlist.EMPTY),
            ).validate(com.bydmate.app.agent.ActionCandidate("navigate", shortcut = "home"), null)
                as com.bydmate.app.agent.ActionValidation.Valid).action,
            "Nawigacja do domu",
        )
        coEvery { handler.handle(any()) } returns AssistantActionResult.AliasPresented(alias)
        val r = rig(null, VoiceUserPhrases(), assistantActions = handler, assistantReply = proposal(), ttsEnabled = true)
        val entry = r.say(unsupportedPhrase)

        assertEquals(VoiceJournalEntry.Route.AGENT, entry.route)
        assertEquals("openclaw:alias", entry.command)
        val spoken = spokenLines(r)
        assertEquals(1, spoken.size)
        assertTrue(spoken.single(), prose !in spoken.single())
    }

    @Test fun a_name_barge_in_while_the_handler_waits_cancels_the_turn_without_speech() {
        val entered = CompletableDeferred<Unit>()
        val handler = mockk<AssistantActionHandler>(relaxed = true)
        coEvery { handler.handle(any()) } coAnswers { entered.complete(Unit); awaitCancellation() }
        val r = rig(null, VoiceUserPhrases(), assistantActions = handler, assistantReply = proposal(),
            agentName = "Bob", ttsEnabled = true)
        r.controller.onPttPressed()
        awaitTrue { r.controller.listening.value }
        awaitTrue { r.asr.events.subscriptionCount.value >= 1 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(unsupportedPhrase))
        awaitTrue { entered.isCompleted }

        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance("Bob"))
        awaitTrue { r.journal.entries.value.any { it.route == VoiceJournalEntry.Route.AGENT && it.outcome == VoiceJournalEntry.Outcome.ERROR } }

        val cancelled = r.journal.entries.value.first { it.outcome == VoiceJournalEntry.Outcome.ERROR }
        assertEquals(VoiceRefusal.BARGE_IN, cancelled.refusal)
        assertEquals(emptyList<String>(), spokenLines(r))
        assertEquals(emptyList<ActionDef>(), r.dispatched.toList())
    }

    @Suppress("SwallowedException") // The handler fixture deliberately finishes after cancellation to return the true write result.
    @Test fun a_turn_cancelled_after_dispatch_journals_the_true_outcome_and_stays_silent() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val handler = mockk<AssistantActionHandler>(relaxed = true)
        // Like the real handler: a caller that goes away after the write still gets the settled result.
        coEvery { handler.handle(any()) } coAnswers {
            entered.complete(Unit)
            try { awaitCancellation() } catch (e: kotlinx.coroutines.CancellationException) { withContext(NonCancellable) { release.await() } }
            AssistantActionResult.Spoken("Wykonano: ustaw temperaturę 22", VoiceJournalEntry.Outcome.OK, "openclaw:ac_set_temp")
        }
        val r = rig(null, VoiceUserPhrases(), assistantActions = handler, assistantReply = proposal(),
            agentName = "Bob", ttsEnabled = true)
        r.controller.onPttPressed()
        awaitTrue { r.controller.listening.value }
        awaitTrue { r.asr.events.subscriptionCount.value >= 1 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(unsupportedPhrase))
        awaitTrue { entered.isCompleted }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance("Bob"))
        awaitTrue { r.journal.entries.value.any { it.refusal == VoiceRefusal.BARGE_IN } }
        release.complete(Unit)
        awaitTrue { r.journal.entries.value.any { it.command == "openclaw:ac_set_temp" } }

        val done = r.journal.entries.value.first { it.command == "openclaw:ac_set_temp" }
        assertEquals(VoiceJournalEntry.Outcome.OK, done.outcome)
        assertEquals(emptyList<String>(), spokenLines(r))
    }

    @Test fun a_local_phrase_during_a_pending_proposal_takes_priority_and_runs() {
        val entered = CompletableDeferred<Unit>()
        val handler = mockk<AssistantActionHandler>(relaxed = true)
        coEvery { handler.handle(any()) } coAnswers { entered.complete(Unit); awaitCancellation() }
        val r = rig(null, VoiceUserPhrases(), assistantActions = handler, assistantReply = proposal(), ttsEnabled = true)
        r.controller.onPttPressed()
        awaitTrue { r.controller.listening.value }
        awaitTrue { r.asr.events.subscriptionCount.value >= 1 }
        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance(unsupportedPhrase))
        awaitTrue { entered.isCompleted }

        r.asr.events.tryEmit(ContinuousAsrEvent.Utterance("Nawiguj do domu"))
        awaitTrue { r.dispatched.isNotEmpty() }

        assertEquals(listOf("home"), r.dispatched.map { JSONObject(it.payload).getString("shortcut") })
        assertTrue(r.journal.entries.value.any { it.refusal == VoiceRefusal.LOCAL_PRIORITY && it.outcome == VoiceJournalEntry.Outcome.ERROR })
        coVerify(exactly = 1) { handler.handle(any()) }
        assertTrue(spokenLines(r).none { prose in it })
    }

    // --- controls: the same saved-phrase path still works for an ordinary exact phrase ---

    @Test fun `control - an ordinary exact custom user phrase still dispatches and skips the assistant`() {
        val text = "wietrzymy kabinę teraz"
        assertEquals("the control phrase must be harmless to the grammar", NluVerdict.Unknown, NluParser.classify(text, VoiceLanguage.PL))
        val r = rig(null, phrases(text))
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals("phrase:${command.id}", entry.command)
        assertEquals(listOf(VoiceAlias(text, AliasTarget.Vehicle(command.id))), r.aliasRuns.toList())
        assertEquals(emptyList<ActionDef>(), r.dispatched.toList())
        coVerify(exactly = 0) { r.assistant.ask(any(), any()) }
    }

    @Test fun `control - an ordinary exact custom automation phrase still fires`() {
        val text = "wietrzymy kabinę teraz"
        val r = rig(text, VoiceUserPhrases())
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Route.AUTOMATION, entry.route)
        coVerify(exactly = 1) { r.engine.fireVoiceRule(9L, any()) }
        coVerify(exactly = 0) { r.assistant.ask(any(), any()) }
        assertTrue(r.dispatched.isEmpty())
    }

    // --- typed saved aliases run only through the coordinator ---

    private fun aliasPhrases(phrase: String, target: AliasTarget) =
        VoiceUserPhrases().apply { assertEquals(SaveResult.Saved, saveAlias(target, phrase)) }

    @Test fun `an exact destination alias runs once through the coordinator and never the local dispatcher`() {
        val text = "jadę do moich rodziców"
        assertEquals(NluVerdict.Unknown, NluParser.classify(text, VoiceLanguage.PL))
        val target = AliasTarget.NavigateQuery("Rodzice, Kraków")
        val r = rig(null, aliasPhrases(text, target))
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Route.NLU, entry.route)
        assertEquals(VoiceJournalEntry.Outcome.OK, entry.outcome)
        assertEquals("phrase:navigate", entry.command)
        assertEquals(listOf(VoiceAlias(text, target)), r.aliasRuns.toList())
        assertEquals(emptyList<ActionDef>(), r.dispatched.toList())
        coVerify(exactly = 0) { r.assistant.ask(any(), any()) }
    }

    @Test fun `an exact ranged fixed-value alias runs once through the coordinator`() {
        val text = "zrób mi tu przytulnie"
        assertEquals(NluVerdict.Unknown, NluParser.classify(text, VoiceLanguage.PL))
        val target = AliasTarget.Vehicle("ac_set_temp", 22)
        val r = rig(null, aliasPhrases(text, target))
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Outcome.OK, entry.outcome)
        assertEquals("phrase:ac_set_temp", entry.command)
        assertEquals(listOf(VoiceAlias(text, target)), r.aliasRuns.toList())
        assertEquals(emptyList<ActionDef>(), r.dispatched.toList())
    }

    @Test fun `an alias beats a normal grammar parse of the same phrase`() {
        val text = "nawiguj do domu"
        assertTrue(NluParser.classify(text, VoiceLanguage.PL) is NluVerdict.Match)
        val target = AliasTarget.Vehicle("ac_set_temp", 19)
        val r = rig(null, aliasPhrases(text, target))
        val entry = r.say(text)

        assertEquals("phrase:ac_set_temp", entry.command)
        assertEquals(listOf(VoiceAlias(text, target)), r.aliasRuns.toList())
        assertEquals(emptyList<ActionDef>(), r.dispatched.toList())
    }

    @Test fun `an automation match beats an alias with the same phrase`() {
        val text = "wietrzymy kabinę teraz"
        val r = rig(text, aliasPhrases(text, AliasTarget.NavigateShortcut("home")))
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Route.AUTOMATION, entry.route)
        coVerify(exactly = 1) { r.engine.fireVoiceRule(9L, any()) }
        assertEquals(emptyList<VoiceAlias>(), r.aliasRuns.toList())
    }

    @Test fun `an unavailable retained alias is refused locally even with the assistant enabled`() {
        val text = "wietrzymy kabinę teraz"
        val retained = AliasTarget.Unavailable("vehicle", "gone_command", "{}")
        val r = rig(null, aliasPhrases(text, retained), assistantEnabled = true)
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Route.REFUSED, entry.route)
        assertEquals(NluRefusal.UNSUPPORTED, entry.refusal)
        assertEquals(emptyList<VoiceAlias>(), r.aliasRuns.toList())
        r.assertNothingRan(text)
    }

    @Test fun `a coordinator refusal of a matched alias is a safe local refusal`() {
        val text = "wietrzymy kabinę teraz"
        for (result in listOf(AliasRunResult.Rejected(ActionRejection.UNKNOWN_COMMAND), AliasRunResult.NotExecutable)) {
            val r = rig(null, aliasPhrases(text, AliasTarget.Vehicle("ac_set_temp", 22)), aliasResult = result)
            val entry = r.say(text)

            assertEquals(VoiceJournalEntry.Route.REFUSED, entry.route)
            assertEquals(NluRefusal.UNSUPPORTED, entry.refusal)
            assertEquals(listOf(1), listOf(r.aliasRuns.size))
            coVerify(exactly = 0) { r.assistant.ask(any(), any()) }
        }
    }

    @Test fun `every alias execution outcome is journaled faithfully with safe text`() {
        val text = "wietrzymy kabinę teraz"
        val target = AliasTarget.Vehicle("ac_set_temp", 22)
        val cases = listOf(
            ActionOutcome.Done to VoiceJournalEntry.Outcome.OK,
            ActionOutcome.Failed("raw_code_xyz") to VoiceJournalEntry.Outcome.ERROR,
            ActionOutcome.Blocked("raw_gate_code", "Zbyt duża prędkość") to VoiceJournalEntry.Outcome.BLOCKED,
            ActionOutcome.Declined to VoiceJournalEntry.Outcome.BLOCKED,
            ActionOutcome.TimedOut to VoiceJournalEntry.Outcome.BLOCKED,
            ActionOutcome.ConfirmUnavailable to VoiceJournalEntry.Outcome.BLOCKED,
            ActionOutcome.Cancelled to VoiceJournalEntry.Outcome.BLOCKED,
        )
        for ((outcome, journaled) in cases) {
            val r = rig(null, aliasPhrases(text, target), aliasResult = AliasRunResult.Executed(outcome), ttsEnabled = true)
            val entry = r.say(text)

            assertEquals(outcome.toString(), journaled, entry.outcome)
            assertEquals(outcome.toString(), 1, r.aliasRuns.size)
            val visible = listOfNotNull(entry.reason, entry.refusal) + spokenLines(r)
            assertTrue(outcome.toString(), visible.none { "raw_code_xyz" in it || "raw_gate_code" in it || "Declined" in it })
        }
    }

    @Test fun `a thrown coordinator error is journaled as an error without its text`() {
        val text = "wietrzymy kabinę teraz"
        val r = rig(null, aliasPhrases(text, AliasTarget.Vehicle("ac_set_temp", 22)))
        coEvery { r.aliasCoordinator.run(any()) } throws IllegalStateException("secret internal detail")
        val entry = r.say(text)

        assertEquals(VoiceJournalEntry.Outcome.ERROR, entry.outcome)
        assertTrue(listOfNotNull(entry.reason, entry.refusal).none { "secret internal detail" in it })
    }

    private fun phoneBook(vararg names: String) = object : VoiceContacts {
        override fun hasPermission(): Boolean = true
        override suspend fun all(): List<VoiceContact> = names.mapIndexed { i, n -> VoiceContact(n, "+4860000000$i") }
    }

    private class RecordingConfirmer(private val answer: ConfirmResult) : ActionConfirmer {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun confirm(title: String, summary: String): ConfirmResult {
            asked += summary
            return answer
        }
    }

    @Test fun a_misheard_contact_name_is_called_offline_without_confirmation() {
        val confirmer = RecordingConfirmer(ConfirmResult.DECLINED)
        val rig = rig(null, VoiceUserPhrases(), contacts = phoneBook("Jan Kowalski", "Jola Gil"), confirmer = confirmer)
        val entry = rig.say("zadzwon do Yola Gil")
        awaitTrue { rig.dispatched.isNotEmpty() }
        val call = rig.dispatched.single()
        assertEquals("call", call.kind)
        assertEquals("+48600000001", JSONObject(call.payload!!).getString("phone"))
        assertEquals(true, JSONObject(call.payload!!).getBoolean("autoDial"))
        assertEquals(VoiceJournalEntry.Outcome.OK, entry.outcome)
        assertEquals(false, entry.toString().contains("+48600000001"))
        assertEquals(emptyList<String>(), confirmer.asked.toList())
        coVerify(exactly = 0) { rig.assistant.ask(any(), any()) }
    }

    @Test fun a_loose_contact_match_is_confirmed_before_the_call() {
        val confirmer = RecordingConfirmer(ConfirmResult.CONFIRMED)
        val rig = rig(null, VoiceUserPhrases(), contacts = phoneBook("Jola Gil"), confirmer = confirmer)
        rig.say("zadzwoń do Jolanta Gil")
        awaitTrue { rig.dispatched.isNotEmpty() }
        assertEquals(listOf("Zadzwonić do Jola Gil?"), confirmer.asked.toList())
        assertEquals("+48600000000", JSONObject(rig.dispatched.single().payload!!).getString("phone"))
        coVerify(exactly = 0) { rig.assistant.ask(any(), any()) }
    }

    @Test fun a_declined_or_unavailable_confirmation_never_calls() {
        for (answer in listOf(ConfirmResult.DECLINED, ConfirmResult.TIMED_OUT, ConfirmResult.UNAVAILABLE)) {
            val confirmer = RecordingConfirmer(answer)
            val rig = rig(null, VoiceUserPhrases(), contacts = phoneBook("Jola Gil"), confirmer = confirmer)
            val entry = rig.say("zadzwoń do Jolanta Gil")
            assertEquals(answer.name, VoiceJournalEntry.Outcome.BLOCKED, entry.outcome)
            assertEquals(answer.name, 1, confirmer.asked.size)
            rig.assertNothingRan("zadzwoń do Jolanta Gil")
        }
    }

    @Test fun an_unknown_contact_or_missing_permission_is_answered_locally() {
        val unknown = rig(null, VoiceUserPhrases(), contacts = phoneBook("Mama"))
        val entry = unknown.say("zadzwoń do Zbigniewa Brzęczyszczykiewicza")
        assertEquals(VoiceRefusal.gate("contact_not_found"), entry.refusal)
        unknown.assertNothingRan("unknown")

        val noPermission = rig(null, VoiceUserPhrases())
        val denied = noPermission.say("zadzwoń do mamy")
        assertEquals(VoiceRefusal.gate("contacts_permission"), denied.refusal)
        noPermission.assertNothingRan("no permission")
    }

    @Test fun a_negated_call_never_dials_or_reaches_the_assistant() {
        val rig = rig(null, VoiceUserPhrases(), contacts = phoneBook("Mama"))
        val entry = rig.say("nie dzwoń do mamy")
        assertEquals(NluRefusal.NEGATED, entry.refusal)
        rig.assertNothingRan("nie dzwoń do mamy")
    }
}
