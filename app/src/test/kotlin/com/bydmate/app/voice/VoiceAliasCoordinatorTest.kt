package com.bydmate.app.voice

import com.bydmate.app.agent.ActionCandidate
import com.bydmate.app.agent.ActionExecutor
import com.bydmate.app.agent.ActionOrigin
import com.bydmate.app.agent.ActionOutcome
import com.bydmate.app.agent.ActionRejection
import com.bydmate.app.agent.ActionValidating
import com.bydmate.app.agent.ActionValidation
import com.bydmate.app.agent.AgentCommandCatalog
import com.bydmate.app.agent.ExecutionHooks
import com.bydmate.app.agent.ValidatedAction
import com.bydmate.app.voice.openclaw.AliasProposal
import com.bydmate.app.voice.openclaw.AliasProposalSinkHolder
import com.bydmate.app.voice.openclaw.AssistantActionHandler
import com.bydmate.app.voice.openclaw.NoAssistantActions
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceAliasCoordinatorTest {
    private val close = "windows_close_all"
    private val setTemp = "ac_set_temp"

    /** Accepts every candidate the fake registry knows; [revoked] ids fail as if removed from the catalog. */
    private class FakeValidator : ActionValidating {
        val calls = mutableListOf<ActionCandidate>()
        val revoked = mutableSetOf<String>()
        var destinationsRevoked = false
        var ranges = mapOf("ac_set_temp" to 16..33)

        override fun validate(candidate: ActionCandidate, registryVersion: String?): ActionValidation {
            calls += candidate
            return when (candidate.kind) {
                "vehicle" -> {
                    val id = candidate.id ?: return ActionValidation.Invalid(ActionRejection.UNKNOWN_COMMAND)
                    if (id in revoked) return ActionValidation.Invalid(ActionRejection.UNKNOWN_COMMAND)
                    val range = ranges[id]
                    val value = candidate.value
                    if (range != null && (value == null || value !in range)) {
                        return ActionValidation.Invalid(ActionRejection.VALUE_OUT_OF_RANGE)
                    }
                    ActionValidation.Valid(ValidatedAction.Vehicle(id, value))
                }
                "navigate" -> when {
                    destinationsRevoked -> ActionValidation.Invalid(ActionRejection.DESTINATION_INVALID)
                    candidate.query != null -> ActionValidation.Valid(ValidatedAction.NavigateQuery(candidate.query!!))
                    candidate.shortcut == "home" ->
                        ActionValidation.Valid(ValidatedAction.NavigateShortcut(ValidatedAction.Shortcut.HOME))
                    else -> ActionValidation.Invalid(ActionRejection.DESTINATION_INVALID)
                }
                else -> ActionValidation.Invalid(ActionRejection.UNKNOWN_KIND)
            }
        }
    }

    private class FakeExecutor : ActionExecutor {
        val runs = mutableListOf<Pair<ValidatedAction, ActionOrigin>>()
        var outcome: ActionOutcome = ActionOutcome.Done
        override suspend fun execute(action: ValidatedAction, origin: ActionOrigin, hooks: ExecutionHooks): ActionOutcome {
            runs += action to origin
            return outcome
        }
    }

    /** Records terminal callbacks and what the store held when each arrived. */
    private class FakeHandler(private val store: VoiceUserPhrases) : AssistantActionHandler by NoAssistantActions {
        val saved = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        val aliasesAtSaved = mutableListOf<List<VoiceAlias>>()
        override fun aliasSaved(proposalId: String) { saved += proposalId; aliasesAtSaved += store.aliases.value }
        override fun aliasDismissed(proposalId: String) { dismissed += proposalId }
    }

    /** In-memory SharedPreferences for the alias key, so a second store instance can reload what Save wrote. */
    private class MemoryPrefs {
        @Volatile var stored: String? = null
        val prefs: SharedPreferences = mockk {
            every { getString(VoiceUserPhrases.KEY, any()) } answers { stored }
            every { edit() } answers {
                var pending: String? = null
                val editor = mockk<SharedPreferences.Editor>(relaxed = true)
                every { editor.putString(VoiceUserPhrases.KEY, any()) } answers { pending = secondArg(); editor }
                every { editor.commit() } answers { stored = pending; true }
                editor
            }
        }
    }

    private class Rig(
        automation: Map<String, String> = emptyMap(),
        prefs: SharedPreferences? = null,
        /** When set, the automation-phrase query suspends until it completes (a held query). */
        gate: CompletableDeferred<Unit>? = null,
    ) {
        val store = VoiceUserPhrases(prefs)
        val holder = AliasProposalSinkHolder()
        val validator = FakeValidator()
        val executor = FakeExecutor()
        val handler = FakeHandler(store)
        val coordinator = VoiceAliasCoordinator(
            holder, validator, executor, { handler }, store,
            summarize = { "LOCAL:" + (it as? ValidatedAction.NavigateQuery)?.query },
            automationPhrases = { gate?.await(); automation },
        )
    }

    private fun proposal(
        id: String,
        action: ValidatedAction,
        phrase: String = "do rodziców",
    ) = AliasProposal("req-$id", id, phrase, action, "ASSISTANT TEXT")

    private fun nav(query: String = "Rodzice, Kraków") = ValidatedAction.NavigateQuery(query)

    @Test fun `creating the coordinator binds the holder`() {
        val rig = Rig()
        assertTrue(rig.holder.bound)
    }

    @Test fun `an invalid proposal returns false and exposes no UI`() {
        val rig = Rig()
        rig.validator.revoked += close
        assertFalse(rig.holder.present(proposal("p1", ValidatedAction.Vehicle(close, null))))
        assertNull(rig.coordinator.pending.value)
        assertTrue(rig.handler.saved.isEmpty() && rig.handler.dismissed.isEmpty())
    }

    @Test fun `a valid proposal is shown with a local summary`() {
        val rig = Rig()
        assertTrue(rig.holder.present(proposal("p1", nav())))
        val pending = rig.coordinator.pending.value
        assertEquals(PendingAliasProposal("p1", "do rodziców", "LOCAL:Rodzice, Kraków"), pending)
        assertEquals(1, rig.validator.calls.size)
    }

    @Test fun `control characters are removed from the displayed phrase`() {
        val rig = Rig()
        assertTrue(rig.holder.present(proposal("p1", nav(), phrase = "do\n rodziców\u0007")))
        assertEquals("do rodziców", rig.coordinator.pending.value?.phrase)
    }

    // A fixed catalog command the offline grammar does not have: approved teaching scope covers it.
    private val fixedOutsideOffline = "ac_wind_face"

    @Test fun `a fixed catalog command outside the offline set is taught saved reloaded and run`() = runBlocking {
        assertTrue(VoiceUserPhrases.COMMANDS.none { it.id == fixedOutsideOffline })
        val entry = AgentCommandCatalog.ALL.single { it.id == fixedOutsideOffline }
        assertNull(entry.value)

        val disk = MemoryPrefs()
        val rig = Rig(prefs = disk.prefs)
        assertTrue(rig.holder.present(proposal("p1", ValidatedAction.Vehicle(fixedOutsideOffline, null), phrase = "nawiew na twarz")))
        assertEquals("p1", rig.coordinator.pending.value?.proposalId)
        assertEquals(AliasSaveResult.Saved, rig.coordinator.save("p1"))
        assertEquals(listOf("p1"), rig.handler.saved)

        val reloaded = VoiceUserPhrases(disk.prefs)
        val alias = reloaded.matchAlias("nawiew na twarz")
        assertEquals(VoiceAlias("nawiew na twarz", AliasTarget.Vehicle(fixedOutsideOffline)), alias)
        assertTrue(alias!!.executable)

        assertEquals(AliasRunResult.Executed(ActionOutcome.Done), rig.coordinator.run(alias))
        val (action, origin) = rig.executor.runs.single()
        assertEquals(ActionOrigin.USER_ALIAS, origin)
        assertEquals(fixedOutsideOffline, (action as ValidatedAction.Vehicle).commandId)
        assertNull((action as ValidatedAction.Vehicle).value)
    }

    @Test fun `a target outside the catalog is not offered`() {
        val rig = Rig()
        assertFalse(rig.holder.present(proposal("p1", ValidatedAction.Vehicle("no_such_command", null))))
        assertNull(rig.coordinator.pending.value)
    }

    @Test fun `save validates again writes durably then reports saved once`() = runBlocking {
        val rig = Rig()
        rig.holder.present(proposal("p1", nav()))
        assertEquals(AliasSaveResult.Saved, rig.coordinator.save("p1"))
        assertEquals(2, rig.validator.calls.size)
        assertEquals(listOf(VoiceAlias("do rodziców", AliasTarget.NavigateQuery("Rodzice, Kraków"))), rig.store.aliases.value)
        assertEquals(listOf("p1"), rig.handler.saved)
        // The write was already visible when the callback ran.
        assertEquals(rig.store.aliases.value, rig.handler.aliasesAtSaved.single())
        assertNull(rig.coordinator.pending.value)
        assertEquals(AliasSaveResult.NotPending, rig.coordinator.save("p1"))
        assertEquals(listOf("p1"), rig.handler.saved)
        assertEquals(1, rig.store.aliases.value.size)
    }

    @Test fun `dismiss reports dismissed once and writes nothing`() {
        val rig = Rig()
        rig.holder.present(proposal("p1", nav()))
        rig.coordinator.dismiss("p1")
        rig.coordinator.dismiss("p1")
        assertEquals(listOf("p1"), rig.handler.dismissed)
        assertTrue(rig.handler.saved.isEmpty())
        assertTrue(rig.store.aliases.value.isEmpty())
        assertNull(rig.coordinator.pending.value)
    }

    @Test fun `a terminal or pending id never reopens the UI`() = runBlocking {
        val rig = Rig()
        assertTrue(rig.holder.present(proposal("p1", nav())))
        assertFalse(rig.holder.present(proposal("p1", nav("inne"))))
        assertEquals("LOCAL:Rodzice, Kraków", rig.coordinator.pending.value?.summary)
        rig.coordinator.save("p1")
        assertFalse(rig.holder.present(proposal("p1", nav())))
        assertNull(rig.coordinator.pending.value)

        assertTrue(rig.holder.present(proposal("p2", nav("Praca"), phrase = "do firmy")))
        rig.coordinator.dismiss("p2")
        assertFalse(rig.holder.present(proposal("p2", nav("Praca"), phrase = "do firmy")))
        assertNull(rig.coordinator.pending.value)
    }

    @Test fun `a newer proposal dismisses the unanswered one`() {
        val rig = Rig()
        rig.holder.present(proposal("p1", nav()))
        assertTrue(rig.holder.present(proposal("p2", nav("Praca"), phrase = "do firmy")))
        assertEquals(listOf("p1"), rig.handler.dismissed)
        assertEquals("p2", rig.coordinator.pending.value?.proposalId)
        assertTrue(rig.store.aliases.value.isEmpty())
    }

    @Test fun `a target revoked before save is not written and is closed`() = runBlocking {
        val rig = Rig()
        assertTrue(rig.holder.present(proposal("p1", nav())))
        rig.validator.destinationsRevoked = true
        assertEquals(AliasSaveResult.Unavailable, rig.coordinator.save("p1"))
        assertTrue(rig.store.aliases.value.isEmpty())
        assertTrue(rig.handler.saved.isEmpty())
        assertEquals(listOf("p1"), rig.handler.dismissed)
        assertNull(rig.coordinator.pending.value)
        assertFalse(rig.holder.present(proposal("p1", nav())))
    }

    // --- a Save suspended on the automation query must not outlive its proposal ---

    @Test fun `a held save whose proposal is superseded writes nothing and fires each callback once`() = runBlocking {
        val disk = MemoryPrefs()
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(prefs = disk.prefs, gate = gate)
        assertTrue(rig.holder.present(proposal("p1", nav())))
        val held = async(start = CoroutineStart.UNDISPATCHED) { rig.coordinator.save("p1") }

        assertTrue(rig.holder.present(proposal("p2", nav("Praca"), phrase = "do firmy")))
        gate.complete(Unit)

        assertEquals(AliasSaveResult.NotPending, held.await())
        assertTrue(rig.store.aliases.value.isEmpty())
        assertNull(disk.stored)
        assertEquals(listOf("p1"), rig.handler.dismissed)
        assertTrue(rig.handler.saved.isEmpty())
        assertEquals("p2", rig.coordinator.pending.value?.proposalId)
        // p2 is still open and its own Save still works.
        assertEquals(AliasSaveResult.Saved, rig.coordinator.save("p2"))
        assertEquals(listOf("p2"), rig.handler.saved)
        assertEquals(listOf("p1"), rig.handler.dismissed)
    }

    @Test fun `a held save whose proposal is dismissed writes nothing and fires dismissed once`() = runBlocking {
        val disk = MemoryPrefs()
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(prefs = disk.prefs, gate = gate)
        rig.holder.present(proposal("p1", nav()))
        val held = async(start = CoroutineStart.UNDISPATCHED) { rig.coordinator.save("p1") }

        rig.coordinator.dismiss("p1")
        gate.complete(Unit)

        assertEquals(AliasSaveResult.NotPending, held.await())
        assertTrue(rig.store.aliases.value.isEmpty())
        assertNull(disk.stored)
        assertEquals(listOf("p1"), rig.handler.dismissed)
        assertTrue(rig.handler.saved.isEmpty())
        assertNull(rig.coordinator.pending.value)
    }

    @Test fun `a held save of the still-current proposal saves once`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(gate = gate)
        rig.holder.present(proposal("p1", nav()))
        val held = async(start = CoroutineStart.UNDISPATCHED) { rig.coordinator.save("p1") }
        gate.complete(Unit)
        assertEquals(AliasSaveResult.Saved, held.await())
        assertEquals(1, rig.store.aliases.value.size)
        assertEquals(listOf("p1"), rig.handler.saved)
        assertTrue(rig.handler.dismissed.isEmpty())
    }

    @Test fun `a target revoked while the save is held is not written and is closed once`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(gate = gate)
        rig.holder.present(proposal("p1", nav()))
        val held = async(start = CoroutineStart.UNDISPATCHED) { rig.coordinator.save("p1") }
        rig.validator.destinationsRevoked = true
        gate.complete(Unit)
        assertEquals(AliasSaveResult.Unavailable, held.await())
        assertTrue(rig.store.aliases.value.isEmpty())
        assertEquals(listOf("p1"), rig.handler.dismissed)
        assertTrue(rig.handler.saved.isEmpty())
    }

    @Test fun `a double Save held on the query writes and reports saved once`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(gate = gate)
        rig.holder.present(proposal("p1", nav()))
        val first = async(start = CoroutineStart.UNDISPATCHED) { rig.coordinator.save("p1") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { rig.coordinator.save("p1") }
        gate.complete(Unit)
        assertEquals(setOf(AliasSaveResult.Saved, AliasSaveResult.NotPending), setOf(first.await(), second.await()))
        assertEquals(1, rig.store.aliases.value.size)
        assertEquals(listOf("p1"), rig.handler.saved)
    }

    @Test fun `an automation phrase blocks the save and keeps the proposal open`() = runBlocking {
        val rig = Rig(automation = mapOf(VoicePhrase.normalize("do rodziców") to "Reguła"))
        rig.holder.present(proposal("p1", nav()))
        assertEquals(
            AliasSaveResult.Rejected(VoiceUserPhrases.Check.InUse("Reguła")),
            rig.coordinator.save("p1"),
        )
        assertTrue(rig.store.aliases.value.isEmpty())
        assertTrue(rig.handler.saved.isEmpty())
        assertEquals("p1", rig.coordinator.pending.value?.proposalId)
    }

    @Test fun `a destination alias runs once through the executor as USER_ALIAS`() = runBlocking {
        val rig = Rig()
        val alias = VoiceAlias("do rodziców", AliasTarget.NavigateQuery("Rodzice, Kraków"))
        val result = rig.coordinator.run(alias)
        assertEquals(AliasRunResult.Executed(ActionOutcome.Done), result)
        val (action, origin) = rig.executor.runs.single()
        assertEquals(ActionOrigin.USER_ALIAS, origin)
        assertEquals("Rodzice, Kraków", (action as ValidatedAction.NavigateQuery).query)
    }

    @Test fun `the executor outcome of a run is returned unchanged and the alias is not retried`() = runBlocking {
        val outcomes = listOf(
            ActionOutcome.Failed("x"), ActionOutcome.Blocked("g", "t"), ActionOutcome.Declined,
            ActionOutcome.TimedOut, ActionOutcome.ConfirmUnavailable, ActionOutcome.Cancelled,
        )
        for (outcome in outcomes) {
            val rig = Rig().also { it.executor.outcome = outcome }
            val result = rig.coordinator.run(VoiceAlias("do domu", AliasTarget.NavigateShortcut("home")))
            assertEquals(AliasRunResult.Executed(outcome), result)
            assertEquals(1, rig.executor.runs.size)
            assertEquals(ActionOrigin.USER_ALIAS, rig.executor.runs.single().second)
        }
    }

    @Test fun `a ranged fixed value runs once with its value`() = runBlocking {
        val rig = Rig()
        val result = rig.coordinator.run(VoiceAlias("ciepło", AliasTarget.Vehicle(setTemp, 22)))
        assertEquals(AliasRunResult.Executed(ActionOutcome.Done), result)
        val (action, origin) = rig.executor.runs.single()
        assertEquals(ActionOrigin.USER_ALIAS, origin)
        val vehicle = action as ValidatedAction.Vehicle
        assertEquals(setTemp, vehicle.commandId)
        assertEquals(22, vehicle.value)
    }

    @Test fun `a revoked or out of range target does not run`() = runBlocking {
        val rig = Rig()
        rig.validator.revoked += close
        assertEquals(
            AliasRunResult.Rejected(ActionRejection.UNKNOWN_COMMAND),
            rig.coordinator.run(VoiceAlias("zamknij", AliasTarget.Vehicle(close))),
        )
        assertEquals(
            AliasRunResult.Rejected(ActionRejection.VALUE_OUT_OF_RANGE),
            rig.coordinator.run(VoiceAlias("zimno", AliasTarget.Vehicle(setTemp, 99))),
        )
        assertTrue(rig.executor.runs.isEmpty())
    }

    @Test fun `an unavailable retained alias never runs`() = runBlocking {
        val rig = Rig()
        val alias = VoiceAlias("stare", AliasTarget.Unavailable("vehicle", "gone", "{}"))
        assertEquals(AliasRunResult.NotExecutable, rig.coordinator.run(alias))
        assertTrue(rig.validator.calls.isEmpty() && rig.executor.runs.isEmpty())
    }

    @Test fun `there is no recovered presentation path`() {
        val surface = VoiceAliasCoordinator::class.java.methods.map { it.name.lowercase() } +
            AliasProposal::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(surface.toString(), surface.none { "recover" in it })
    }

    // --- source contract: the DI wiring that makes teaching advertise only when a sink is bound ---

    private fun source(path: String): String {
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
        return assertNotNull("missing $path", file).let { file!!.readText() }
    }

    @Test fun `the actual handler provider parameter forces the coordinator`() {
        val module = source("src/main/kotlin/com/bydmate/app/di/VoiceModule.kt")
        val start = module.indexOf("fun provideAssistantActionHandler(")
        assertTrue("provider missing", start >= 0)
        val params = module.substring(start, module.indexOf("): AssistantActionHandler", start))
        assertTrue(params, Regex("""\w+:\s*VoiceAliasCoordinator\b""").containsMatchIn(params))
    }

    @Test fun `the coordinator is an injectable singleton that takes a lazy handler`() {
        val code = source("src/main/kotlin/com/bydmate/app/voice/VoiceAliasCoordinator.kt")
        assertTrue(code.contains("@Singleton\nclass VoiceAliasCoordinator"))
        assertTrue(code.contains("@Inject constructor("))
        assertTrue(code.contains("Provider<AssistantActionHandler>"))
        assertTrue(code.contains("holder.target = this"))
    }
}
