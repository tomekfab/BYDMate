package com.bydmate.app.voice

import com.bydmate.app.R

enum class TtsGender { MALE, FEMALE }

/** Gosia uses one single-speaker Piper archive. */
enum class TtsVoiceEngine { PIPER }

/** The sole downloadable offline voice. Its id is both the preference value and UI key. */
data class TtsVoice(
    val id: String,
    val labelRes: Int,
    val url: String,
    val gender: TtsGender,
    val engine: TtsVoiceEngine,
    val modelDirId: String,
    val speakerId: Int,
    val sizeMb: Int,
    val language: VoiceLanguage = VoiceLanguage.PL,
    val displayName: String? = null,
)

/** Gosia is the only selectable, downloadable and synthesized voice. */
object TtsVoiceCatalog {
    /** The one verified Polish voice: Piper Gosia medium int8, the archive pinned (URL, size and
     *  SHA-256) in [PolishModelManifest.GOSIA_MEDIUM_INT8]. Its model card (CC0 dataset) is part
     *  of that pin. No gender pair exists, so persona/gender changes keep this voice. */
    val POLISH: List<TtsVoice> = listOf(
        TtsVoice(
            id = "gosia", labelRes = 0, url = PolishModelManifest.GOSIA_MEDIUM_INT8.downloads.single().url,
            gender = TtsGender.FEMALE, engine = TtsVoiceEngine.PIPER,
            modelDirId = "gosia", speakerId = 0, sizeMb = 21,
            language = VoiceLanguage.PL, displayName = "Gosia",
        ),
    )
    val ALL: List<TtsVoice> = POLISH
    val GOSIA: TtsVoice get() = POLISH.single()

    /** Any missing, retired or corrupted preference resolves to Gosia. */
    /** Legacy stored IDs are intentionally accepted and migrated to the sole supported voice. */
    fun byId(@Suppress("UNUSED_PARAMETER") id: String): TtsVoice = GOSIA

    fun defaultFor(): TtsVoice = GOSIA

    /** There is no alternate engine or speaker. */
    fun counterpart(): TtsVoice = GOSIA
}
