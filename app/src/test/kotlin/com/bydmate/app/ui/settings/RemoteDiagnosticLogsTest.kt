package com.bydmate.app.ui.settings

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presentation contract for the Settings viewer of locally queued diagnostic batches. */
class RemoteDiagnosticLogsTest {
    private val vectors = JSONObject(javaClass.getResource("/diagnostics/receiver-vectors.json")!!.readText())

    private fun body(
        id: String = "12345678-1234-4234-8234-123456789abc",
        createdAtMs: Long = 1,
        dropped: Long = 0,
        truncated: Long = 0,
        counters: Triple<Long, Long, Long> = Triple(0, 0, 0),
    ): ByteArray {
        val json = JSONObject(vectors.getString("acceptedBatchJson")).put("batchId", id).put("createdAtMs", createdAtMs)
        json.getJSONObject("logcat").put("droppedUnrecognisedLines", dropped).put("truncatedLogEvents", truncated)
        json.getJSONObject("counters").put("captureDropped", counters.first)
            .put("queueDropped", counters.second).put("storageFailures", counters.third)
        return json.toString().toByteArray()
    }

    @Test fun projectsEventsLogcatEntriesAndEveryCounterFromOneBatch() {
        val batch = projectRemoteDiagnosticLog(body(dropped = 7, truncated = 9, counters = Triple(3, 4, 5)), "retrying")!!
        assertEquals("12345678-1234-4234-8234-123456789abc", batch.batchId)
        assertEquals(1L, batch.createdAtMs)
        assertEquals("retrying", batch.status)
        assertEquals(listOf("voice.failure", "connection.request", "connection.failure"), batch.events.map { it.type })
        assertEquals(listOf(1L, 2L, 3L), batch.events.map { it.atMs })
        assertEquals(listOf("voice.lifecycle"), batch.logcatEntries.map { it.type })
        assertEquals("listening", batch.logcatEntries.single().fields["state"])
        assertEquals("openclaw", batch.events[2].fields["backend"])
        assertEquals("IOException", batch.events[2].fields["exceptionClass"])
        assertEquals("200", batch.events[1].fields["httpStatus"])
        assertEquals(4, batch.eventCount)
        assertEquals(7L, batch.droppedUnrecognisedLines)
        assertEquals(9L, batch.truncatedLogEvents)
        assertEquals(3L, batch.counters.captureDropped)
        assertEquals(4L, batch.counters.queueDropped)
        assertEquals(5L, batch.counters.storageFailures)
    }

    @Test fun projectionOmitsConfigurationAppAndEnvironmentFields() {
        val text = projectRemoteDiagnosticLog(body(), "queued")!!.toString()
        for (secret in listOf("a".repeat(64), "installIdHash", "head_unit", "1.0-2", "buildType", "microphone", "notificationListener", "manufacturer", "Bearer", "token")) {
            assertFalse("projection leaks $secret", text.contains(secret))
        }
    }

    @Test fun cacheShowsProtocolValidPrettyPrintedBatches() {
        val bytes = JSONObject(body().toString(Charsets.UTF_8)).toString(2).toByteArray()
        val expected = projectRemoteDiagnosticLog(bytes, "queued")!!
        val cache = RemoteDiagnosticLogCache()
        val states = mapOf(expected.batchId to "pending")
        assertEquals(listOf(expected), cache.project(listOf(bytes), states))
        org.junit.Assert.assertSame(cache.project(listOf(bytes), states).single(), cache.project(listOf(bytes.copyOf()), states).single())
    }

    @Test fun invalidBytesProjectToNullInsteadOfThrowing() {
        assertNull(projectRemoteDiagnosticLog("{}".toByteArray(), "queued"))
        assertNull(projectRemoteDiagnosticLog(ByteArray(0), "queued"))
    }

    @Test fun selectionResolvesFromRefreshedListAndMissingBatchIsNull() {
        val first = projectRemoteDiagnosticLog(body(id = "11111111-1111-4111-8111-111111111111", createdAtMs = 10), "queued")!!
        val second = projectRemoteDiagnosticLog(body(id = "22222222-2222-4222-8222-222222222222", createdAtMs = 20), "queued")!!
        assertEquals(second, resolveRemoteDiagnosticLog(listOf(second, first), second.batchId))
        assertNull("acknowledged or expired batch must not stay selected", resolveRemoteDiagnosticLog(listOf(first), second.batchId))
        assertNull(resolveRemoteDiagnosticLog(emptyList(), first.batchId))
        assertNull(resolveRemoteDiagnosticLog(listOf(first), null))
    }

    @Test fun newestFirstOrderingSortsByCreationTimeThenBatchId() {
        val older = projectRemoteDiagnosticLog(body(id = "11111111-1111-4111-8111-111111111111", createdAtMs = 10), "queued")!!
        val newer = projectRemoteDiagnosticLog(body(id = "22222222-2222-4222-8222-222222222222", createdAtMs = 20), "queued")!!
        val tieLow = projectRemoteDiagnosticLog(body(id = "33333333-3333-4333-8333-333333333333", createdAtMs = 20), "queued")!!
        assertEquals(listOf(tieLow, newer, older).map { it.batchId },
            newestFirst(listOf(older, newer, tieLow)).map { it.batchId })
    }

    @Test fun everyLocaleDefinesTheViewerStrings() {
        val names = listOf(
            "settings_remote_diagnostics_logs_show", "settings_remote_diagnostics_logs_title",
            "settings_remote_diagnostics_logs_close", "settings_remote_diagnostics_logs_empty",
            "settings_remote_diagnostics_logs_removed", "settings_remote_diagnostics_logs_help",
            "settings_remote_diagnostics_logs_batch", "settings_remote_diagnostics_logs_events",
            "settings_remote_diagnostics_logs_logcat", "settings_remote_diagnostics_logs_counters",
            "settings_remote_diagnostics_logs_dropped", "settings_remote_diagnostics_logs_truncated",
        )
        for (folder in listOf("values", "values-pl", "values-be", "values-en", "values-pt", "values-zh")) {
            val text = File(projectRoot(), "app/src/main/res/$folder/strings.xml").readText()
            for (name in names) assertTrue("$folder missing $name", Regex("<string name=\"$name\"[^>]*>[^<]+</string>").containsMatchIn(text))
        }
    }

    @Test fun settingsScreenWiresTheStoredLogsViewerWithoutBreakingFourArgumentCalls() {
        val screen = File(projectRoot(), SCREEN).readText()
        assertTrue(screen.contains("R.string.settings_remote_diagnostics_logs_show"))
        assertTrue("dismissible dialog composable", screen.contains("RemoteDiagnosticLogsDialog("))
        assertTrue("dismissal handler", Regex("onDismissRequest").containsMatchIn(screen.substringAfter("fun RemoteDiagnosticLogsDialog(")))
        assertTrue("scrollable list", screen.substringAfter("fun RemoteDiagnosticLogsDialog(").let {
            it.contains("LazyColumn") || it.contains("verticalScroll")
        })
        val signature = screen.substringAfter("internal fun RemoteDiagnosticsSection(\n").substringBefore(") {")
        assertTrue("existing four parameters remain", listOf("state: SettingsUiState", "onAutomaticChange", "onImport: () -> Unit", "onSend: () -> Unit").all(signature::contains))
        assertTrue("new parameter defaults so four-argument calls compile", Regex("onShowLogs: \\(\\) -> Unit = \\{\\}").containsMatchIn(signature))
        assertTrue(screen.contains("resolveRemoteDiagnosticLog("))
    }

    private fun projectRoot(): File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .firstOrNull { File(it, SCREEN).isFile } ?: error("Cannot find $SCREEN from ${File(".").canonicalPath}")

    private companion object {
        const val SCREEN = "app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsScreen.kt"
    }
}
