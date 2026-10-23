package com.bydmate.app.voice

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RED contract for the Step 2 cleanup.  The production class is intentionally not present yet.
 * Its minimal injected boundary is: the app files root, a coordinated native-release callback,
 * an IO dispatcher, and a typed non-fatal warning sink.
 */
class LegacyRussianAssetCleanupTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `releases native Russian assets before deleting only the explicit allowlist`() = runBlocking {
        val events = mutableListOf<String>()
        val protectedVad = File(root, "asr/shared-vad")
        val protectedGosia = File(root, "tts/gosia")
        val protectedPolishStaging = File(root, "tts/.staging-gosia")
        (LegacyRussianAssetCleanup.LEGACY_RELATIVE_PATHS + listOf(
            "asr/shared-vad",
            "tts/gosia",
            "tts/.staging-gosia",
            "user/recording.wav",
        )).forEach { relative ->
            File(root, relative).apply { mkdirs(); File(this, "sentinel").writeText(relative) }
        }

        LegacyRussianAssetCleanup(
            filesRoot = root,
            releaseRussianAssets = { events += "release" },
            ioDispatcher = Dispatchers.Unconfined,
            warningSink = { events += "warning:${it::class.simpleName}" },
        ).runAfterMigration()

        assertEquals("native handles release before filesystem cleanup", "release", events.first())
        LegacyRussianAssetCleanup.LEGACY_RELATIVE_PATHS.forEach { relative ->
            assertFalse("legacy path removed: $relative", File(root, relative).exists())
        }
        assertTrue(protectedVad.exists())
        assertTrue(protectedGosia.exists())
        assertTrue(protectedPolishStaging.exists())
        assertTrue(File(root, "user/recording.wav").exists())
    }

    @Test fun `allowlist includes every removed Russian manager and catalog directory including staging`() {
        assertEquals(
            setOf(
                "asr/gigaam-v3-ru",
                "asr/.staging-gigaam-v3-ru",
                "asr/gigaam-v2-ru",
                "asr/.staging-gigaam-v2-ru",
                "tts/dmitri",
                "tts/.staging-dmitri",
                "tts/vits-ru-multi",
                "tts/.staging-vits-ru-multi",
                "tts/ruslan",
                "tts/.staging-ruslan",
                "tts/irina",
                "tts/.staging-irina",
                "tts/supertonic-ru",
                "tts/.staging-supertonic-ru",
                "tts/.staging-supertonic-ru-stress",
            ),
            LegacyRussianAssetCleanup.LEGACY_RELATIVE_PATHS,
        )
    }

    @Test fun `failure is a typed warning and does not prevent repeated cleanup`() = runBlocking {
        val warnings = mutableListOf<LegacyRussianAssetCleanupWarning>()
        val releaseCalls = mutableListOf<Unit>()
        File(root, "tts/dmitri").mkdirs()
        val cleanup = LegacyRussianAssetCleanup(
            filesRoot = root,
            releaseRussianAssets = { releaseCalls += Unit },
            ioDispatcher = Dispatchers.Unconfined,
            warningSink = warnings::add,
            deleteRecursively = { file ->
                if (file.relativeTo(root).path == "tts/dmitri") false else file.deleteRecursively()
            },
        )

        cleanup.runAfterMigration()
        cleanup.runAfterMigration()

        assertEquals(2, releaseCalls.size)
        assertEquals(2, warnings.size)
        assertTrue(warnings.all { it.path == "tts/dmitri" })
    }

    @Test fun `delete exception is a typed warning and does not stop the remaining allowlist`() = runBlocking {
        val warnings = mutableListOf<LegacyRussianAssetCleanupWarning>()
        File(root, "tts/dmitri").mkdirs()
        File(root, "tts/ruslan").mkdirs()

        LegacyRussianAssetCleanup(
            filesRoot = root,
            releaseRussianAssets = {},
            ioDispatcher = Dispatchers.Unconfined,
            warningSink = warnings::add,
            deleteRecursively = { file ->
                if (file.relativeTo(root).path == "tts/dmitri") error("storage error")
                else file.deleteRecursively()
            },
        ).runAfterMigration()

        assertEquals(listOf("tts/dmitri"), warnings.mapNotNull { it.path })
        assertFalse(File(root, "tts/ruslan").exists())
    }

    @Test fun `release cancellation propagates without deleting assets`() = runBlocking {
        val legacy = File(root, "tts/dmitri").apply { mkdirs() }
        val cleanup = LegacyRussianAssetCleanup(
            filesRoot = root,
            releaseRussianAssets = { throw CancellationException("release cancelled") },
            ioDispatcher = Dispatchers.Unconfined,
        )

        try {
            cleanup.runAfterMigration()
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation must never become a non-fatal migration warning.
        }

        assertTrue(legacy.exists())
    }

    private val root: File get() = temporaryFolder.root
}
