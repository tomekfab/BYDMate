package com.bydmate.app.voice

import com.bydmate.app.agent.ActionCandidate
import com.bydmate.app.agent.ActionExecutor
import com.bydmate.app.agent.ActionOrigin
import com.bydmate.app.agent.ActionOutcome
import com.bydmate.app.agent.ActionRegistry
import com.bydmate.app.agent.ActionRejection
import com.bydmate.app.agent.ActionValidating
import com.bydmate.app.agent.ActionValidation
import com.bydmate.app.agent.ValidatedAction
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.voice.openclaw.AliasProposal
import com.bydmate.app.voice.openclaw.AliasProposalSink
import com.bydmate.app.voice.openclaw.AliasProposalSinkHolder
import com.bydmate.app.voice.openclaw.AssistantActionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** What the confirmation shows: the phrase and a summary built locally, never assistant prose. */
data class PendingAliasProposal(val proposalId: String, val phrase: String, val summary: String)

sealed interface AliasSaveResult {
    data object Saved : AliasSaveResult
    /** No open proposal has this id (unknown, already saved or dismissed). */
    data object NotPending : AliasSaveResult
    /** The target no longer validates; nothing was written and the proposal is closed as dismissed. */
    data object Unavailable : AliasSaveResult
    /** The phrase collides or exceeds a limit; the proposal stays open so the user can dismiss it. */
    data class Rejected(val check: VoiceUserPhrases.Check) : AliasSaveResult
    data object PersistFailed : AliasSaveResult
}

sealed interface AliasRunResult {
    data class Executed(val outcome: ActionOutcome) : AliasRunResult
    /** [ActionValidating] refused the target now; nothing ran. */
    data class Rejected(val reason: ActionRejection) : AliasRunResult
    /** A retained alias this build cannot run; nothing ran. */
    data object NotExecutable : AliasRunResult
}

/**
 * The #5 end of the assistant alias proposal and the one door a matched alias runs through.
 *
 * The assistant only suggests: [present] validates and shows a confirmation, [save] validates again and
 * writes through the singleton [VoiceUserPhrases], and the handler hears [AssistantActionHandler.aliasSaved] or
 * [AssistantActionHandler.aliasDismissed] once the proposal is terminal. There is no second ledger: [terminal] is only
 * an in-process guard so a repeated id cannot reopen the confirmation (#4 already drops recovered proposals before the
 * sink). [run] validates the stored target again and executes it only through [ActionExecutor] with
 * [ActionOrigin.USER_ALIAS], so the executor's risk overlay stays in force.
 *
 * The handler is a lazy [Provider]: the handler needs this coordinator (so it exists, and has bound the holder,
 * before the handler advertises teaching) and this coordinator needs the handler only when a proposal ends.
 */
@Suppress("TooManyFunctions") // One proposal lifecycle owns validation, persistence and terminal callbacks.
// Keep the production collaborators and deterministic test seams explicit in the constructor.
@Singleton
class VoiceAliasCoordinator @Suppress("LongParameterList") constructor(
    holder: AliasProposalSinkHolder,
    private val validator: ActionValidating,
    private val executor: ActionExecutor,
    private val handler: Provider<AssistantActionHandler>,
    private val store: VoiceUserPhrases,
    private val summarize: (ValidatedAction) -> String,
    private val automationPhrases: suspend () -> Map<String, String>,
) : AliasProposalSink {

    @Suppress("LongParameterList") // Hilt supplies the same collaborators and binds the proposal holder before use.
    @Inject constructor(
        holder: AliasProposalSinkHolder,
        validator: ActionValidating,
        executor: ActionExecutor,
        handler: Provider<AssistantActionHandler>,
        store: VoiceUserPhrases,
        registry: ActionRegistry,
        ruleDao: RuleDao,
    ) : this(holder, validator, executor, handler, store, registry::summarize, { voiceTriggerOwners(ruleDao) })

    private class Open(val proposalId: String, val phrase: String, val target: AliasTarget)

    private val lock = Any()
    private var open: Open? = null
    private val terminal = LinkedHashSet<String>()
    private val _pending = MutableStateFlow<PendingAliasProposal?>(null)

    /** The proposal waiting for the user's Save or Dismiss, or null. */
    val pending: StateFlow<PendingAliasProposal?> = _pending.asStateFlow()

    init {
        holder.target = this
    }

    override fun present(p: AliasProposal): Boolean {
        val target = revalidate(p.action) ?: return false
        val phrase = p.phrase.filterNot(Char::isISOControl).trim()
        val summary = summarize(p.action)
        val superseded = synchronized(lock) {
            if (p.proposalId in terminal || open?.proposalId == p.proposalId) return false
            val previous = open
            if (previous != null) markTerminal(previous.proposalId)
            open = Open(p.proposalId, phrase, target)
            _pending.value = PendingAliasProposal(p.proposalId, phrase, summary)
            previous
        }
        // The unanswered proposal is closed without a write; the handler ledger must not stay on it.
        superseded?.let { handler.get().aliasDismissed(it.proposalId) }
        return true
    }

    /**
     * Saves the open proposal [proposalId]. The automation-phrase query suspends, and the user may Dismiss or a newer
     * proposal may replace this one meanwhile, so after it returns the same open proposal is checked again and the
     * second validation, the write and the terminal close all happen under one lock, serialized with [present] and
     * [dismiss]. No lock is held across the suspension. A proposal that was closed meanwhile is [AliasSaveResult.NotPending]
     * and writes nothing; whoever closed it already reported it once.
     */
    suspend fun save(proposalId: String): AliasSaveResult {
        val start = synchronized(lock) { open?.takeIf { it.proposalId == proposalId } } ?: return AliasSaveResult.NotPending
        val automation = automationPhrases()
        val closed: Open?
        val result = synchronized(lock) {
            val current = open?.takeIf { it === start } ?: return AliasSaveResult.NotPending
            val target = current.target.toCandidate()?.let { validate(it) }
            if (target == null) {
                closed = closeLocked(current)
                AliasSaveResult.Unavailable
            } else when (val saved = store.saveAlias(target, current.phrase, automation)) {
                SaveResult.Saved -> {
                    closed = closeLocked(current)
                    AliasSaveResult.Saved
                }
                is SaveResult.Rejected -> { closed = null; AliasSaveResult.Rejected(saved.check) }
                SaveResult.PersistFailed -> { closed = null; AliasSaveResult.PersistFailed }
            }
        }
        // Reported outside the lock; the proposal was closed by this call alone, so exactly once.
        if (closed != null) {
            if (result == AliasSaveResult.Saved) handler.get().aliasSaved(proposalId) else handler.get().aliasDismissed(proposalId)
        }
        return result
    }

    fun dismiss(proposalId: String) {
        if (close(proposalId) != null) handler.get().aliasDismissed(proposalId)
    }

    /** Runs a saved alias after validating its target again; never executes a retained unavailable alias. */
    suspend fun run(alias: VoiceAlias): AliasRunResult {
        val candidate = alias.target.toCandidate() ?: return AliasRunResult.NotExecutable
        return when (val check = validator.validate(candidate, null)) {
            is ActionValidation.Invalid -> AliasRunResult.Rejected(check.reason)
            is ActionValidation.Valid ->
                AliasRunResult.Executed(executor.execute(check.action, ActionOrigin.USER_ALIAS))
        }
    }

    /** The persisted form of [action] when it still validates and the store can reload it as executable. */
    private fun revalidate(action: ValidatedAction): AliasTarget? = action.toTarget().toCandidate()?.let(::validate)

    private fun validate(candidate: ActionCandidate): AliasTarget? =
        (validator.validate(candidate, null) as? ActionValidation.Valid)
            ?.action?.toTarget()?.takeIf(VoiceUserPhrases::canPersist)

    /** Ends [proposalId] if it is the open one; the caller reports the terminal state. */
    private fun close(proposalId: String): Open? = synchronized(lock) {
        val current = open?.takeIf { it.proposalId == proposalId } ?: return null
        closeLocked(current)
    }

    /** Caller holds [lock] and [current] is the open proposal. */
    private fun closeLocked(current: Open): Open {
        open = null
        _pending.value = null
        markTerminal(current.proposalId)
        return current
    }

    private fun markTerminal(proposalId: String) {
        terminal += proposalId
        if (terminal.size > MAX_TERMINAL) terminal.remove(terminal.first())
    }

    private fun ValidatedAction.toTarget(): AliasTarget = when (this) {
        is ValidatedAction.Vehicle -> AliasTarget.Vehicle(commandId, value)
        is ValidatedAction.NavigateQuery -> AliasTarget.NavigateQuery(query)
        is ValidatedAction.NavigateShortcut -> AliasTarget.NavigateShortcut(which.name.lowercase())
    }

    private fun AliasTarget.toCandidate(): ActionCandidate? = when (this) {
        is AliasTarget.Vehicle -> ActionCandidate("vehicle", id = commandId, value = value)
        is AliasTarget.NavigateQuery -> ActionCandidate("navigate", query = query)
        is AliasTarget.NavigateShortcut -> ActionCandidate("navigate", shortcut = which)
        is AliasTarget.Unavailable -> null
    }

    private companion object {
        const val MAX_TERMINAL = 512

        /** Normalized voice-trigger phrase → rule name over all rules, disabled ones included. */
        suspend fun voiceTriggerOwners(ruleDao: RuleDao): Map<String, String> = buildMap {
            for (rule in ruleDao.getAllList()) {
                TriggerDef.listFromJson(rule.triggers)
                    .filter { it.kind == "voice" && it.value.isNotBlank() }
                    .forEach { putIfAbsent(VoicePhrase.normalize(it.value), rule.name) }
            }
        }
    }
}
