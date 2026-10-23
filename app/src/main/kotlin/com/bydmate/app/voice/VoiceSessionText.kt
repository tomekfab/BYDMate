package com.bydmate.app.voice

/**
 * The orb's overlay lines use neutral Polish wording (no gendered verbs). The spoken line is
 * separate: the controller passes canonical outcome words to the persona
 * ([AgentPersona.spokenPhrase]).
 */
internal class VoiceSessionText(@Suppress("UNUSED_PARAMETER") language: VoiceLanguage) {
    fun done(heard: String) = "Rozpoznano: «$heard». Wykonano"

    fun running(heard: String) = "Rozpoznano: «$heard». Wykonuję"

    fun refused(heard: String, reason: String) = "Rozpoznano: «$heard». Odmowa: $reason"

    fun notUnderstood(heard: String) = "Nie rozpoznano: «$heard»"

    val genericNotUnderstood = "Nie rozpoznano"

    fun internalError(message: String?) = "Odmowa: ${message ?: "błąd wewnętrzny"}"

    fun acTempUnknown() = PolishVoiceReplies.problem(PolishVoiceReplies.Problem.AC_TEMP_UNKNOWN)
}
