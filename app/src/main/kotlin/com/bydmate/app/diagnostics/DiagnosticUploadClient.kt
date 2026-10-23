package com.bydmate.app.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class UploadOutcome { SENT, RETRY, BLOCKED, TERMINAL }

@Singleton
class DiagnosticUploadClient internal constructor(private val http: OkHttpClient) {
    @Inject constructor() : this(OkHttpClient())
    // A caller cannot re-enable redirects through the supplied client.
    internal val transport = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun upload(record: DiagnosticBody, credentials: DiagnosticCredentials): UploadOutcome = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${credentials.endpoint}/v1/diagnostic-batches")
            .header("Authorization", "Bearer ${credentials.bearerToken}")
            .header("X-BYDMate-Batch", record.batchId).header("X-BYDMate-Digest", record.digest)
            .post(record.bytes.toRequestBody("application/json".toMediaType())).build()
        try {
            transport.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val source = response.body?.source()
                        if (source == null || source.request(1025)) UploadOutcome.TERMINAL
                        else acknowledge(record.batchId, record.digest, source.readUtf8())
                    }
                    401, 403 -> UploadOutcome.BLOCKED
                    in 500..599 -> UploadOutcome.RETRY
                    else -> UploadOutcome.TERMINAL
                }
            }
        } catch (_: IOException) { UploadOutcome.RETRY }
    }

    fun acknowledge(batchId: String, digest: String, response: String): UploadOutcome = runCatching {
        val json = strictDiagnosticObject(response)
        RemoteDiagnosticEventParser.exact(json, setOf("batchId", "digest"))
        require(json.get("batchId") == batchId && json.get("digest") == digest)
        UploadOutcome.SENT
    }.getOrDefault(UploadOutcome.TERMINAL)
}
