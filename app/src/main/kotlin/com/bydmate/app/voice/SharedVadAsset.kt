package com.bydmate.app.voice

/** The silero VAD file used by every voice profile. */
interface SharedVadAsset {
    fun vadPath(): String
    fun isVadReady(): Boolean
    suspend fun ensureVad(onProgress: (Int) -> Unit = {}): Result<Unit>
}
