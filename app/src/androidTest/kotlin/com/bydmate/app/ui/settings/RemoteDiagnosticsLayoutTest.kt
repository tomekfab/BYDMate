package com.bydmate.app.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.R
import com.bydmate.app.ui.widget.WidgetPreviewActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Measures the production section in the 290dp Settings pane, without service or import I/O. */
@RunWith(Parameterized::class)
class RemoteDiagnosticsLayoutTest(private val language: String, private val configured: Boolean) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun configurationQueueUploadStateAndBothActionsFitNarrowPane() {
        val diagnostics = RemoteDiagnosticsState(
            configured = configured,
            count = 5,
            bytes = 12345L,
            statuses = setOf("queued", "sending", "retrying", "blocked", "terminal"),
            lastSuccessAtMs = if (configured) 1_792_550_000_000L else null,
        )
        val state = SettingsUiState(
            remoteDiagnostics = diagnostics,
            remoteDiagnosticsImportStatus = if (configured) R.string.settings_remote_diagnostics_imported_source_retained
                else R.string.settings_remote_diagnostics_import_failed,
        )
        ActivityScenario.launch(WidgetPreviewActivity::class.java).use { scenario ->
            val positioned = CountDownLatch(1)
            val section = AtomicReference<Rect>()
            val sectionHeight = AtomicInteger()
            var context: Context? = null
            scenario.onActivity { activity ->
                val configuration = Configuration(activity.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                    fontScale = 1f
                }
                val localized = activity.createConfigurationContext(configuration)
                context = localized
                activity.setContent {
                    CompositionLocalProvider(
                        LocalContext provides localized,
                        LocalConfiguration provides configuration,
                        LocalDensity provides Density(DENSITY, 1f),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            // Settings scrolls. Measure natural height too, so scrolling cannot hide
                            // an action row which grows to thousands of dp as its label collapses.
                            Column(Modifier.width(290.dp).heightIn(max = 900.dp).verticalScroll(rememberScrollState())) {
                                Column(Modifier.onGloballyPositioned {
                                    section.set(it.boundsInRoot())
                                    sectionHeight.set(it.size.height)
                                    positioned.countDown()
                                }) {
                                    RemoteDiagnosticsSection(
                                        state = state,
                                        onAutomaticChange = {},
                                        onImport = {},
                                        onSend = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
            assertTrue("Section was not laid out", positioned.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val bounds = requireNotNull(section.get())
                assertEquals("Test must exercise a 290dp pane", 290f * DENSITY, bounds.width, 1f)
                assertTrue("Section is too tall: ${sectionHeight.get() / DENSITY}dp", sectionHeight.get() <= 900f * DENSITY)
                val root = findComposeRoot(activity.findViewById(android.R.id.content))
                    ?: error("No Compose root")
                assertFalse("Compose still has pending layout", root.hasPendingMeasureOrLayout)
                val nodes = descendants(root.semanticsOwner.unmergedRootSemanticsNode).toList()
                val localized = requireNotNull(context)
                val expected = listOf(
                    localized.getString(R.string.settings_remote_diagnostics_disclosure),
                    localized.getString(R.string.settings_remote_diagnostics_auto),
                    localized.getString(if (configured) R.string.settings_remote_diagnostics_configured
                        else R.string.settings_remote_diagnostics_unconfigured),
                    localized.getString(requireNotNull(state.remoteDiagnosticsImportStatus)),
                    localized.getString(R.string.settings_remote_diagnostics_queue, diagnostics.count, diagnostics.bytes),
                    localized.getString(R.string.settings_remote_diagnostics_queued),
                    localized.getString(R.string.settings_remote_diagnostics_sending),
                    localized.getString(R.string.settings_remote_diagnostics_retrying),
                    localized.getString(R.string.settings_remote_diagnostics_blocked),
                    localized.getString(R.string.settings_remote_diagnostics_terminal),
                    diagnostics.lastSuccessAtMs?.let {
                        localized.getString(R.string.settings_remote_diagnostics_success,
                            DateFormat.getDateTimeInstance().format(Date(it)))
                    } ?: localized.getString(R.string.settings_remote_diagnostics_never),
                    localized.getString(R.string.settings_remote_diagnostics_expiry),
                )
                expected.forEach { text -> assertReadable(textNode(nodes, text), text, bounds) }
                listOf(R.string.settings_remote_diagnostics_import, R.string.settings_remote_diagnostics_send).forEach { id ->
                    val label = localized.getString(id)
                    // The old row repeats Import as a heading. Select the actual clickable action.
                    val button = nodes.singleOrNull { node ->
                        node.config.contains(SemanticsActions.OnClick) &&
                            descendants(node).any { textValue(it) == label }
                    } ?: error("Expected exactly one clickable '$label' action")
                    assertTrue("Action is disabled: $label", !button.config.contains(SemanticsProperties.Disabled))
                    assertReadable(textNode(descendants(button).toList(), label), label, bounds)
                    assertInside("Action outside section: $label", button.boundsInRoot, bounds)
                }
            }
        }
    }

    private fun assertReadable(node: SemanticsNode, value: String, section: Rect) {
        val results = mutableListOf<TextLayoutResult>()
        assertTrue("No rendered text layout for '$value'",
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) == true)
        val layout = results.single()
        assertEquals("Text was truncated: '$value'", value.length, layout.getLineEnd(layout.lineCount - 1))
        repeat(layout.lineCount) { line ->
            assertFalse("Text was ellipsized: '$value'", layout.isLineEllipsized(line))
            val lineBounds = Rect(
                node.positionInRoot.x + layout.getLineLeft(line),
                node.positionInRoot.y + layout.getLineTop(line),
                node.positionInRoot.x + layout.getLineRight(line),
                node.positionInRoot.y + layout.getLineBottom(line),
            )
            assertInside("Text outside section: '$value'", lineBounds, section)
            assertInside("Text clipped by ancestor: '$value'", lineBounds, node.boundsInRoot)
        }
    }

    private fun assertInside(message: String, inner: Rect, outer: Rect) {
        assertTrue("$message: text=$inner visible=$outer",
            inner.width > 0f && inner.height > 0f && inner.left >= outer.left - 1f &&
                inner.top >= outer.top - 1f && inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f)
    }

    private fun textValue(node: SemanticsNode): String? =
        if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].joinToString("") { it.text }
        else null

    private fun textNode(nodes: List<SemanticsNode>, text: String): SemanticsNode =
        nodes.singleOrNull { textValue(it) == text } ?: error("Expected exactly one rendered '$text'")

    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(descendants(it)) }
    }

    private fun findComposeRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) repeat(view.childCount) { index ->
            findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    companion object {
        private const val DENSITY = 1.625f

        @JvmStatic
        @Parameterized.Parameters(name = "{0}_configured_{1}")
        fun cases(): List<Array<Any>> = listOf("en", "pl", "ru").flatMap { language ->
            listOf(false, true).map { configured -> arrayOf<Any>(language, configured) }
        }
    }
}
