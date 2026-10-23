package com.bydmate.app.ui.widget

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
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
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.R
import com.bydmate.app.domain.calculator.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Measures the production composable, not a duplicate of its row/font calculations.
 * TextLayoutResult exposes full line geometry even when semantics bounds are clipped.
 * No screenshot, capture marker, vehicle service or voice controller is required.
 */
@RunWith(Parameterized::class)
class WidgetDataTextLayoutTest(private val language: String, private val fontScale: Float) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun tripDistanceIsCompleteOnOneLine() = withLayout { snapshot ->
        val distance = snapshot.text(snapshot.context.getString(R.string.widget_trip_km_value, 18.4))
        assertEquals("Trip distance wrapped: ${distance.value}", 1, distance.layout.lineCount)
        assertReadable(distance, snapshot.widget)
    }

    @Test
    fun serviceValuesAreFullyInsideWidget() = withLayout { snapshot ->
        listOf("22°", "25°", snapshot.context.getString(R.string.widget_voltage_value, 12.4))
            .forEach { value -> assertReadable(snapshot.text(value), snapshot.widget) }
    }

    @Test
    fun allVehicleValuesRemainVisibleWithoutOverlapInFixedFootprint() = withLayout { snapshot ->
        // Acceptance footprint is independent of WidgetStyle so changing that constant cannot pass.
        assertEquals("Widget width changed", 260f * snapshot.density, snapshot.widget.width, 1f)
        assertEquals("Widget height changed", 108f * snapshot.density, snapshot.widget.height, 1f)
        val context = snapshot.context
        val expected = listOf(
            context.getString(R.string.widget_duration_minutes, 24),
            context.getString(R.string.widget_trip_km_value, 18.4),
            context.getString(R.string.widget_km_per_percent, 318.0 / 74),
            "74%", "~318", context.getString(R.string.widget_unit_km),
            String.format("%.1f", 16.2), "22°", "25°",
            context.getString(R.string.widget_voltage_value, 12.4),
        )
        assertEquals("A vehicle value was removed or duplicated", expected.sorted(), snapshot.texts.map { it.value }.sorted())
        val texts = expected.map(snapshot::text)
        texts.forEach { assertReadable(it, snapshot.widget) }
        texts.forEachIndexed { index, first ->
            texts.drop(index + 1).forEach { second ->
                first.lineBounds().forEach { a -> second.lineBounds().forEach { b ->
                    assertFalse("Text overlaps: '${first.value}' $a and '${second.value}' $b", a.overlaps(b))
                } }
            }
        }
    }

    private fun assertReadable(text: RenderedText, widget: Rect) {
        val layout = text.layout
        // The semantics result can retain a paragraph's wider measurement constraints.
        // hasVisualOverflow then reports width overflow for a fully visible short label.
        // Check the actual line extents against the measured, clipped node bounds instead.
        assertEquals("Text was truncated: '${text.value}'", text.value.length, layout.getLineEnd(layout.lineCount - 1))
        repeat(layout.lineCount) { line ->
            assertFalse("Text was ellipsized: '${text.value}'", layout.isLineEllipsized(line))
        }
        text.lineBounds().forEach { bounds ->
            assertInside("'${text.value}' line outside widget", bounds, widget)
            assertInside("'${text.value}' line clipped by an ancestor", bounds, text.visibleBounds)
        }
    }

    private fun assertInside(message: String, inner: Rect, outer: Rect) {
        // One physical pixel permits rounding; it cannot conceal a clipped line.
        assertTrue("$message: line=$inner visible=$outer",
            inner.width > 0f && inner.height > 0f &&
                inner.left >= outer.left - 1f && inner.top >= outer.top - 1f &&
                inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f)
    }

    private fun withLayout(check: (Snapshot) -> Unit) {
        ActivityScenario.launch(WidgetPreviewActivity::class.java).use { scenario ->
            val positioned = CountDownLatch(1)
            val widget = AtomicReference<Rect>()
            var context: Context? = null
            var density = 0f
            scenario.onActivity { activity ->
                val configuration = Configuration(activity.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                    this.fontScale = this@WidgetDataTextLayoutTest.fontScale
                }
                val localized = activity.createConfigurationContext(configuration)
                context = localized
                density = localized.resources.displayMetrics.density
                activity.setContent {
                    CompositionLocalProvider(
                        LocalContext provides localized,
                        LocalConfiguration provides configuration,
                        LocalDensity provides Density(density, fontScale),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(Modifier.onGloballyPositioned {
                                widget.set(it.boundsInRoot())
                                positioned.countDown()
                            }) {
                                FloatingWidgetView(
                                    soc = 74, rangeKm = 318.0, consumption = 16.2, trend = Trend.FLAT,
                                    sessionStartedAt = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(24),
                                    tripDistanceKm = 18.4, insideTemp = 22, batTemp = 25, voltage12v = 12.4,
                                    alpha = 1f,
                                )
                            }
                        }
                    }
                }
            }
            assertTrue("Widget was not laid out", positioned.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = findComposeRoot(activity.findViewById(android.R.id.content))
                    ?: error("No Compose root in WidgetPreviewActivity")
                assertFalse("Compose still has pending layout", root.hasPendingMeasureOrLayout)
                val texts = descendants(root.semanticsOwner.unmergedRootSemanticsNode).mapNotNull { node ->
                    if (!node.config.contains(SemanticsProperties.Text)) return@mapNotNull null
                    val results = mutableListOf<TextLayoutResult>()
                    assertTrue("No text layout action", node.config.contains(SemanticsActions.GetTextLayoutResult))
                    assertTrue("No rendered text layout", node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) == true)
                    assertEquals("Expected one layout per text node", 1, results.size)
                    RenderedText(
                        node.config[SemanticsProperties.Text].joinToString("") { it.text },
                        results.single(), node.positionInRoot.x, node.positionInRoot.y, node.boundsInRoot,
                    )
                }.toList()
                assertTrue("No production text nodes found", texts.isNotEmpty())
                texts.forEach { assertEquals("System font scale was overridden", fontScale, it.layout.layoutInput.density.fontScale, 0.001f) }
                check(Snapshot(requireNotNull(context), density, requireNotNull(widget.get()), texts))
            }
        }
    }

    private fun findComposeRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) repeat(view.childCount) { index ->
            findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(descendants(it)) }
    }

    private data class Snapshot(val context: Context, val density: Float, val widget: Rect, val texts: List<RenderedText>) {
        fun text(value: String): RenderedText = texts.singleOrNull { it.value == value }
            ?: error("Expected exactly one '$value'; found ${texts.map { it.value }}")
    }

    private data class RenderedText(
        val value: String, val layout: TextLayoutResult, val x: Float, val y: Float, val visibleBounds: Rect,
    ) {
        fun lineBounds(): List<Rect> = (0 until layout.lineCount).map { line ->
            Rect(x + layout.getLineLeft(line), y + layout.getLineTop(line),
                x + layout.getLineRight(line), y + layout.getLineBottom(line))
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}_font_{1}")
        fun cases(): List<Array<Any>> = listOf("en", "pl", "ru").flatMap { language ->
            listOf(1f, 1.3f).map { fontScale -> arrayOf<Any>(language, fontScale) }
        }
    }
}
