package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import org.json.JSONObject

sealed interface NavDestinationCheck {
    data class Valid(val raw: String) : NavDestinationCheck
    data object Invalid : NavDestinationCheck
}

object NavDestinationRules {
    const val MAX_QUERY_CHARS = 200

    private val ASCII_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:(?!\\s)")

    fun check(raw: String): NavDestinationCheck {
        if (raw.isBlank() || raw.length > MAX_QUERY_CHARS || raw.any(Char::isISOControl)) {
            return NavDestinationCheck.Invalid
        }
        if (raw.contains("://") || ASCII_SCHEME.containsMatchIn(raw)) {
            return NavDestinationCheck.Invalid
        }
        return NavDestinationCheck.Valid(raw)
    }
}

object NavigateActionMapper {
    fun toActionDef(valid: NavDestinationCheck.Valid, go: Boolean): ActionDef =
        ActionDef(
            command = "",
            displayName = "",
            kind = "navigate",
            payload = JSONObject().put("query", valid.raw).put("go", go).toString(),
        )
}
