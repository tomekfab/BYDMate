package com.bydmate.app.voice

import com.bydmate.app.agent.ActionConfirmer
import com.bydmate.app.agent.ConfirmResult

/** The phone book as the offline voice call reads it. */
interface VoiceContacts {
    fun hasPermission(): Boolean
    suspend fun all(): List<VoiceContact>
}

/** No phone book: every call is refused for missing permission. */
object NoVoiceContacts : VoiceContacts {
    override fun hasPermission(): Boolean = false
    override suspend fun all(): List<VoiceContact> = emptyList()
}

/** No confirmation surface: a loose call match is never dialed. */
internal object NoCallConfirmer : ActionConfirmer {
    override suspend fun confirm(title: String, summary: String): ConfirmResult = ConfirmResult.UNAVAILABLE
}
