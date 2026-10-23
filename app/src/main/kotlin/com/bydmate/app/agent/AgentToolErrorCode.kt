package com.bydmate.app.agent

import org.json.JSONObject

/**
 * Fixed code of a tool's error for the trace. The error texts are Polish, written for the model
 * and the driver, and some quote a name the driver said, so the text itself never goes into the
 * trace: the first rule whose fragment it contains names it, [OTHER] when none does.
 */
internal object AgentToolErrorCode {

    const val OTHER = "other"

    // Most specific first: "usługa wyszukiwania ładowarek jest niedostępna" must not read as "wyszukiwanie jest niedostępne".
    private val RULES = listOf(
        "brak danych z samochodu" to "no_vehicle_data",
        "nieznane narzędzie" to "unknown_tool",
        "uruchomionej przez automatyzację" to "not_in_automation",
        "błąd wewnętrzny" to "internal",
        "usługa map jest niedostępna" to "maps_unavailable",
        "wyszukiwanie miejscowości jest niedostępne" to "settlements_unavailable",
        "usługa wyszukiwania ładowarek jest niedostępna" to "chargers_unavailable",
        "wyszukiwanie jest niedostępne" to "search_unavailable",
        "pogoda jest niedostępna" to "weather_unavailable",
        "nie znaleziono miasta" to "city_not_found",
        "brak GPS" to "no_gps",
        "nie znaleziono takiego miejsca" to "place_not_found",
        "prędkość jest nieznana" to "speed_unknown",
        "tylko na postoju" to "park_required",
        "nie wykonano części działań" to "partial",
        "nie znaleziono automatyzacji" to "automation_not_found",
        "nie znaleziono kontaktu" to "contact_not_found",
        "potwierdzenie na ekranie" to "needs_confirmation",
        "brak dostępu" to "no_permission",
        "Nawigator" to "navigator",
        "podział ekranu nie jest uruchomiony" to "split_not_running",
        "podział ekranu" to "split_unavailable",
        "danych" to "not_enough_data",
        "już istnieje" to "exists",
        "osiągnięto limit" to "limit",
        "nieprawidłow" to "bad_args",
        "nie podano" to "missing_args",
        "podaj" to "missing_args",
        "wymagane są oba" to "missing_args",
        "wymagane jest albo" to "missing_args",
        "nieznan" to "unknown_value",
        "nie udało się" to "failed",
        "nie udało się" to "failed",
        "nie wykonano" to "failed",
    )

    /** The code of [result]'s `error`, null when the tool did not fail. */
    fun of(result: String): String? {
        val error = runCatching { JSONObject(result).optString("error") }.getOrNull()
        if (error.isNullOrEmpty()) return null
        return RULES.firstOrNull { (fragment, _) -> error.contains(fragment, ignoreCase = true) }?.second ?: OTHER
    }
}
