package com.bydmate.app.navdata

import org.json.JSONObject

/**
 * Byte budgets for one recorded record, measured the way the capture store serializes it.
 * The 16 KiB record limit leaves at least 2 KiB for the envelope (package, kind, timestamps, metadata, icon names).
 */
internal object NavCaptureBudget {
    const val TREE_BYTES = 10 * 1024
    const val TEXT_BYTES = 2 * 1024
    const val EXTRAS_BYTES = 6 * 1024
    const val MAX_EXTRAS_KEYS = 64

    /** UTF-8 length of [value] once JSON-escaped, without the surrounding quotes. */
    fun serializedBytes(value: String): Int = JSONObject.quote(value).toByteArray(Charsets.UTF_8).size - 2

    /** Cuts at a code-point boundary so that `serializedBytes(result) <= maxBytes`. */
    fun truncate(value: String, maxBytes: Int): Pair<String, Boolean> {
        if (serializedBytes(value) <= maxBytes) return value to false
        var used = 0
        var end = 0
        while (end < value.length) {
            val next = end + Character.charCount(value.codePointAt(end))
            val cost = serializedBytes(value.substring(end, next))
            if (used + cost > maxBytes) break
            used += cost
            end = next
        }
        // Escaping can depend on the previous character, so confirm the whole prefix.
        while (end > 0 && serializedBytes(value.substring(0, end)) > maxBytes) end -= Character.charCount(value.codePointBefore(end))
        return value.substring(0, end) to true
    }

    /** Keeps entries in order while keys plus values fit; the last kept value is cut, the rest are dropped. */
    fun boundExtras(extras: Map<String, String>, maxBytes: Int, maxKeys: Int): Pair<Map<String, String>, Boolean> {
        val kept = LinkedHashMap<String, String>()
        var remaining = maxBytes
        for ((key, value) in extras) {
            val keyBytes = serializedBytes(key)
            if (kept.size >= maxKeys || keyBytes > remaining) return kept to true
            val (bounded, cut) = truncate(value, remaining - keyBytes)
            kept[key] = bounded
            if (cut) return kept to true
            remaining -= keyBytes + serializedBytes(bounded)
        }
        return kept to false
    }
}
