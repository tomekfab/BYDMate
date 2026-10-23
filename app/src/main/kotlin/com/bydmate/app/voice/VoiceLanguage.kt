package com.bydmate.app.voice

/** Voice profile languages. The ids are persisted and used in file/guard names: never rename. */
enum class VoiceLanguage(val id: String) {
    PL("pl");

    companion object {
        /** Every install, including a malformed legacy selection, uses Polish. */
        val DEFAULT = PL

        fun fromId(id: String?): VoiceLanguage? = PL.takeIf { it.id == id }
    }
}

/** The profile one voice session runs under. Captured once when the session starts and never
 *  updated, so a settings change mid-session cannot mix languages. */
data class VoiceSessionProfile(val language: VoiceLanguage)
