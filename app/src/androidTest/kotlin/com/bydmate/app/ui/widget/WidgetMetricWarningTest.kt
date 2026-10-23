package com.bydmate.app.ui.widget

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.R
import com.bydmate.app.domain.calculator.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Checks the actual warning icon and its placement beside the metric it describes. */
@RunWith(Parameterized::class)
class WidgetMetricWarningTest(
    private val soc: Int?,
    private val voltage: Double?,
    private val expectedSocWarning: Boolean,
    private val expectedVoltageWarning: Boolean,
) {
    @Test
    fun warningBelongsToFailingMetric() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(WidgetPreviewActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        FloatingWidgetView(
                            soc = soc, rangeKm = 318.0, consumption = 16.2, trend = Trend.FLAT,
                            sessionStartedAt = null, tripDistanceKm = 18.4,
                            insideTemp = 22, batTemp = 25, voltage12v = voltage, alpha = 1f,
                        )
                    }
                }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = checkNotNull(findComposeRoot(activity.findViewById(android.R.id.content)))
                val nodes = descendants(root.semanticsOwner.unmergedRootSemanticsNode).toList()
                val descriptions = setOf(
                    activity.getString(R.string.widget_cd_warning),
                    activity.getString(R.string.widget_cd_critical),
                )
                val warnings = nodes.filter { node ->
                    node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                        .any { it in descriptions }
                }
                assertEquals("Unexpected warning count for soc=$soc voltage=$voltage",
                    listOf(expectedSocWarning, expectedVoltageWarning).count { it }, warnings.size)
                fun assertBeside(value: String, expected: Boolean) {
                    val metric = nodes.single { node ->
                        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                            .joinToString("") { it.text } == value
                    }.boundsInRoot
                    val beside = warnings.any { warning ->
                        val bounds = warning.boundsInRoot
                        bounds.left >= metric.right && bounds.left - metric.right < 24 * activity.resources.displayMetrics.density &&
                            bounds.top < metric.bottom && bounds.bottom > metric.top
                    }
                    assertEquals("Warning misplaced for '$value' (soc=$soc voltage=$voltage)", expected, beside)
                }
                if (soc != null) assertBeside("$soc%", expectedSocWarning)
                if (voltage != null) {
                    assertBeside(activity.getString(R.string.widget_voltage_value, voltage), expectedVoltageWarning)
                }
                assertTrue("Layout must settle before inspecting warnings", !root.hasPendingMeasureOrLayout)
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

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "soc={0}_voltage={1}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf(80, 11.8, false, true),
            arrayOf(80, 12.2, false, true),
            arrayOf(20, 13.5, true, false),
            arrayOf(10, 11.8, true, true),
            arrayOf(80, 13.5, false, false),
            arrayOf(null, null, false, false),
        )
    }
}
