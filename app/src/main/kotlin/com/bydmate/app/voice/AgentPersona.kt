package com.bydmate.app.voice

import kotlin.random.Random

/** Voice-agent personality: short spoken phrases for terminal outcomes (random pick
 *  per announce) plus a runtime system-prompt block. Pure Kotlin, no Android deps. */
enum class AgentPersona(val id: String) {
    SNARKY("snarky"), NAVIGATOR("navigator"), ENGINEER("engineer");

    /** Maps a canonical terse spoken outcome ("Gotowe"/"Nie udało się"/"Nie rozumiem"/"Błąd")
     *  to a random persona phrase; any other string passes through unchanged. */
    fun spokenPhrase(
        spoken: String,
        random: Random = Random.Default,
        gender: TtsGender = TtsGender.MALE,
    ): String = PolishVoiceReplies.persona(this, spoken, random, gender)

    /** A random short filler ("Już sprawdzam.") spoken while a slow tool call is in flight,
     *  so the driver hears something before the real answer. */
    fun fillerPhrase(random: Random = Random.Default): String =
        PolishVoiceReplies.filler(this, random)

    /** Every phrase this persona can speak (outcome pools + fillers), for the online-voice
     *  TTS precache -- nothing here is picked at runtime by index, only by content. */
    fun phrases(gender: TtsGender = TtsGender.MALE): List<String> =
        PolishVoiceReplies.personaPhrases(this, gender)

    companion object {
        fun fromId(id: String?): AgentPersona = entries.firstOrNull { it.id == id } ?: NAVIGATOR
    }
}

/** User-configured agent identity: display/wake name + persona + gender. Read from
 *  SharedPreferences("voice") via a DI-provided lambda (see VoiceModule). */
data class AgentIdentity(
    val name: String,
    val persona: AgentPersona,
    val gender: TtsGender = TtsGender.MALE,
)

/** Runtime system-prompt addition. Appended AFTER the const SYSTEM_PROMPT, never edits it. */
object AgentPersonaPrompt {
    fun block(identity: AgentIdentity): String =
        PolishVoiceReplies.personaPrompt(identity)
}
