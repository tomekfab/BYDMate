package com.bydmate.app.ui.dashboard

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.nativestack.VehicleKind
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.bydmate.app.data.trips.TripAutoResetMode
import com.bydmate.app.data.trips.TripCounterUi
import com.bydmate.app.ui.theme.BYDMateTheme
import com.bydmate.app.ui.widget.WidgetPreviewActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Exercises production controls using the existing ActivityScenario/semantics test pattern. */
class DashboardInteractionsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val counter = TripCounterUi(40.0, 9.5, 8.0, 1.5, 1.9, 2, 3000, 0)

    @Test fun longPressRequestsResetAndTapStillOpensDetails() {
        val requested = AtomicInteger()
        val opened = AtomicInteger()
        mount {
            TripCounterRow(DashboardUiState(trip1 = counter, trip2 = counter),
                onToggle = opened::set, onRequestReset = requested::set)
        }.use { scenario ->
            act(scenario, R.string.dashboard_trip1_label, longClick = true)
            assertEquals(1, requested.get())
            assertEquals(0, opened.get())
            act(scenario, R.string.dashboard_trip2_label)
            assertEquals(2, opened.get())
        }
    }

    @Test fun visibleResetActionRequestsConfirmation() {
        val requested = AtomicInteger()
        mount {
            TripCounterDialog("TRIP 1", counter, "PLN", TripAutoResetMode.OFF, {},
                onRequestReset = { requested.incrementAndGet() }, onDismiss = {})
        }.use { scenario ->
            act(scenario, R.string.dashboard_trip_reset_action)
            assertEquals(1, requested.get())
        }
    }

    @Test fun cancelDoesNotConfirm() {
        val cancelled = AtomicInteger()
        val confirmed = AtomicInteger()
        mount { TripResetConfirmation(1, { cancelled.incrementAndGet() }, { confirmed.incrementAndGet() }) }
            .use { scenario ->
                act(scenario, R.string.dashboard_trip_reset_cancel)
                assertEquals(1, cancelled.get())
                assertEquals(0, confirmed.get())
            }
    }

    @Test fun confirmInvokesOnlyConfirmOnce() {
        val cancelled = AtomicInteger()
        val confirmed = AtomicInteger()
        mount { TripResetConfirmation(2, { cancelled.incrementAndGet() }, { confirmed.incrementAndGet() }) }
            .use { scenario ->
                act(scenario, R.string.dashboard_trip_reset_action)
                assertEquals(1, confirmed.get())
                assertEquals(0, cancelled.get())
            }
    }

    @Test fun recentEvRowOpensTrips() = assertRecentRowOpensTrips(VehicleKind.EV)

    @Test fun recentHybridRowOpensTrips() = assertRecentRowOpensTrips(VehicleKind.HYBRID)

    @Test fun viewTripsOpensHistoryForEvAndHybrid() {
        listOf(VehicleKind.EV, VehicleKind.HYBRID).forEach { kind ->
            val opened = AtomicInteger()
            mount {
                RecentTripsSection(recentState(kind), { opened.incrementAndGet() }, Modifier.fillMaxSize())
            }.use { scenario ->
                act(scenario, R.string.dashboard_view_trips)
                assertEquals(1, opened.get())
            }
        }
    }

    private fun assertRecentRowOpensTrips(kind: VehicleKind) {
        val opened = AtomicInteger()
        mount {
            RecentTripsSection(recentState(kind), { opened.incrementAndGet() }, Modifier.fillMaxSize())
        }.use { scenario ->
            act(scenario, "12.3")
            assertEquals(1, opened.get())
        }
    }

    private fun recentState(kind: VehicleKind) = DashboardUiState(
        vehicleKind = kind,
        recentTrips = listOf(TripEntity(
            id = 1, startTs = 1_730_000_000_000, endTs = 1_730_000_600_000,
            distanceKm = 12.3, kwhConsumed = 2.1, kwhPer100km = 17.1, cost = 4.0,
            fuelLiters = if (kind == VehicleKind.HYBRID) 0.5 else null,
            hevKm = if (kind == VehicleKind.HYBRID) 6.0 else null,
        )),
    )

    private fun mount(content: @Composable () -> Unit): ActivityScenario<WidgetPreviewActivity> =
        ActivityScenario.launch(WidgetPreviewActivity::class.java).also { scenario ->
            scenario.onActivity { it.setContent { BYDMateTheme { content() } } }
            instrumentation.waitForIdleSync()
        }

    private fun act(scenario: ActivityScenario<WidgetPreviewActivity>, label: Int, longClick: Boolean = false) {
        val context = instrumentation.targetContext
        act(scenario, context.getString(label), longClick)
    }

    private fun act(scenario: ActivityScenario<WidgetPreviewActivity>, text: String, longClick: Boolean = false) {
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            // Dialogs have a separate window; inspect all attached roots, as well as content.
            val roots = android.view.inspector.WindowInspector.getGlobalWindowViews().mapNotNull(::findComposeRoot)
            val nodes = roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode).toList() }
            val action = if (longClick) SemanticsActions.OnLongClick else SemanticsActions.OnClick
            val target = nodes.filter { node -> node.config.contains(action) && descendants(node).any {
                it.config.contains(SemanticsProperties.Text) &&
                    it.config[SemanticsProperties.Text].joinToString("") { value -> value.text } == text
            } }.minByOrNull { descendants(it).count() } ?: error("Expected one production action '$text' (long=$longClick)")
            assertTrue("Action rejected '$text'", target.config[action].action?.invoke() == true)
        }
        instrumentation.waitForIdleSync()
    }

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
}
