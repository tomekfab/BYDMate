package com.bydmate.app.voice

/** Stable codes for why a voice session did not become a command. They appear verbatim in the
 *  voice journal, the dump and logcat, so issue reports can be grepped by them. */
object VoiceRefusal {
    const val UNRECOGNIZED = "unrecognized"
    /** A dictionary question whose value the car does not report: the phrase goes to the agent. */
    const val VALUE_UNKNOWN = "value_unknown"
    const val ASR_EMPTY = "asr_empty"
    const val ASR_FAILED = "asr_failed"
    const val MODEL_MISSING = "asr_model_missing"
    const val ECHO = "echo"
    const val AGENT_FOLLOWUP_WINDOW = "agent_followup_window"
    const val BARGE_IN = "barge_in"
    const val HARD_STOP = "hard_stop"
    const val DISPATCH_FAILED = "dispatch_failed"
    const val RULE_NOT_FOUND = "rule_not_found"
    const val INTERNAL_ERROR = "internal_error"
    /** Unknown Polish speech while the online assistant is off: nothing was sent anywhere. */
    const val AI_DISABLED = "ai_disabled"
    /** The online assistant could not answer; the phrase was not sent to anything else. */
    const val ASSISTANT_FAILED = "assistant_failed"
    /** A local command arrived while an assistant turn ran: the turn was cancelled for it. */
    const val LOCAL_PRIORITY = "local_priority"

    /** A safety gate held the command back, e.g. `gate:speed_unknown`. */
    fun gate(name: String): String = "gate:$name"
}

/** What the built-in dictionary made of a phrase: a command to run, a phrase for the assistant, or
 *  a request to the car that is refused here and never leaves the device. */
sealed interface NluOutcome {
    data class Understood(val result: ParseResult) : NluOutcome

    /** Nothing in the dictionary and nothing car-like in it: the assistant may take it when enabled.
     *  [reason] is a [VoiceRefusal] code. */
    data class Refused(val reason: String) : NluOutcome

    /** It looks like a request to the car and nothing runs. [reason] is an [NluRefusal] code. It is
     *  answered locally and never goes to the assistant, whatever the online settings are. */
    data class Rejected(val reason: String) : NluOutcome

    companion object {
        fun of(result: ParseResult): NluOutcome = when (result) {
            ParseResult.Unrecognized -> Refused(VoiceRefusal.UNRECOGNIZED)
            else -> Understood(result)
        }

        /** Every verdict of the dictionary: a refused request is [Rejected], never [Refused]. */
        fun of(verdict: NluVerdict): NluOutcome = when (verdict) {
            is NluVerdict.Match -> of(verdict.result)
            NluVerdict.Unknown -> Refused(VoiceRefusal.UNRECOGNIZED)
            is NluVerdict.Refused -> Rejected(verdict.reason)
        }
    }
}
