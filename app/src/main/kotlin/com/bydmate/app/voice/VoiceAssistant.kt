package com.bydmate.app.voice

import com.bydmate.app.voice.openclaw.ParsedEnvelope

/** What the Polish voice session gets back from the remote assistant. Only [Answer] may be spoken. */
sealed interface AssistantReply {
    data class Answer(val text: String) : AssistantReply

    /**
     * An action turn whose reply carried a fenced action block (or a malformed one). [prose] is the
     * text around the block; [envelope] is not yet validated against the registry. [turnKey] and
     * [recovered] come from the transport; [startedAtMs] is when the turn was asked.
     */
    data class Proposal(
        val prose: String,
        val envelope: ParsedEnvelope,
        val recovered: Boolean,
        val turnKey: String,
        val startedAtMs: Long,
    ) : AssistantReply

    /** The turn was cancelled on purpose. Nothing is said. */
    data object Aborted : AssistantReply

    /**
     * No answer. [problem] is what the driver hears, [detail] is a Polish line for the settings
     * screen. [uncertain] means the question may still have reached the assistant; it is never
     * sent again on its own.
     */
    data class Problem(
        val problem: PolishVoiceReplies.Problem,
        val detail: String,
        val uncertain: Boolean = false,
    ) : AssistantReply
}

/**
 * The one online assistant a Polish session may use: a text-only chat with the user's own
 * assistant. The controller asks it only for harmless unknown speech and only when [isEnabled];
 * a [Problem][AssistantReply.Problem] is final and never sends the question to another provider.
 */
interface VoiceAssistant {
    /** The user's separate opt-in. False means no network call of any kind. */
    fun isEnabled(): Boolean

    /** Sends [text] and waits for the final answer. [onDelta] runs on a network thread. */
    suspend fun ask(text: String, onDelta: (String) -> Unit = {}): AssistantReply

    /** Stops the running turn; it then returns [AssistantReply.Aborted]. Safe when nothing runs. */
    suspend fun cancel()
}

/** The default: no online assistant. A controller without one never makes a network request. */
object NoAssistant : VoiceAssistant {
    override fun isEnabled(): Boolean = false
    override suspend fun ask(text: String, onDelta: (String) -> Unit): AssistantReply =
        AssistantReply.Problem(
            PolishVoiceReplies.Problem.AI_DISABLED,
            PolishVoiceReplies.problem(PolishVoiceReplies.Problem.AI_DISABLED),
        )
    override suspend fun cancel() = Unit
}
