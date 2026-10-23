package com.bydmate.app.ui.settings

import android.content.SharedPreferences
import com.bydmate.app.agent.ActionCandidate
import com.bydmate.app.agent.ActionDescriptor
import com.bydmate.app.agent.ActionExecutor
import com.bydmate.app.agent.ActionOrigin
import com.bydmate.app.agent.ActionOutcome
import com.bydmate.app.agent.ActionRegistry
import com.bydmate.app.agent.ActionRejection
import com.bydmate.app.agent.ActionRisk
import com.bydmate.app.agent.ActionValidating
import com.bydmate.app.agent.ActionValidation
import com.bydmate.app.agent.ExecutionHooks
import com.bydmate.app.agent.ValidatedAction
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.voice.AliasRunResult
import com.bydmate.app.voice.AliasSaveResult
import com.bydmate.app.voice.AliasTarget
import com.bydmate.app.voice.VoiceAlias
import com.bydmate.app.voice.VoiceAliasCoordinator
import com.bydmate.app.voice.VoiceUserPhrases
import com.bydmate.app.voice.openclaw.AliasProposal
import com.bydmate.app.voice.openclaw.AliasProposalSinkHolder
import com.bydmate.app.voice.openclaw.AssistantActionHandler
import com.bydmate.app.voice.openclaw.NoAssistantActions
import io.mockk.coEvery
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

class VoiceUserPhrasesViewModelTest {
    private val close = "windows_close_all"
    private val setTemp = "ac_set_temp"

    private class FakeValidator : ActionValidating {
        val revoked = mutableSetOf<String>()
        val ranges = mapOf("ac_set_temp" to 16..33, "fridge_cool" to -6..6)
        override fun validate(candidate: ActionCandidate, registryVersion: String?): ActionValidation = when (candidate.kind) {
            "vehicle" -> {
                val id = candidate.id
                when {
                    id == null || id in revoked -> ActionValidation.Invalid(ActionRejection.UNKNOWN_COMMAND)
                    id in ranges && candidate.value !in ranges.getValue(id) -> ActionValidation.Invalid(ActionRejection.VALUE_OUT_OF_RANGE)
                    id !in ranges && candidate.value != null -> ActionValidation.Invalid(ActionRejection.UNEXPECTED_FIELD)
                    else -> ActionValidation.Valid(ValidatedAction.Vehicle(id, candidate.value))
                }
            }
            "navigate" -> when {
                candidate.query != null -> ActionValidation.Valid(ValidatedAction.NavigateQuery(candidate.query!!))
                candidate.shortcut == "home" -> ActionValidation.Valid(ValidatedAction.NavigateShortcut(ValidatedAction.Shortcut.HOME))
                else -> ActionValidation.Invalid(ActionRejection.DESTINATION_INVALID)
            }
            else -> ActionValidation.Invalid(ActionRejection.UNKNOWN_KIND)
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

    private class FakeHandler : AssistantActionHandler by NoAssistantActions {
        val saved = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        override fun aliasSaved(proposalId: String) { saved += proposalId }
        override fun aliasDismissed(proposalId: String) { dismissed += proposalId }
    }

    private class MemoryPrefs(initial: String? = null) {
        @Volatile var stored: String? = initial
        var commits = 0
        @Volatile var failCommits = false
        val prefs: SharedPreferences = mockk {
            every { getString(VoiceUserPhrases.KEY, any()) } answers { stored }
            every { edit() } answers {
                var pending: String? = null
                val editor = mockk<SharedPreferences.Editor>(relaxed = true)
                every { editor.putString(VoiceUserPhrases.KEY, any()) } answers { pending = secondArg(); editor }
                every { editor.commit() } answers { commits++; if (failCommits) false else { stored = pending; true } }
                editor
            }
        }
    }

    private class Rig(
        rules: List<RuleEntity> = emptyList(),
        disk: MemoryPrefs = MemoryPrefs(),
        /** When set, the automation-rule query suspends until it completes (a held query). */
        gate: CompletableDeferred<Unit>? = null,
    ) {
        val disk = disk
        val store = VoiceUserPhrases(disk.prefs)
        val validator = FakeValidator()
        val executor = FakeExecutor()
        val handler = FakeHandler()
        val holder = AliasProposalSinkHolder()
        val ruleDao = mockk<RuleDao>().also { coEvery { it.getAllList() } coAnswers { gate?.await(); rules } }
        val registry = mockk<ActionRegistry>().also {
            every { it.descriptors } returns listOf(
                ActionDescriptor("windows_close_all", "Zamknij wszystkie szyby", null, ActionRisk.COMFORT),
                ActionDescriptor("ac_set_temp", "Ustaw temperaturę N", 16..33, ActionRisk.COMFORT),
                ActionDescriptor("fridge_cool", "Chłodzenie lodówki do N", -6..6, ActionRisk.COMFORT),
            )
            every { it.summarize(any()) } returns "SUMMARY"
        }
        val coordinator = VoiceAliasCoordinator(
            holder, validator, executor, { handler }, store,
            summarize = { "SUMMARY" },
            automationPhrases = { emptyMap() },
        )
        val vm = VoiceUserPhrasesViewModel(store, ruleDao, validator, registry, coordinator)
    }

    private fun voiceRule(name: String, phrase: String, enabled: Boolean) = RuleEntity(
        id = 5, name = name, enabled = enabled, actions = "[]",
        triggers = TriggerDef.listToJson(
            listOf(TriggerDef(param = "Voice", chineseName = "语音", operator = "==", value = phrase, displayName = phrase, kind = "voice")),
        ),
    )

    private fun Rig.draftRanged(phrase: String, value: String) {
        vm.startNew()
        vm.updateDraft { it.copy(phrase = phrase, kind = DraftKind.VEHICLE, commandId = setTemp, valueText = value) }
    }

    @Test fun `a valid ranged fixed-value alias stays a draft until Save`() = runBlocking {
        val rig = Rig()
        rig.draftRanged("ciepło dwadzieścia dwa", "22")
        assertNotNull(rig.vm.draft.value)
        assertTrue(rig.store.aliases.value.isEmpty())
        assertEquals(0, rig.disk.commits)

        assertEquals(DraftSaveResult.Saved, rig.vm.saveDraft())
        assertEquals(
            listOf(VoiceAlias("ciepło dwadzieścia dwa", AliasTarget.Vehicle(setTemp, 22))),
            rig.store.aliases.value,
        )
        assertEquals(1, rig.disk.commits)
        assertNull(rig.vm.draft.value)
    }

    @Test fun `an out-of-range value empty phrase or missing target is refused without a write`() = runBlocking {
        val rig = Rig()
        rig.draftRanged("ciepło", "99")
        assertEquals(DraftSaveResult.Refused(DraftIssue.ValueInvalid), rig.vm.saveDraft())
        assertEquals(DraftIssue.ValueInvalid, rig.vm.draft.value?.issue)
        rig.draftRanged("   ", "22")
        assertEquals(DraftSaveResult.Refused(DraftIssue.Empty), rig.vm.saveDraft())
        rig.vm.startNew()
        rig.vm.updateDraft { it.copy(phrase = "coś") }
        assertEquals(DraftSaveResult.Refused(DraftIssue.TargetRequired), rig.vm.saveDraft())
        rig.validator.revoked += setTemp
        rig.draftRanged("ciepło", "22")
        assertEquals(DraftSaveResult.Refused(DraftIssue.TargetUnavailable), rig.vm.saveDraft())
        assertTrue(rig.store.aliases.value.isEmpty())
        assertEquals(0, rig.disk.commits)
    }

    @Test fun `a destination alias colliding with a disabled automation rule is refused`() = runBlocking {
        val rig = Rig(rules = listOf(voiceRule("Stara reguła", "do rodziców", enabled = false)))
        rig.vm.startNew()
        rig.vm.updateDraft {
            it.copy(phrase = "Do rodziców", kind = DraftKind.DESTINATION, shortcut = null, query = "Rodzice, Kraków")
        }
        assertEquals(DraftSaveResult.Refused(DraftIssue.InUse("Stara reguła")), rig.vm.saveDraft())
        assertTrue(rig.store.aliases.value.isEmpty())
        assertEquals(0, rig.disk.commits)
        assertEquals(DraftIssue.InUse("Stara reguła"), rig.vm.draft.value?.issue)
    }

    @Test fun `a grammar shadow warns first and saves only after the explicit second Save`() = runBlocking {
        val rig = Rig()
        rig.vm.startNew()
        rig.vm.updateDraft { it.copy(phrase = "Okna zamknij", kind = DraftKind.VEHICLE, commandId = close) }
        assertEquals(DraftWarning.GRAMMAR_SHADOW, rig.vm.warningFor("Okna zamknij"))

        assertEquals(DraftSaveResult.NeedsConfirmation(DraftWarning.GRAMMAR_SHADOW), rig.vm.saveDraft())
        assertEquals(DraftWarning.GRAMMAR_SHADOW, rig.vm.draft.value?.warning)
        assertTrue(rig.store.aliases.value.isEmpty())

        assertEquals(DraftSaveResult.Saved, rig.vm.saveDraft(confirmWarning = true))
        assertEquals(VoiceAlias("Okna zamknij", AliasTarget.Vehicle(close)), rig.store.aliases.value.single())
    }

    @Test fun `editing the phrase clears a shown warning so it is judged again`() = runBlocking {
        val rig = Rig()
        rig.vm.startNew()
        rig.vm.updateDraft { it.copy(phrase = "Okna zamknij", commandId = close) }
        rig.vm.saveDraft()
        assertNotNull(rig.vm.draft.value?.warning)
        rig.vm.updateDraft { it.copy(phrase = "szyby precz") }
        assertNull(rig.vm.draft.value?.warning)
        assertNull(rig.vm.draft.value?.issue)
    }

    @Test fun `a phrase a hard safety refusal stops is warned as never running yet may be saved`() = runBlocking {
        val rig = Rig()
        for (phrase in listOf("nie otwórz się werdach", "„otwórz się werdach”", "otwórz się werdach i coś jeszcze")) {
            assertEquals(phrase, DraftWarning.NEVER_RUNS, rig.vm.warningFor(phrase))
        }
        rig.vm.startNew()
        rig.vm.updateDraft { it.copy(phrase = "nie otwórz się werdach", commandId = close) }
        assertEquals(DraftSaveResult.NeedsConfirmation(DraftWarning.NEVER_RUNS), rig.vm.saveDraft())
        assertTrue(rig.store.aliases.value.isEmpty())
        assertEquals(DraftSaveResult.Saved, rig.vm.saveDraft(confirmWarning = true))
        assertEquals(1, rig.store.aliases.value.size)
    }

    @Test fun `an incomplete navigation phrase and an unknown phrase carry no warning`() {
        val rig = Rig()
        assertNull(rig.vm.warningFor("wracamy do bazy"))
        assertNull(rig.vm.warningFor("nawiguj do"))
    }

    @Test fun `an unavailable retained alias cannot run and stays editable and deletable`() = runBlocking {
        val rig = Rig(disk = MemoryPrefs("""{"no_such_command":["stara fraza"]}"""))
        val retained = rig.store.aliases.value.single()
        assertFalse(retained.executable)
        assertFalse(rig.vm.isRunnable(retained))
        assertEquals(AliasRunResult.NotExecutable, rig.vm.run(retained))
        assertTrue(rig.executor.runs.isEmpty())

        // Repair: edit it onto a valid target in one replace.
        rig.vm.startEdit(retained)
        assertEquals("stara fraza", rig.vm.draft.value?.phrase)
        rig.vm.updateDraft { it.copy(commandId = close) }
        assertEquals(DraftSaveResult.Saved, rig.vm.saveDraft())
        assertEquals(VoiceAlias("stara fraza", AliasTarget.Vehicle(close)), rig.store.aliases.value.single())
    }

    @Test fun `a saved alias whose target no longer validates is not runnable and does not run`() = runBlocking {
        val rig = Rig()
        rig.draftRanged("ciepło", "22")
        rig.vm.saveDraft()
        val alias = rig.store.aliases.value.single()
        assertTrue(rig.vm.isRunnable(alias))
        rig.validator.revoked += setTemp
        assertFalse(rig.vm.isRunnable(alias))
        assertTrue(rig.vm.run(alias) is AliasRunResult.Rejected)
        assertTrue(rig.executor.runs.isEmpty())
    }

    @Test fun `a runnable alias runs once through the shared executor as USER_ALIAS`() = runBlocking {
        val rig = Rig()
        rig.draftRanged("ciepło", "22")
        rig.vm.saveDraft()
        val alias = rig.store.aliases.value.single()
        assertEquals(AliasRunResult.Executed(ActionOutcome.Done), rig.vm.run(alias))
        val (action, origin) = rig.executor.runs.single()
        assertEquals(ActionOrigin.USER_ALIAS, origin)
        assertEquals(22, (action as ValidatedAction.Vehicle).value)
    }

    // --- finding 2: a signed ranged value (fridge_cool -6..6) ---

    @Test fun `sanitizing keeps a leading minus only for a range with negative values`() {
        val signed = -6..6
        val unsigned = 16..33
        assertEquals("-6", VoiceUserPhrasesViewModel.sanitizeValue("-6", signed))
        assertEquals("-", VoiceUserPhrasesViewModel.sanitizeValue("-", signed))
        assertEquals("-6", VoiceUserPhrasesViewModel.sanitizeValue("--6", signed))
        assertEquals("6", VoiceUserPhrasesViewModel.sanitizeValue("6-", signed))
        assertEquals("6", VoiceUserPhrasesViewModel.sanitizeValue("a6b", signed))
        assertEquals("22", VoiceUserPhrasesViewModel.sanitizeValue("-22", unsigned))
        // The limit comes from the range: nothing longer than its widest bound can be in range.
        assertEquals("-6", VoiceUserPhrasesViewModel.sanitizeValue("-6123", signed))
        assertEquals("22", VoiceUserPhrasesViewModel.sanitizeValue("2212", unsigned))
    }

    @Test fun `a signed value typed through the sanitizer saves as negative`() = runBlocking {
        val rig = Rig()
        val range = rig.vm.commands.single { it.id == "fridge_cool" }.range!!
        rig.vm.startNew()
        rig.vm.updateDraft {
            it.copy(phrase = "zimna lodówka", commandId = "fridge_cool", valueText = VoiceUserPhrasesViewModel.sanitizeValue("-6", range))
        }
        assertEquals(DraftSaveResult.Saved, rig.vm.saveDraft())
        assertEquals(
            listOf(VoiceAlias("zimna lodówka", AliasTarget.Vehicle("fridge_cool", -6))),
            rig.store.aliases.value,
        )
    }

    @Test fun `a lone minus or an out-of-range signed value is refused by the shared range check`() = runBlocking {
        val rig = Rig()
        val range = rig.vm.commands.single { it.id == "fridge_cool" }.range!!
        for (typed in listOf("-", "-7", "7")) {
            rig.vm.startNew()
            rig.vm.updateDraft {
                it.copy(phrase = "lodówka", commandId = "fridge_cool", valueText = VoiceUserPhrasesViewModel.sanitizeValue(typed, range))
            }
            assertEquals(typed, DraftSaveResult.Refused(DraftIssue.ValueInvalid), rig.vm.saveDraft())
        }
        assertTrue(rig.store.aliases.value.isEmpty())
        assertEquals(0, rig.disk.commits)
    }

    // --- finding 3: the draft changes while Save waits on the automation-rule query ---

    private fun Rig.heldDraftA(): Unit = draftRanged("ciepło", "22")

    @Test fun `a draft edited while Save is held is not written and keeps the edit`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(gate = gate)
        rig.heldDraftA()
        val held = async(start = CoroutineStart.UNDISPATCHED) { rig.vm.saveDraft() }

        rig.vm.updateDraft { it.copy(phrase = "zimno", valueText = "18") }
        gate.complete(Unit)

        assertEquals(DraftSaveResult.Superseded, held.await())
        assertTrue(rig.store.aliases.value.isEmpty())
        assertEquals(0, rig.disk.commits)
        val draft = rig.vm.draft.value
        assertEquals("zimno", draft?.phrase)
        assertEquals("18", draft?.valueText)
        assertNull(draft?.issue)
        assertNull(draft?.warning)
    }

    @Test fun `a draft cancelled while Save is held is not written and stays closed`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(gate = gate)
        rig.heldDraftA()
        val held = async(start = CoroutineStart.UNDISPATCHED) { rig.vm.saveDraft() }
        rig.vm.cancelDraft()
        gate.complete(Unit)
        assertEquals(DraftSaveResult.Superseded, held.await())
        assertTrue(rig.store.aliases.value.isEmpty())
        assertEquals(0, rig.disk.commits)
        assertNull(rig.vm.draft.value)
    }

    @Test fun `a draft replaced by an equal looking new one while Save is held is not written`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(gate = gate)
        rig.heldDraftA()
        val held = async(start = CoroutineStart.UNDISPATCHED) { rig.vm.saveDraft() }
        rig.vm.startNew()
        rig.vm.updateDraft { it.copy(phrase = "ciepło", commandId = setTemp, valueText = "22") }
        gate.complete(Unit)
        assertEquals(DraftSaveResult.Superseded, held.await())
        assertTrue(rig.store.aliases.value.isEmpty())
        assertEquals(0, rig.disk.commits)
        assertEquals("ciepło", rig.vm.draft.value?.phrase)
    }

    @Test fun `a held Save of an unchanged draft still saves once and a double Save writes once`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(gate = gate)
        rig.heldDraftA()
        val first = async(start = CoroutineStart.UNDISPATCHED) { rig.vm.saveDraft() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { rig.vm.saveDraft() }
        gate.complete(Unit)
        assertEquals(setOf<DraftSaveResult>(DraftSaveResult.Saved, DraftSaveResult.Superseded), setOf(first.await(), second.await()))
        assertEquals(1, rig.store.aliases.value.size)
        assertEquals(1, rig.disk.commits)
        assertNull(rig.vm.draft.value)
    }

    @Test fun `a warning is not shown on a draft edited while Save is held`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(gate = gate)
        rig.vm.startNew()
        rig.vm.updateDraft { it.copy(phrase = "Okna zamknij", commandId = close) }
        val held = async(start = CoroutineStart.UNDISPATCHED) { rig.vm.saveDraft() }
        rig.vm.updateDraft { it.copy(phrase = "szyby precz") }
        gate.complete(Unit)
        assertEquals(DraftSaveResult.Superseded, held.await())
        assertNull(rig.vm.draft.value?.warning)
        assertEquals("szyby precz", rig.vm.draft.value?.phrase)
    }

    // --- finding 4: delete stays pending until the removal is durable ---

    @Test fun `a failed delete commit keeps the confirmation and the alias then a retry removes it`() {
        val rig = Rig()
        rig.store.saveAlias(AliasTarget.NavigateQuery("Rynek"), "na rynek")
        val alias = rig.store.aliases.value.single()
        rig.vm.requestDelete(alias)

        rig.disk.failCommits = true
        assertFalse(rig.vm.confirmDelete())
        assertEquals(alias, rig.vm.pendingDelete.value)
        assertEquals(DraftIssue.SaveFailed, rig.vm.deleteError.value)
        assertEquals(listOf(alias), rig.store.aliases.value)

        rig.disk.failCommits = false
        assertTrue(rig.vm.confirmDelete())
        assertNull(rig.vm.pendingDelete.value)
        assertNull(rig.vm.deleteError.value)
        assertTrue(rig.store.aliases.value.isEmpty())
    }

    @Test fun `cancelling or asking again clears a delete error`() {
        val rig = Rig()
        rig.store.saveAlias(AliasTarget.NavigateQuery("Rynek"), "na rynek")
        val alias = rig.store.aliases.value.single()
        rig.vm.requestDelete(alias)
        rig.disk.failCommits = true
        rig.vm.confirmDelete()
        assertNotNull(rig.vm.deleteError.value)
        rig.vm.cancelDelete()
        assertNull(rig.vm.deleteError.value)
        assertNull(rig.vm.pendingDelete.value)
        assertEquals(listOf(alias), rig.store.aliases.value)
    }

    @Test fun `confirming the delete of an alias that is already gone closes the dialog`() {
        val rig = Rig()
        rig.store.saveAlias(AliasTarget.NavigateQuery("Rynek"), "na rynek")
        val alias = rig.store.aliases.value.single()
        rig.vm.requestDelete(alias)
        rig.store.removeAlias(alias)
        assertTrue(rig.vm.confirmDelete())
        assertNull(rig.vm.pendingDelete.value)
        assertNull(rig.vm.deleteError.value)
    }

    // --- finding 5: the row text comes from the validator's own action, never a hand-built one ---

    @Test fun `describe summarizes only an action the validator accepts`() {
        val rig = Rig()
        val ranged = VoiceAlias("ciepło", AliasTarget.Vehicle(setTemp, 22))
        val destination = VoiceAlias("na rynek", AliasTarget.NavigateQuery("Rynek"))
        val home = VoiceAlias("do domu", AliasTarget.NavigateShortcut("home"))
        assertEquals("SUMMARY", rig.vm.describe(ranged))
        assertEquals("SUMMARY", rig.vm.describe(destination))
        assertEquals("SUMMARY", rig.vm.describe(home))

        rig.validator.revoked += setTemp
        assertEquals(setTemp, rig.vm.describe(ranged))
        assertEquals("work", rig.vm.describe(VoiceAlias("do pracy", AliasTarget.NavigateShortcut("work"))))
        assertEquals("gone", rig.vm.describe(VoiceAlias("stare", AliasTarget.Unavailable("vehicle", "gone", "{}"))))
        assertEquals("navigate", rig.vm.describe(VoiceAlias("stare", AliasTarget.Unavailable("navigate", null, "{}"))))
    }

    // --- finding 6: the Run button reports what really happened ---

    @Test fun `each run outcome maps to its own local feedback`() = runBlocking {
        val noFeedback = listOf(ActionOutcome.Done, ActionOutcome.Declined, ActionOutcome.Cancelled)
        val refused = listOf(ActionOutcome.Failed("raw_code_xyz"), ActionOutcome.TimedOut, ActionOutcome.ConfirmUnavailable)
        for (outcome in noFeedback) {
            val rig = Rig().also { it.executor.outcome = outcome }
            val alias = VoiceAlias("do domu", AliasTarget.NavigateShortcut("home"))
            assertNull(outcome.toString(), rig.vm.runFeedback(rig.vm.run(alias)))
            assertEquals(1, rig.executor.runs.size)
        }
        for (outcome in refused) {
            val rig = Rig().also { it.executor.outcome = outcome }
            val alias = VoiceAlias("do domu", AliasTarget.NavigateShortcut("home"))
            assertEquals(outcome.toString(), RunFeedback.Refused, rig.vm.runFeedback(rig.vm.run(alias)))
        }
        val rig = Rig().also { it.executor.outcome = ActionOutcome.Blocked("gate", "Nie teraz: jazda") }
        val alias = VoiceAlias("do domu", AliasTarget.NavigateShortcut("home"))
        assertEquals(RunFeedback.Blocked("Nie teraz: jazda"), rig.vm.runFeedback(rig.vm.run(alias)))
    }

    @Test fun `a rejected or unavailable alias reports refused without running`() = runBlocking {
        val rig = Rig()
        rig.validator.revoked += setTemp
        assertEquals(RunFeedback.Refused, rig.vm.runFeedback(rig.vm.run(VoiceAlias("c", AliasTarget.Vehicle(setTemp, 22)))))
        assertEquals(
            RunFeedback.Refused,
            rig.vm.runFeedback(rig.vm.run(VoiceAlias("s", AliasTarget.Unavailable("vehicle", "gone", "{}")))),
        )
        assertTrue(rig.executor.runs.isEmpty())
    }

    @Test fun `delete needs its explicit confirmation`() {
        val rig = Rig()
        rig.store.saveAlias(AliasTarget.NavigateQuery("Rynek"), "na rynek")
        val alias = rig.store.aliases.value.single()
        val commits = rig.disk.commits

        rig.vm.requestDelete(alias)
        assertEquals(alias, rig.vm.pendingDelete.value)
        assertEquals(listOf(alias), rig.store.aliases.value)
        rig.vm.cancelDelete()
        assertNull(rig.vm.pendingDelete.value)
        assertEquals(listOf(alias), rig.store.aliases.value)
        assertEquals(commits, rig.disk.commits)

        rig.vm.requestDelete(alias)
        assertTrue(rig.vm.confirmDelete())
        assertTrue(rig.store.aliases.value.isEmpty())
        assertNull(rig.vm.pendingDelete.value)
        assertFalse(rig.vm.confirmDelete())
    }

    @Test fun `editing an alias replaces it in place and its own phrase is not a collision`() = runBlocking {
        val rig = Rig()
        rig.draftRanged("ciepło", "20")
        rig.vm.saveDraft()
        rig.store.saveAlias(AliasTarget.NavigateShortcut("home"), "wracamy")
        val original = rig.store.aliases.value.first()

        rig.vm.startEdit(original)
        assertEquals(setTemp, rig.vm.draft.value?.commandId)
        assertEquals("20", rig.vm.draft.value?.valueText)
        rig.vm.updateDraft { it.copy(valueText = "24") }
        assertEquals(DraftSaveResult.Saved, rig.vm.saveDraft())
        assertEquals(
            listOf(VoiceAlias("ciepło", AliasTarget.Vehicle(setTemp, 24)), VoiceAlias("wracamy", AliasTarget.NavigateShortcut("home"))),
            rig.store.aliases.value,
        )
    }

    @Test fun `dismissing a proposal makes no persistent write`() {
        val rig = Rig()
        assertTrue(rig.holder.present(AliasProposal("req", "p1", "do rodziców", ValidatedAction.NavigateQuery("Rodzice, Kraków"), "ASSISTANT TEXT")))
        assertEquals("p1", rig.vm.proposal.value?.proposalId)

        rig.vm.dismissProposal("p1")
        assertNull(rig.vm.proposal.value)
        assertEquals(listOf("p1"), rig.handler.dismissed)
        assertTrue(rig.handler.saved.isEmpty())
        assertTrue(rig.store.aliases.value.isEmpty())
        assertEquals(0, rig.disk.commits)
    }

    @Test fun `saving a proposal is explicit and writes it once`() = runBlocking {
        val rig = Rig()
        rig.holder.present(AliasProposal("req", "p1", "do rodziców", ValidatedAction.NavigateQuery("Rodzice, Kraków"), "x"))
        assertTrue(rig.store.aliases.value.isEmpty())
        assertEquals(AliasSaveResult.Saved, rig.vm.saveProposal("p1"))
        assertEquals(1, rig.disk.commits)
        assertEquals(listOf("p1"), rig.handler.saved)
        assertNull(rig.vm.proposal.value)
    }

    @Test fun `each store verdict maps to an editor issue`() {
        assertEquals(DraftIssue.Empty, VoiceUserPhrasesViewModel.issueOf(VoiceUserPhrases.Check.Empty))
        assertEquals(DraftIssue.TooLong, VoiceUserPhrasesViewModel.issueOf(VoiceUserPhrases.Check.TooLong))
        assertEquals(DraftIssue.TooMany, VoiceUserPhrasesViewModel.issueOf(VoiceUserPhrases.Check.TooMany))
        assertEquals(DraftIssue.InUse("X"), VoiceUserPhrasesViewModel.issueOf(VoiceUserPhrases.Check.InUse("X")))
    }

    // ---- The proposal host must be reachable on every screen, exactly once. ----

    private fun mainKotlin(path: String) = File("src/main/kotlin/com/bydmate/app/$path").readText()

    @Test fun `the proposal host is mounted once app-wide and the editor screen does not duplicate it`() {
        val nav = mainKotlin("ui/navigation/AppNavigation.kt")
        assertEquals("AppNavigation must mount AliasProposalHost() once", 1, Regex("""\bAliasProposalHost\(""").findAll(nav).count())

        val screen = mainKotlin("ui/settings/VoiceUserPhrasesScreen.kt")
        assertEquals(1, Regex("""fun AliasProposalHost\(""").findAll(screen).count())
        val editor = screen.substring(screen.indexOf("fun VoiceUserPhrasesScreen("), screen.indexOf("fun AliasProposalHost("))
        assertFalse("the editor screen must not host proposals itself", editor.contains("AliasProposalHost("))
        assertFalse("the editor screen must not collect the proposal", editor.contains(".proposal"))

        // Every call site in the module: the definition plus the one in AppNavigation, nothing else.
        val total = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
            .sumOf { Regex("""\bAliasProposalHost\(""").findAll(it.readText()).count() }
        assertEquals(2, total)
    }
}
