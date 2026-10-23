package com.bydmate.app.voice.openclaw

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * The stable, separate car conversation for the chosen assistant agent. Persisted in `noBackupFilesDir`
 * (never backed up). An explicit reset or a language change starts a new conversation; pairing is
 * kept elsewhere. A separate conversation is organisation only: it does not limit what the paired
 * device may read or do (see the scope limits in the protocol contract).
 */
class OpenClawConversationStore(
    private val dir: File,
    private val agentId: String = OpenClawProtocol.DEFAULT_AGENT_ID,
) {
    private class Stored(val agent: String, val id: String, val language: String)

    private val file get() = File(dir, FILE_NAME)

    /** The current session key for [language]; a different language or agent starts a new conversation. */
    @Synchronized
    fun sessionKey(language: String): String {
        val stored = load()
        if (stored != null && stored.agent == agentId && stored.language == language) {
            return OpenClawProtocol.sessionKey(agentId, stored.id)
        }
        return reset(language)
    }

    @Synchronized
    fun reset(language: String): String {
        val id = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        write(Stored(agentId, id, language))
        return OpenClawProtocol.sessionKey(agentId, id)
    }

    @Synchronized
    fun clear() {
        file.delete()
        File(dir, TEMP_NAME).delete()
    }

    private fun load(): Stored? {
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            Stored(json.getString("agent"), json.getString("id"), json.getString("language"))
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun write(stored: Stored) {
        dir.mkdirs()
        val temp = File(dir, TEMP_NAME)
        temp.writeText(JSONObject().put("agent", stored.agent).put("id", stored.id).put("language", stored.language).toString())
        if (!temp.renameTo(file)) {
            temp.delete()
            throw java.io.IOException("could not store the OpenClaw conversation")
        }
    }

    companion object {
        const val FILE_NAME = "conversation.json"
        private const val TEMP_NAME = "conversation.json.tmp"

        fun create(context: Context, agentId: String = OpenClawProtocol.DEFAULT_AGENT_ID) =
            OpenClawConversationStore(File(context.noBackupFilesDir, OpenClawIdentityStore.DIRECTORY_NAME), agentId)
    }
}
