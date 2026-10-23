package com.bydmate.app.voice

/** What the floating widget shows for one voice command. There is no decoding stage:
 *  [ContinuousAsrEvent] only has Utterance, SpeechStart and SilenceTick, so decoding is not
 *  observable and SpeechStart stays [LISTENING]. */
enum class VoiceStage {
    IDLE, PREPARING, LISTENING, PROCESSING, RUNNING_COMMAND, WAITING_ASSISTANT,
    STARTED, CONFIRM_ON_SCREEN, DONE, ANSWERED, REFUSED, NOT_UNDERSTOOD, FAILED;

    val isTerminal: Boolean
        get() = when (this) {
            STARTED, CONFIRM_ON_SCREEN, DONE, ANSWERED, REFUSED, NOT_UNDERSTOOD, FAILED -> true
            else -> false
        }
}

/** Immutable presentation snapshot. [speaking] means sound is physically coming from the
 *  speaker, not that speech is queued. */
data class VoiceStatus(
    val stage: VoiceStage,
    val heard: String?,
    val speaking: Boolean,
    val language: VoiceLanguage,
) {
    companion object {
        val IDLE = VoiceStatus(VoiceStage.IDLE, null, false, VoiceLanguage.DEFAULT)
    }
}

const val HEARD_MAX_CHARS = 64

private val WHITESPACE_RUN = Regex("[\\s\\u00A0\\u2007\\u202F]+")

/** One line of heard text for the widget: whitespace runs (newlines too) become one space,
 *  blank gives null, and anything over [maxChars] UTF-16 chars is cut to `maxChars - 1` on a
 *  code-point boundary and ends in an ellipsis. */
fun boundHeard(raw: String?, maxChars: Int = HEARD_MAX_CHARS): String? {
    if (raw == null) return null
    val text = raw.replace(WHITESPACE_RUN, " ").trim()
    if (text.isEmpty()) return null
    if (text.length <= maxChars) return text
    var cut = (maxChars - 1).coerceIn(0, text.length)
    if (cut > 0 && Character.isHighSurrogate(text[cut - 1])) cut--
    return text.substring(0, cut).trimEnd() + "…"
}

/** Hard-coded Polish per session, like [VoiceSessionText] and [PolishVoiceReplies]. */
object VoiceStageLabels {
    fun of(stage: VoiceStage, @Suppress("UNUSED_PARAMETER") language: VoiceLanguage): String = polish(stage)

    private fun polish(stage: VoiceStage): String = when (stage) {
        VoiceStage.IDLE -> ""
        VoiceStage.PREPARING -> "Przygotowuję"
        VoiceStage.LISTENING -> "Słucham"
        VoiceStage.PROCESSING -> "Przetwarzam"
        VoiceStage.RUNNING_COMMAND -> "Wykonuję"
        VoiceStage.WAITING_ASSISTANT -> "Czekam na asystenta"
        VoiceStage.STARTED -> "Uruchomiono"
        VoiceStage.CONFIRM_ON_SCREEN -> "Potwierdź na ekranie"
        VoiceStage.DONE -> "Wykonano"
        VoiceStage.ANSWERED -> "Odpowiedź"
        VoiceStage.REFUSED -> "Odmowa"
        VoiceStage.NOT_UNDERSTOOD -> "Nie rozpoznano"
        VoiceStage.FAILED -> "Błąd"
    }

}

/** Accessibility text: the stage label, then the heard words when there are any. */
fun voiceStatusDescription(status: VoiceStatus): String {
    val label = VoiceStageLabels.of(status.stage, status.language)
    return if (status.heard != null) "$label: ${status.heard}" else label
}
