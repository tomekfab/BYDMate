package com.bydmate.app.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Deletes only known retired Russian model directories after their native users released them.
 * Preference values are deliberately not inputs: they are user data and must never become paths. */
class LegacyRussianAssetCleanup(
    private val filesRoot: File,
    private val releaseRussianAssets: suspend () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val warningSink: (LegacyRussianAssetCleanupWarning) -> Unit = {},
    private val deleteRecursively: (File) -> Boolean = { it.deleteRecursively() || !it.exists() },
) {
    suspend fun runAfterMigration() = withContext(ioDispatcher) {
        try {
            releaseRussianAssets()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            // Native-release implementations expose only this non-fatal failure boundary.
            warn(LegacyRussianAssetCleanupWarning.ReleaseFailure(failure))
            return@withContext
        }
        LEGACY_RELATIVE_PATHS.forEach { path ->
            val target = File(filesRoot, path)
            val deleted = runCatching { !target.exists() || deleteRecursively(target) }.getOrDefault(false)
            if (!deleted) {
                warn(LegacyRussianAssetCleanupWarning.DeleteFailure(path))
            }
        }
    }

    private fun warn(warning: LegacyRussianAssetCleanupWarning) = runCatching { warningSink(warning) }

    companion object {
        /** Derived from the retired GigaAM manager and TTS catalog, including their staging dirs. */
        val LEGACY_RELATIVE_PATHS: Set<String> = linkedSetOf(
            "asr/gigaam-v3-ru", "asr/.staging-gigaam-v3-ru",
            "asr/gigaam-v2-ru", "asr/.staging-gigaam-v2-ru",
            "tts/dmitri", "tts/.staging-dmitri",
            "tts/ruslan", "tts/.staging-ruslan",
            "tts/irina", "tts/.staging-irina",
            "tts/vits-ru-multi", "tts/.staging-vits-ru-multi",
            "tts/supertonic-ru", "tts/.staging-supertonic-ru", "tts/.staging-supertonic-ru-stress",
        )
    }
}

sealed class LegacyRussianAssetCleanupWarning(open val path: String?) {
    data class ReleaseFailure(val failure: Throwable) : LegacyRussianAssetCleanupWarning(null)
    data class DeleteFailure(override val path: String) : LegacyRussianAssetCleanupWarning(path)
}
