package com.bydmate.app.ui.settings

import androidx.lifecycle.ViewModel
import com.bydmate.app.agent.ActionCandidate
import com.bydmate.app.agent.ActionDescriptor
import com.bydmate.app.agent.ActionOutcome
import com.bydmate.app.agent.ActionRegistry
import com.bydmate.app.agent.ActionValidating
import com.bydmate.app.agent.ActionValidation
import com.bydmate.app.agent.ValidatedAction
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.voice.AliasRunResult
import com.bydmate.app.voice.AliasSaveResult
import com.bydmate.app.voice.AliasTarget
import com.bydmate.app.voice.NluParser
import com.bydmate.app.voice.NluRefusal
import com.bydmate.app.voice.NluVerdict
import com.bydmate.app.voice.PendingAliasProposal
import com.bydmate.app.voice.ReplaceResult
import com.bydmate.app.voice.SaveResult
import com.bydmate.app.voice.VoiceAlias
import com.bydmate.app.voice.VoiceAliasCoordinator
import com.bydmate.app.voice.VoiceLanguage
import com.bydmate.app.voice.VoicePhrase
import com.bydmate.app.voice.VoiceUserPhrases
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

enum class DraftKind { VEHICLE, DESTINATION }

/** A phrase the alias editor warns about but still lets the user save after an explicit confirmation. */
enum class DraftWarning {
    /** The built-in grammar already understands the phrase; the alias wins over it. */
    GRAMMAR_SHADOW,
    /** The phrase is a negated, quoted or partially recognized combined command: a hard safety refusal comes
     *  before aliases, so the spoken alias can never run. */
    NEVER_RUNS,
}

/** Why a draft was not saved; the screen maps each to a localized message. */
sealed interface DraftIssue {
    data object Empty : DraftIssue
    data object TooLong : DraftIssue
    data object TooMany : DraftIssue
    data class InUse(val owner: String) : DraftIssue
    data object TargetRequired : DraftIssue
    data object ValueInvalid : DraftIssue
    data object TargetUnavailable : DraftIssue
    data object SaveFailed : DraftIssue
}

/** What the Settings Run button reports back to the user; null feedback means nothing to say. */
sealed interface RunFeedback {
    /** A trusted local reason the action did not run. */
    data class Blocked(val text: String) : RunFeedback
    /** The alias did not run; the screen shows its constant message. */
    data object Refused : RunFeedback
}

/** The editor's unsaved state. Nothing here reaches the store before [VoiceUserPhrasesViewModel.saveDraft]. */
data class AliasDraft(
    /** The stored alias being edited, or null for a new one. */
    val editing: VoiceAlias? = null,
    val phrase: String = "",
    val kind: DraftKind = DraftKind.VEHICLE,
    val commandId: String? = null,
    val valueText: String = "",
    /** "home" or "work" for a shortcut destination; null for a free-text [query]. */
    val shortcut: String? = null,
    val query: String = "",
    val issue: DraftIssue? = null,
    val warning: DraftWarning? = null,
    /** Set by the view model, new for every edit, so a Save that waited on a query can tell its draft changed. */
    val revision: Long = 0,
)

sealed interface DraftSaveResult {
    data object NoDraft : DraftSaveResult
    /** The draft was edited, replaced or closed while the save waited on a query; nothing was written. */
    data object Superseded : DraftSaveResult
    data object Saved : DraftSaveResult
    /** A [DraftWarning] needs the user's explicit second Save; nothing was written. */
    data class NeedsConfirmation(val warning: DraftWarning) : DraftSaveResult
    data class Refused(val issue: DraftIssue) : DraftSaveResult
}

@HiltViewModel
@Suppress("TooManyFunctions") // One editor owns draft validation, persistence and proposal controls.
class VoiceUserPhrasesViewModel @Inject constructor(
    private val userPhrases: VoiceUserPhrases,
    private val ruleDao: RuleDao,
    private val validator: ActionValidating,
    private val registry: ActionRegistry,
    private val coordinator: VoiceAliasCoordinator,
) : ViewModel() {
    val aliases: StateFlow<List<VoiceAlias>> = userPhrases.aliases

    /** The vehicle actions the editor offers: what the registry can really run. */
    val commands: List<ActionDescriptor> = registry.descriptors

    private val _draft = MutableStateFlow<AliasDraft?>(null)
    val draft: StateFlow<AliasDraft?> = _draft.asStateFlow()

    private val _pendingDelete = MutableStateFlow<VoiceAlias?>(null)
    val pendingDelete: StateFlow<VoiceAlias?> = _pendingDelete.asStateFlow()

    private val _deleteError = MutableStateFlow<DraftIssue?>(null)
    val deleteError: StateFlow<DraftIssue?> = _deleteError.asStateFlow()

    /** The assistant's alias suggestion waiting for the user's Save or Dismiss. */
    val proposal: StateFlow<PendingAliasProposal?> = coordinator.pending

    private var lastRevision = 0L

    private fun nextRevision(): Long = ++lastRevision

    fun startNew() {
        _draft.value = AliasDraft(revision = nextRevision())
    }

    /** Opens [alias] as a draft. A retained unavailable alias keeps its phrase and asks for a new target. */
    fun startEdit(alias: VoiceAlias) {
        val revision = nextRevision()
        _draft.value = when (val t = alias.target) {
            is AliasTarget.Vehicle -> AliasDraft(
                editing = alias, phrase = alias.phrase, kind = DraftKind.VEHICLE,
                commandId = t.commandId, valueText = t.value?.toString().orEmpty(), revision = revision,
            )
            is AliasTarget.NavigateQuery -> AliasDraft(
                editing = alias, phrase = alias.phrase, kind = DraftKind.DESTINATION, query = t.query,
                revision = revision,
            )
            is AliasTarget.NavigateShortcut -> AliasDraft(
                editing = alias, phrase = alias.phrase, kind = DraftKind.DESTINATION, shortcut = t.which,
                revision = revision,
            )
            is AliasTarget.Unavailable -> AliasDraft(
                editing = alias, phrase = alias.phrase,
                kind = if (t.kind == "navigate") DraftKind.DESTINATION else DraftKind.VEHICLE,
                revision = revision,
            )
        }
    }

    /** Any edit drops the last issue and warning: the next Save judges the draft afresh. */
    fun updateDraft(transform: (AliasDraft) -> AliasDraft) {
        _draft.update { current -> current?.let { transform(it).copy(issue = null, warning = null, revision = nextRevision()) } }
    }

    fun cancelDraft() {
        _draft.value = null
    }

    /**
     * Saves the draft: validates the target through [ActionValidating], checks every collision (all automation
     * rules, disabled ones too), then writes once. A grammar shadow or a never-runs phrase is warned about first and
     * saved only when [confirmWarning] is true: the alias is never refused for it. Nothing is written before. If the
     * draft changes while the rule query is pending, nothing is written and the result is [DraftSaveResult.Superseded].
     */
    suspend fun saveDraft(confirmWarning: Boolean = false): DraftSaveResult {
        val d = _draft.value ?: return DraftSaveResult.NoDraft
        val phrase = d.phrase.filterNot(Char::isISOControl).trim()
        if (phrase.isEmpty()) return refuse(d, DraftIssue.Empty)
        val candidate = when (val built = candidateOf(d)) {
            is Built.Candidate -> built.value
            is Built.Failed -> return refuse(d, built.issue)
        }
        val target = (validator.validate(candidate, null) as? ActionValidation.Valid)
            ?.action?.toTarget()?.takeIf(VoiceUserPhrases::canPersist)
            ?: return refuse(d, DraftIssue.TargetUnavailable)
        val automation = voiceTriggerOwners()
        // The query suspended: the draft may have been edited, replaced or closed. Judge only the draft that was saved.
        if (_draft.value?.revision != d.revision) return DraftSaveResult.Superseded
        val verdict = userPhrases.checkAlias(target, phrase, automation, replacing = d.editing)
        if (verdict != VoiceUserPhrases.Check.Ok) return refuse(d, issueOf(verdict))
        if (!confirmWarning) {
            warningFor(phrase)?.let { warning ->
                _draft.value = d.copy(warning = warning, issue = null)
                return DraftSaveResult.NeedsConfirmation(warning)
            }
        }
        val issue = persistDraft(d, target, phrase, automation)
        if (issue != null) return refuse(d, issue)
        _draft.value = null
        return DraftSaveResult.Saved
    }

    private fun persistDraft(d: AliasDraft, target: AliasTarget, phrase: String, automation: Map<String, String>): DraftIssue? =
        when (val editing = d.editing) {
            null -> when (val r = userPhrases.saveAlias(target, phrase, automation)) {
                SaveResult.Saved -> null
                is SaveResult.Rejected -> issueOf(r.check)
                SaveResult.PersistFailed -> DraftIssue.SaveFailed
            }
            else -> when (val r = userPhrases.replaceAlias(editing, VoiceAlias(phrase, target), automation)) {
                ReplaceResult.Replaced -> null
                is ReplaceResult.Rejected -> issueOf(r.check)
                ReplaceResult.NotFound, ReplaceResult.PersistFailed -> DraftIssue.SaveFailed
            }
        }

    private fun refuse(d: AliasDraft, issue: DraftIssue): DraftSaveResult {
        _draft.value = d.copy(issue = issue, warning = null)
        return DraftSaveResult.Refused(issue)
    }

    /** Asks for the explicit delete confirmation; nothing is removed yet. */
    fun requestDelete(alias: VoiceAlias) {
        _deleteError.value = null
        _pendingDelete.value = alias
    }

    fun cancelDelete() {
        _deleteError.value = null
        _pendingDelete.value = null
    }

    /**
     * Removes the alias [requestDelete] asked about. The confirmation closes only once the removal is durable (or the
     * alias is already gone); a failed commit keeps it open with [deleteError] set, so the user can retry or cancel.
     * False when none was pending or the write failed.
     */
    fun confirmDelete(): Boolean {
        val alias = _pendingDelete.value ?: return false
        if (alias !in userPhrases.aliases.value || userPhrases.removeAlias(alias)) {
            _deleteError.value = null
            _pendingDelete.value = null
            return true
        }
        _deleteError.value = DraftIssue.SaveFailed
        return false
    }

    /**
     * What the row shows under the phrase: the registry's summary of the action the validator returns now, never
     * assistant text. A target the validator refuses, or a retained unavailable one, shows its stored identifier.
     */
    fun describe(alias: VoiceAlias): String {
        val t = alias.target
        val valid = candidateOf(t)?.let { validator.validate(it, null) as? ActionValidation.Valid }
        return valid?.let { registry.summarize(it.action) } ?: when (t) {
            is AliasTarget.Vehicle -> t.commandId
            is AliasTarget.NavigateQuery -> t.query
            is AliasTarget.NavigateShortcut -> t.which
            is AliasTarget.Unavailable -> t.commandId ?: t.kind
        }
    }

    /** Whether the row may offer Run: a retained unavailable alias, or a target the validator refuses now, may not. */
    fun isRunnable(alias: VoiceAlias): Boolean {
        val candidate = candidateOf(alias.target) ?: return false
        return validator.validate(candidate, null) is ActionValidation.Valid
    }

    /** Runs a saved alias through the coordinator: validated again, executed only as `USER_ALIAS`. */
    suspend fun run(alias: VoiceAlias): AliasRunResult = coordinator.run(alias)

    /** The editor's warning for [phrase], from the same grammar the voice controller applies before aliases. */
    fun warningFor(phrase: String): DraftWarning? = when (val verdict = NluParser.classify(phrase, VoiceLanguage.PL)) {
        is NluVerdict.Match -> DraftWarning.GRAMMAR_SHADOW
        is NluVerdict.Refused -> DraftWarning.NEVER_RUNS.takeIf { verdict.reason in HARD_REFUSALS }
        NluVerdict.Unknown -> null
    }

    suspend fun saveProposal(proposalId: String): AliasSaveResult = coordinator.save(proposalId)

    fun dismissProposal(proposalId: String) = coordinator.dismiss(proposalId)

    private sealed interface Built {
        data class Candidate(val value: ActionCandidate) : Built
        data class Failed(val issue: DraftIssue) : Built
    }

    private fun candidateOf(d: AliasDraft): Built = when (d.kind) {
        DraftKind.VEHICLE -> {
            val id = d.commandId ?: return Built.Failed(DraftIssue.TargetRequired)
            val descriptor = commands.firstOrNull { it.id == id } ?: return Built.Failed(DraftIssue.TargetUnavailable)
            val range = descriptor.range
            val value = if (range == null) null else d.valueText.toIntOrNull()?.takeIf { it in range }
            if (range != null && value == null) Built.Failed(DraftIssue.ValueInvalid)
            else Built.Candidate(ActionCandidate("vehicle", id = id, value = value))
        }
        DraftKind.DESTINATION -> {
            val query = d.query.filterNot(Char::isISOControl).trim()
            when {
                d.shortcut != null -> Built.Candidate(ActionCandidate("navigate", shortcut = d.shortcut))
                query.isEmpty() -> Built.Failed(DraftIssue.TargetRequired)
                else -> Built.Candidate(ActionCandidate("navigate", query = query))
            }
        }
    }

    private fun candidateOf(target: AliasTarget): ActionCandidate? = when (target) {
        is AliasTarget.Vehicle -> ActionCandidate("vehicle", id = target.commandId, value = target.value)
        is AliasTarget.NavigateQuery -> ActionCandidate("navigate", query = target.query)
        is AliasTarget.NavigateShortcut -> ActionCandidate("navigate", shortcut = target.which)
        is AliasTarget.Unavailable -> null
    }

    private fun ValidatedAction.toTarget(): AliasTarget = when (this) {
        is ValidatedAction.Vehicle -> AliasTarget.Vehicle(commandId, value)
        is ValidatedAction.NavigateQuery -> AliasTarget.NavigateQuery(query)
        is ValidatedAction.NavigateShortcut -> AliasTarget.NavigateShortcut(which.name.lowercase())
    }

    /** Normalized voice-trigger phrase → rule name over all rules, disabled ones included. */
    private suspend fun voiceTriggerOwners(): Map<String, String> = buildMap {
        for (rule in ruleDao.getAllList()) {
            TriggerDef.listFromJson(rule.triggers)
                .filter { it.kind == "voice" && it.value.isNotBlank() }
                .forEach { putIfAbsent(VoicePhrase.normalize(it.value), rule.name) }
        }
    }

    /** What the Run button tells the user about [result]; null when it ran or the user chose not to. */
    fun runFeedback(result: AliasRunResult): RunFeedback? = when (result) {
        is AliasRunResult.Rejected, AliasRunResult.NotExecutable -> RunFeedback.Refused
        is AliasRunResult.Executed -> when (val outcome = result.outcome) {
            ActionOutcome.Done, ActionOutcome.Declined, ActionOutcome.Cancelled -> null
            // Only a trusted local reason reaches `Blocked.text` (see `DispatcherActionExecutor`).
            is ActionOutcome.Blocked -> RunFeedback.Blocked(outcome.text)
            is ActionOutcome.Failed, ActionOutcome.TimedOut, ActionOutcome.ConfirmUnavailable -> RunFeedback.Refused
        }
    }

    companion object {
        /**
         * [text] as a value-field entry for [range]: digits, plus one leading minus when the range has negative
         * values (a lone `-` is kept so the user can type `-6`). The length limit is the widest bound's, since a
         * longer entry is never in range. Range validation stays in the shared check at Save.
         */
        fun sanitizeValue(text: String, range: IntRange): String {
            val negative = range.first < 0 && text.startsWith("-")
            val limit = maxOf(range.first.toString().length, range.last.toString().length)
            return ((if (negative) "-" else "") + text.filter(Char::isDigit)).take(limit)
        }

        /** The refusals that come before aliases in the voice controller, so such a spoken alias can never run.
         *  A partial one is a combined command recognized in part; incomplete navigation is not here on purpose. */
        private val HARD_REFUSALS = setOf(NluRefusal.NEGATED, NluRefusal.QUOTED, NluRefusal.PARTIAL)

        fun issueOf(check: VoiceUserPhrases.Check): DraftIssue = when (check) {
            VoiceUserPhrases.Check.Ok -> DraftIssue.SaveFailed
            VoiceUserPhrases.Check.Empty -> DraftIssue.Empty
            VoiceUserPhrases.Check.TooLong -> DraftIssue.TooLong
            VoiceUserPhrases.Check.TooMany -> DraftIssue.TooMany
            is VoiceUserPhrases.Check.InUse -> DraftIssue.InUse(check.owner)
        }
    }
}
