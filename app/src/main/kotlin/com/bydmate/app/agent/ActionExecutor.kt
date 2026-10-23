package com.bydmate.app.agent

import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.NavDestinationCheck
import com.bydmate.app.data.automation.NavigateActionMapper
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.remote.DiParsData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class ActionOrigin { ASSISTANT, USER_ALIAS }

enum class ConfirmResult { CONFIRMED, DECLINED, TIMED_OUT, UNAVAILABLE }

interface ActionConfirmer {
    suspend fun confirm(title: String, summary: String): ConfirmResult
}

interface ExecutionHooks {
    /** Completes when the proposal is cancelled (barge-in, opt-out). */
    val cancelled: Deferred<Unit>
    fun onConfirmPending()

    /** Last veto before the write: false means do not dispatch. */
    suspend fun beforeDispatch(): Boolean

    object None : ExecutionHooks {
        override val cancelled: Deferred<Unit> = CompletableDeferred()
        override fun onConfirmPending() = Unit
        override suspend fun beforeDispatch(): Boolean = true
    }
}

sealed interface ActionOutcome {
    data object Done : ActionOutcome
    data class Failed(val code: String) : ActionOutcome
    data class Blocked(val code: String, val text: String) : ActionOutcome
    data object Declined : ActionOutcome
    data object TimedOut : ActionOutcome
    data object ConfirmUnavailable : ActionOutcome
    data object Cancelled : ActionOutcome
}

interface ActionExecutor {
    suspend fun execute(
        action: ValidatedAction,
        origin: ActionOrigin,
        hooks: ExecutionHooks = ExecutionHooks.None,
    ): ActionOutcome
}

/**
 * The one executor for assistant actions and saved aliases. Risk comes from [ActionRegistry]
 * alone, so [ActionOrigin] never changes the confirm rule. Navigation is COMFORT and runs directly.
 */
class DispatcherActionExecutor(
    private val registry: ActionRegistry,
    private val dispatcher: ActionDispatcher,
    private val confirmer: ActionConfirmer,
    private val snapshot: () -> DiParsData?,
) : ActionExecutor {

    override suspend fun execute(action: ValidatedAction, origin: ActionOrigin, hooks: ExecutionHooks): ActionOutcome {
        if (hooks.cancelled.isCompleted) return ActionOutcome.Cancelled
        val def = toActionDef(action) ?: return ActionOutcome.Failed("unresolved_command")
        val risk = when (action) {
            is ValidatedAction.Vehicle -> registry.descriptor(action.commandId)?.risk
                ?: return ActionOutcome.Failed("unknown_command")
            is ValidatedAction.NavigateQuery, is ValidatedAction.NavigateShortcut -> ActionRisk.COMFORT
        }
        if (risk == ActionRisk.CONFIRM) {
            hooks.onConfirmPending()
            when (awaitConfirmation(registry.summarize(action), hooks)) {
                null -> return ActionOutcome.Cancelled
                ConfirmResult.CONFIRMED -> if (hooks.cancelled.isCompleted) return ActionOutcome.Cancelled
                ConfirmResult.DECLINED -> return ActionOutcome.Declined
                ConfirmResult.TIMED_OUT -> return ActionOutcome.TimedOut
                ConfirmResult.UNAVAILABLE -> return ActionOutcome.ConfirmUnavailable
            }
        }
        if (!hooks.beforeDispatch()) return ActionOutcome.Cancelled
        // From here the write is never abandoned: a barge-in must not leave the car half-commanded,
        // and the caller needs the real result for the ledger.
        return withContext(NonCancellable) { run(def) }
    }

    /** The confirmer's answer, or null when [ExecutionHooks.cancelled] completes first. */
    private suspend fun awaitConfirmation(summary: String, hooks: ExecutionHooks): ConfirmResult? = coroutineScope {
        val answer = async { confirmer.confirm(CONFIRM_TITLE, summary) }
        select<ConfirmResult?> {
            hooks.cancelled.onAwait { null }
            answer.onAwait { it }
        }.also { answer.cancel() }
    }

    // After beforeDispatch, NonCancellable finishes the write and returns a sanitized outcome; never expose exception data.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun run(def: ActionDef): ActionOutcome {
        val result = try {
            // Read after the confirm and the last veto: a confirmed window can sit open while the car moves.
            dispatcher.dispatch(def, snapshot())
        } catch (e: Exception) {
            return ActionOutcome.Failed(DISPATCH_FAILED)
        }
        val reason = result.reason
        return when {
            result.success -> ActionOutcome.Done
            result.trustedLocalizedReason && !reason.isNullOrBlank() -> ActionOutcome.Blocked(GATE, reason)
            else -> ActionOutcome.Failed(DISPATCH_FAILED)
        }
    }

    private fun toActionDef(action: ValidatedAction): ActionDef? = when (action) {
        is ValidatedAction.Vehicle -> registry.resolve(action.commandId, action.value)
            ?.let { ActionDef(command = it, displayName = action.commandId, kind = "param") }
        is ValidatedAction.NavigateQuery ->
            NavigateActionMapper.toActionDef(NavDestinationCheck.Valid(action.query), go = PREVIEW_ONLY)
        is ValidatedAction.NavigateShortcut -> ActionDef(
            command = "",
            displayName = registry.summarize(action),
            kind = "navigate",
            payload = JSONObject().put("shortcut", action.which.name.lowercase()).put("go", PREVIEW_ONLY).toString(),
        )
    }

    private companion object {
        /**
         * Navigation from the assistant or a saved alias only shows the destination. `go = true` would press the
         * Yandex start button through accessibility for a home or work shortcut, and no route may start by itself.
         */
        const val PREVIEW_ONLY = false
        const val CONFIRM_TITLE = "Asystent"
        const val GATE = "gate"
        const val DISPATCH_FAILED = "dispatch_failed"
    }
}
