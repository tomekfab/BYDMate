package com.bydmate.app.agent

import com.bydmate.app.data.automation.NavDestinationCheck
import com.bydmate.app.data.automation.NavDestinationRules

/**
 * An assistant or alias action that passed [RegistryActionValidator]. Only [ValidatedAction.from]
 * constructs the subclasses; every executor and ledger takes this type, never raw model output.
 */
sealed class ValidatedAction private constructor() {
    class Vehicle internal constructor(val commandId: String, val value: Int?) : ValidatedAction()
    class NavigateQuery internal constructor(val query: String) : ValidatedAction()
    class NavigateShortcut internal constructor(val which: Shortcut) : ValidatedAction()

    enum class Shortcut { HOME, WORK }

    companion object {
        internal fun from(c: ActionCandidate, registry: ActionRegistry, registryVersion: String?): ActionValidation {
            if (registryVersion != null && registryVersion != registry.version) return invalid(ActionRejection.STALE_CAPABILITIES)
            return when (c.kind) {
                "vehicle" -> vehicle(c, registry)
                "navigate" -> navigate(c)
                else -> invalid(ActionRejection.UNKNOWN_KIND)
            }
        }

        private fun invalid(reason: ActionRejection) = ActionValidation.Invalid(reason)

        private fun valid(action: ValidatedAction) = ActionValidation.Valid(action)

        private fun vehicle(c: ActionCandidate, registry: ActionRegistry): ActionValidation {
            val id = c.id ?: return invalid(registry.refusalFor(""))
            val descriptor = registry.descriptor(id) ?: return invalid(registry.refusalFor(id))
            if (c.query != null || c.shortcut != null) return invalid(ActionRejection.UNEXPECTED_FIELD)
            val range = descriptor.range
            val value = c.value
            if (range == null) {
                if (value != null) return invalid(ActionRejection.UNEXPECTED_FIELD)
            } else {
                if (value == null) return invalid(ActionRejection.MISSING_VALUE)
                if (value !in range) return invalid(ActionRejection.VALUE_OUT_OF_RANGE)
            }
            return valid(Vehicle(id, value))
        }

        private fun navigate(c: ActionCandidate): ActionValidation {
            if (c.id != null || c.value != null) return invalid(ActionRejection.UNEXPECTED_FIELD)
            val query = c.query
            val shortcut = c.shortcut
            if ((query == null) == (shortcut == null)) return invalid(ActionRejection.UNEXPECTED_FIELD)
            if (shortcut != null) {
                return when (shortcut) {
                    "home" -> valid(NavigateShortcut(Shortcut.HOME))
                    "work" -> valid(NavigateShortcut(Shortcut.WORK))
                    else -> invalid(ActionRejection.DESTINATION_INVALID)
                }
            }
            if (query!!.isBlank()) return invalid(ActionRejection.EMPTY_DESTINATION)
            return when (val check = NavDestinationRules.check(query)) {
                is NavDestinationCheck.Valid -> valid(NavigateQuery(check.raw))
                NavDestinationCheck.Invalid -> invalid(ActionRejection.DESTINATION_INVALID)
            }
        }
    }
}

data class ActionCandidate(
    val kind: String,
    val id: String? = null,
    val value: Int? = null,
    val query: String? = null,
    val shortcut: String? = null,
)

sealed interface ActionValidation {
    data class Valid(val action: ValidatedAction) : ActionValidation
    data class Invalid(val reason: ActionRejection) : ActionValidation
}

interface ActionValidating {
    fun validate(candidate: ActionCandidate, registryVersion: String?): ActionValidation
}

class RegistryActionValidator(private val registry: ActionRegistry) : ActionValidating {
    override fun validate(candidate: ActionCandidate, registryVersion: String?): ActionValidation =
        ValidatedAction.from(candidate, registry, registryVersion)
}
