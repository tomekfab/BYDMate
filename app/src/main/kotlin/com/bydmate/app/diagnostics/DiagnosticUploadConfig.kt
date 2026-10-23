package com.bydmate.app.diagnostics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Never use a data class: generated toString must not reveal the credential. */
class DiagnosticCredentials internal constructor(val bearerToken: String) {
    val endpoint: String get() = DiagnosticUploadConfig.ENDPOINT
    override fun toString(): String = "DiagnosticCredentials(redacted)"
}

@Singleton
class DiagnosticUploadConfig @Inject constructor(@ApplicationContext context: Context) {
    private val file = File(context.noBackupFilesDir, "diagnostic-credentials")
    @Synchronized fun read(): DiagnosticCredentials? = runCatching { parse(file.readText()) }.getOrNull()
    @Synchronized fun importDocument(document: String): Boolean {
        val credentials = parse(document) ?: return false
        file.parentFile?.mkdirs()
        atomicWrite(file, JSONObject().put("schemaVersion", 1).put("endpoint", ENDPOINT)
            .put("bearerToken", credentials.bearerToken).toString().toByteArray())
        return true
    }
    @Synchronized fun clear() { file.delete() }

    companion object {
        const val ENDPOINT = "https://bobs-mac-mini-openclaw.tail3549c7.ts.net:9443"
        fun parse(document: String): DiagnosticCredentials? = runCatching {
            require(document.toByteArray().size <= 1024)
            val json = strictDiagnosticObject(document)
            RemoteDiagnosticEventParser.exact(json, setOf("schemaVersion", "endpoint", "bearerToken"))
            RemoteDiagnosticEventParser.integer(json.get("schemaVersion"), 1, 1)
            require(json.get("endpoint") == ENDPOINT)
            val token = json.get("bearerToken")
            require(token is String && Regex("[A-Za-z0-9_-]{43}").matches(token))
            DiagnosticCredentials(token)
        }.getOrNull()
    }
}
