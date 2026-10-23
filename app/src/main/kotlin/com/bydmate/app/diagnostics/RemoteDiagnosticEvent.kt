package com.bydmate.app.diagnostics

import org.json.JSONObject

/** Immutable validated event. No raw text or throwable messages cross this boundary. */
class RemoteDiagnosticEvent internal constructor(private val encoded: String) {
    fun json(): JSONObject = JSONObject(encoded)
    internal fun factKey(): String = json().apply { remove("atMs") }.toString()
    override fun equals(other: Any?): Boolean = other is RemoteDiagnosticEvent && encoded == other.encoded
    override fun hashCode(): Int = encoded.hashCode()
}

internal const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
internal const val MAX_BATCH_BYTES = 64 * 1024
internal fun bounded(value: Long): Long = value.coerceIn(0, MAX_SAFE_INTEGER)
