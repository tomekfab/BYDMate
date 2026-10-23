package com.bydmate.app.data.local

import java.util.Locale

/** Converts persisted and platform language tags to one supported app locale. */
object AppLocaleResolver {
    const val DEFAULT_LANGUAGE = "pl"

    private val supportedLanguages = setOf("pl", "en", "zh", "pt", "be")

    fun resolve(storedLanguage: String?): String {
        val language = storedLanguage?.trim()?.let { Locale.forLanguageTag(it).language }
        return language?.takeIf { it in supportedLanguages } ?: DEFAULT_LANGUAGE
    }
}
