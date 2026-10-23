package com.bydmate.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the same telemetry used by ABRP (see
 * [IternioTelemetryClient.buildTelemetry]) to any user URL.
 *
 * There is no queue or retry: telemetry is live, and sending a missed sample later is pointless
 * because the next sample is newer. The caller owns the pause after an error
 * ([com.bydmate.app.service.TrackingService]).
 */
@Singleton
class WebhookTelemetryClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "WebhookTelemetry"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** https anywhere; plain http only on loopback (the only cleartext hosts the app allows). */
        fun isAllowedWebhookUrl(url: HttpUrl): Boolean =
            url.scheme == "https" ||
                (url.scheme == "http" && (url.host == "localhost" || url.host == "127.0.0.1"))
    }

    /**
     * @param url Webhook address. HTTPS is accepted, while HTTP is accepted only for
     * localhost/127.0.0.1: network_security_config.xml permits cleartext only for loopback,
     * and the platform silently blocks other HTTP hosts.
     * @param secret Optional secret, used as `Authorization: Bearer`. It is never logged.
     * @param telemetry Ready JSON, using the same object as Iternio.
     */
    suspend fun send(url: String, secret: String?, telemetry: JSONObject): Result<Unit> =
        withContext(Dispatchers.IO) {
            val httpUrl = url.trim().toHttpUrlOrNull()
            if (httpUrl == null || !isAllowedWebhookUrl(httpUrl)) {
                return@withContext Result.failure(IllegalArgumentException("nieprawidłowy URL webhooka"))
            }
            try {
                val request = Request.Builder()
                    .url(httpUrl)
                    .post(telemetry.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .apply {
                        secret?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            header("Authorization", "Bearer $it")
                        }
                    }
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // Never log the body: a user endpoint may echo the secret back.
                        Log.w(TAG, "HTTP ${response.code}")
                        return@withContext Result.failure(IllegalStateException("HTTP ${response.code}"))
                    }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "wysyłanie nie powiodło się: ${e.message}")
                Result.failure(e)
            }
        }
}
