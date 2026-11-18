package com.bydmate.app.ui

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.ui.components.SocGauge
import com.bydmate.app.ui.settings.SettingChipRow
import com.bydmate.app.ui.theme.BYDMateTheme
import com.bydmate.app.ui.trips.BarChart
import com.bydmate.app.ui.trips.ChartBar
import com.bydmate.app.ui.trips.ChartMetric
import com.bydmate.app.ui.widget.WidgetPreviewActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real production text layouts, with no vehicle services or history mutations. */
class InterfaceReadabilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun chipRowLabelsAndChoicesFitAtNormalAndLargestAppText() {
        listOf(1f, 1.3f).forEach { scale ->
            render(290f, scale, content = {
                SettingChipRow("Text size", "Applies to app screens and dialogs.",
                    listOf("Normal", "Large", "Extra large"), 0, {})
            }) { nodes, bounds ->
                val title = textNode(nodes, "Text size")
                assertTrue("Setting label collapsed at scale $scale", title.boundsInRoot.width > 0f)
                assertTrue("Chip row grows to an unusable height", bounds.height < 160f)
                listOf("Text size", "Normal", "Large", "Extra large").forEach {
                    assertReadable(textNode(nodes, it), bounds)
                }
            }
        }
    }

    @Test fun selectedChartValueWithCompleteUnitFitsNarrowCanvasAtLargeText() {
        render(200f, 1.3f, content = {
            BarChart(listOf(ChartBar("18.11", 1234.5, 42)), ChartMetric.PER_100,
                0, "PLN", {}, Modifier.height(240.dp).width(200.dp))
        }) { nodes, bounds ->
            assertReadable(textNode(nodes, "1234.5 kWh/100 km"), bounds)
        }
    }

    @Test fun unavailableChargeIsDistinctFromAnActualZeroReading() {
        render(200f, 1f, { SocGauge(null) }) { nodes, _ ->
            assertTrue("Unknown charge must show a dash", nodes.any { text(it) == "—" })
            assertFalse("Unknown charge is not a depleted battery", nodes.any { text(it) == "0" })
        }
        render(200f, 1f, { SocGauge(0) }) { nodes, _ ->
            assertTrue("Actual zero charge must still show zero", nodes.any { text(it) == "0" })
        }
    }

    private fun render(width: Float, scale: Float, content: @Composable () -> Unit,
        check: (List<SemanticsNode>, Rect) -> Unit) {
        ActivityScenario.launch(WidgetPreviewActivity::class.java).use { scenario ->
            val positioned = CountDownLatch(1)
            val bounds = AtomicReference<Rect>()
            scenario.onActivity { activity ->
                val config = Configuration(activity.resources.configuration).apply {
                    setLocale(Locale.ENGLISH)
                    fontScale = 1f
                }
                val context = activity.createConfigurationContext(config)
                activity.setContent {
                    CompositionLocalProvider(LocalContext provides context,
                        LocalConfiguration provides config, LocalDensity provides Density(0.5f, 1f)) {
                        BYDMateTheme(fontScale = scale) {
                            Box(Modifier.fillMaxSize()) {
                                Column(Modifier.width(width.dp).verticalScroll(rememberScrollState())) {
                                    Column(Modifier.onGloballyPositioned {
                                        bounds.set(it.boundsInRoot()); positioned.countDown()
                                    }) { content() }
                                }
                            }
                        }
                    }
                }
            }
            assertTrue("Production component did not lay out", positioned.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = findRoot(activity.findViewById(android.R.id.content)) ?: error("No Compose root")
                assertFalse(root.hasPendingMeasureOrLayout)
                check(descendants(root.semanticsOwner.unmergedRootSemanticsNode).toList(), bounds.get())
            }
        }
    }

    private fun text(node: SemanticsNode): String? =
        if (node.config.contains(SemanticsProperties.Text))
            node.config[SemanticsProperties.Text].joinToString("") { it.text } else null
    private fun textNode(nodes: List<SemanticsNode>, value: String) =
        nodes.firstOrNull { text(it) == value } ?: error("Production text missing: $value")
    private fun assertReadable(node: SemanticsNode, bounds: Rect) {
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) == true)
        val layout = results.single()
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
        repeat(layout.lineCount) { line ->
            assertFalse(layout.isLineEllipsized(line))
            assertTrue("Text left edge clipped", node.positionInRoot.x + layout.getLineLeft(line) >= bounds.left - 1)
            assertTrue("Text right edge clipped", node.positionInRoot.x + layout.getLineRight(line) <= bounds.right + 1)
        }
        assertTrue("Text extends below component", node.boundsInRoot.bottom <= bounds.bottom + 1)
    }
    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node); node.children.forEach { yieldAll(descendants(it)) }
    }
    private fun findRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) repeat(view.childCount) { i -> findRoot(view.getChildAt(i))?.let { return it } }
        return null
    }
}
