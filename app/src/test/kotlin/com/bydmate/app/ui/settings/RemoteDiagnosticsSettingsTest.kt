package com.bydmate.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.diagnostics.DiagnosticLogCapture
import com.bydmate.app.diagnostics.DiagnosticUploadConfig
import com.bydmate.app.diagnostics.DiagnosticUploadQueue
import com.bydmate.app.diagnostics.DiagnosticUploadScheduler
import com.bydmate.app.diagnostics.UploadOutcome
import io.mockk.mockk
import io.mockk.verify
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RemoteDiagnosticsSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduler = mockk<DiagnosticUploadScheduler>(relaxed = true)
    private val capture = mockk<DiagnosticLogCapture>(relaxed = true)
    private lateinit var queue: DiagnosticUploadQueue
    private lateinit var credentials: DiagnosticUploadConfig
    private lateinit var settings: RemoteDiagnosticsSettings
    private var now = 100L

    @Before fun setup() {
        context.filesDir.resolve("diagnostic-upload").deleteRecursively()
        context.getSharedPreferences(RemoteDiagnosticsSettings.PREFS, 0).edit().clear().commit()
        queue = DiagnosticUploadQueue(context.filesDir.resolve("diagnostic-upload"), { now })
        credentials = DiagnosticUploadConfig(context).also { it.clear() }
        settings = RemoteDiagnosticsSettings(context, credentials, queue, scheduler, capture)
    }
    private fun document(endpoint: String = DiagnosticUploadConfig.ENDPOINT): String = JSONObject()
        .put("schemaVersion", 1).put("endpoint", endpoint).put("bearerToken", "a".repeat(43)).toString()
    private fun enqueue() {
        val vectors = JSONObject(javaClass.getResource("/diagnostics/receiver-vectors.json")!!.readText())
        val body = JSONObject(vectors.getString("acceptedBatchJson")).put("createdAtMs", now)
        assertTrue(queue.commit(body.toString().toByteArray()))
    }
    @Test fun invalidAndWrongHostImportRejectWithoutRetainedCredentials() {
        for (input in listOf("{}", document("https://example.com:9443"), document().dropLast(1) + ",\"extra\":1}")) {
            assertFalse(settings.importDocument(input))
            assertNull(credentials.read())
        }
        verify(exactly = 0) { scheduler.recover() }
    }
    @Test fun validImportUnblocksRetainedDataAndExposesOnlyConfiguredFlag() {
        enqueue()
        val body = queue.lease()!!
        queue.retain(body.batchId, UploadOutcome.BLOCKED); queue.release(body.batchId)
        assertTrue(settings.importDocument(document()))
        assertTrue(settings.snapshot().configured)
        assertEquals(setOf("queued"), settings.snapshot().statuses)
        assertFalse(settings.snapshot().toString().contains("a".repeat(43)))
        assertTrue(context.noBackupFilesDir.resolve("diagnostic-credentials").isFile)
        assertFalse(context.filesDir.resolve("diagnostic-credentials").exists())
        verify { scheduler.recover() }
    }
    @Test fun selectedSourceRejectsOversizeAndRetainsUnimportedDocument() {
        val source = java.io.File.createTempFile("diagnostic-import", ".json", context.cacheDir)
        try {
            source.writeText(document() + " ".repeat(1024))
            assertEquals(R.string.settings_remote_diagnostics_import_failed,
                settings.importSelected(android.net.Uri.fromFile(source)))
            assertTrue(source.exists())
            assertNull(credentials.read())
        } finally { source.delete() }
    }
    @Test fun importFromReadOnlySourceReportsManualCleanupWithoutExposingCredentials() {
        val source = java.io.File.createTempFile("diagnostic-import", ".json", context.cacheDir)
        try {
            source.writeText(document())
            assertEquals(R.string.settings_remote_diagnostics_imported_source_retained,
                settings.importSelected(android.net.Uri.fromFile(source)))
            assertTrue(source.exists())
            assertTrue(settings.snapshot().configured)
        } finally { source.delete() }
    }
    @Test fun manualSendWhileOffDoesNotEnableCapture() {
        settings.sendNow()
        assertFalse(settings.snapshot().automatic)
        verify(exactly = 1) { capture.sendNow() }
        verify(exactly = 0) { capture.start() }
    }
    @Test fun automaticChoicePersistsWithoutCredentials() {
        settings.setAutomatic(true)
        assertTrue(settings.snapshot().automatic)
        settings.setAutomatic(false)
        assertFalse(settings.snapshot().automatic)
    }
    @Test fun queueReportsQueuedRetryingBlockedTerminalSendingAndSuccess() {
        enqueue()
        assertEquals(1, settings.snapshot().count)
        assertTrue(settings.snapshot().bytes > 0)
        assertEquals(setOf("queued"), settings.snapshot().statuses)
        val body = queue.lease()!!
        assertEquals(setOf("sending"), settings.snapshot().statuses)
        queue.release(body.batchId)
        for ((outcome, state) in listOf(UploadOutcome.RETRY to "retrying", UploadOutcome.BLOCKED to "blocked", UploadOutcome.TERMINAL to "terminal")) {
            queue.retain(body.batchId, outcome)
            assertEquals(setOf(state), settings.snapshot().statuses)
        }
        queue.acknowledge(body)
        assertEquals(now, settings.snapshot().lastSuccessAtMs)
        assertEquals(0, settings.snapshot().count)
    }
    @Test fun expiredBodiesDisappearWithoutInventedHistory() {
        enqueue(); now += DiagnosticUploadQueue.TTL_MS
        assertEquals(0, settings.snapshot().count)
        assertTrue(settings.snapshot().statuses.isEmpty())
        assertNull(settings.snapshot().lastSuccessAtMs)
    }
    private fun enqueue(id: String, createdAtMs: Long) {
        val vectors = JSONObject(javaClass.getResource("/diagnostics/receiver-vectors.json")!!.readText())
        val body = JSONObject(vectors.getString("acceptedBatchJson")).put("batchId", id).put("createdAtMs", createdAtMs)
        assertTrue(queue.commit(body.toString().toByteArray()))
    }
    @Test fun snapshotListsEveryQueuedBatchNewestFirstWithItsOwnStatusAndEvents() {
        now = 300
        enqueue("11111111-1111-4111-8111-111111111111", 100)
        enqueue("22222222-2222-4222-8222-222222222222", 200)
        queue.retain("11111111-1111-4111-8111-111111111111", UploadOutcome.RETRY)
        queue.retain("22222222-2222-4222-8222-222222222222", UploadOutcome.BLOCKED)
        val logs = settings.snapshot().logs
        assertEquals(listOf("22222222-2222-4222-8222-222222222222", "11111111-1111-4111-8111-111111111111"), logs.map { it.batchId })
        assertEquals(listOf("blocked", "retrying"), logs.map { it.status })
        assertEquals(listOf(4, 4), logs.map { it.eventCount })
        assertEquals(3, logs.first().events.size)
        assertEquals(1, logs.first().logcatEntries.size)
    }
    @Test fun snapshotMapsPendingToQueuedAndExposesNoCredentialsOrDeviceMetadata() {
        enqueue()
        assertTrue(settings.importDocument(document()))
        val logs = settings.snapshot().logs
        assertEquals(listOf("queued"), logs.map { it.status })
        val text = logs.toString() + settings.snapshot().toString()
        for (secret in listOf("a".repeat(43), "a".repeat(64), "head_unit", "installIdHash", "1.0-2")) {
            assertFalse("snapshot leaks $secret", text.contains(secret))
        }
    }
    @Test fun unchangedBatchesReuseTheCachedImmutableProjectionAcrossPolls() {
        enqueue()
        val first = settings.snapshot().logs.single()
        assertSame(first, settings.snapshot().logs.single())
    }
    @Test fun acknowledgedBatchLeavesLogsAndSelectionResolvesToNothing() {
        enqueue()
        val id = settings.snapshot().logs.single().batchId
        val body = queue.lease()!!
        assertEquals("sending", settings.snapshot().logs.single().status)
        queue.acknowledge(body)
        val after = settings.snapshot().logs
        assertTrue(after.isEmpty())
        assertNull(resolveRemoteDiagnosticLog(after, id))
    }
    @Test fun expiredBatchLeavesLogs() {
        enqueue(); now += DiagnosticUploadQueue.TTL_MS
        assertTrue(settings.snapshot().logs.isEmpty())
    }
    @Test fun disclosureListsApprovedFieldsAndExplicitExclusions() {
        val id = context.resources.getIdentifier("settings_remote_diagnostics_disclosure", "string", context.packageName)
        assertTrue("Settings disclosure resource exists", id != 0)
        val english = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.ENGLISH)
        }
        val text = context.createConfigurationContext(english).getString(id)
        for (term in listOf("state", "timing", "reason", "HTTP status", "exception class", "audio", "transcripts", "arbitrary logs", "exception messages", "provider bodies", "tokens", "location")) {
            assertTrue("Disclosure contains $term", text.contains(term))
        }
    }
}
