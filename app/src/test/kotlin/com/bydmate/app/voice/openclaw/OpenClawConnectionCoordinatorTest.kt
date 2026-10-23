package com.bydmate.app.voice.openclaw

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.automation.NetworkAvailableMonitor
import com.bydmate.app.service.TailscaleStartup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@Suppress("LargeClass") // Recovery, lifecycle and diagnostic scenarios share the scripted assistant rig.
class OpenClawConnectionCoordinatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val endpoint = "wss://gateway.example.ts.net/"

    /**
     * A stand-in for the assistant that mirrors its real status events explicitly: it publishes
     * Connecting when a connect starts, every link change the script performs, and (like the
     * assistant's refresh) the client's actual link when connect returns. The returned link and
     * the client's actual link are separate, so a script can model a drop that outlives a Ready.
     */
    private class Rig(
        private val scope: CoroutineScope,
        private val automatic: suspend Rig.() -> AutomaticReconnectResult,
        private val manual: suspend Rig.() -> OpenClawLinkState = { settle(OpenClawLinkState.Ready) },
        private val tailscaleResult: TailscaleStartup.ConnectionRequestResult =
            TailscaleStartup.ConnectionRequestResult(receiverSupported = true, sent = true),
        statusSource: (StateFlow<OpenClawStatus>) -> StateFlow<OpenClawStatus> = { it },
    ) {
        val status = MutableStateFlow(OpenClawStatus(true, "wss://gateway.example.ts.net/", true, OpenClawLinkState.Disconnected))
        val edges = MutableStateFlow(0L)
        val delays = mutableListOf<Long>()
        val events = mutableListOf<String>()
        var tailscaleCalls = 0
        var automaticCalls = 0
        var manualCalls = 0
        var clientLink: OpenClawLinkState = OpenClawLinkState.Disconnected
        val traces = mutableListOf<Pair<String, Map<String, String>>>()
        /** The [events] seen when each trace was recorded, to check its order. */
        val eventsAtTrace = mutableListOf<List<String>>()
        var traceSinkThrows = false
        var tailscaleError: Throwable? = null

        /** Decides, per 1-based call, whether admission skips before any hook or connect. */
        var admission: (Int) -> AutomaticReconnectSkipReason? = { null }

        /** Every call skips for [reason], and the status shows what the assistant saw (terminal reasons). */
        fun skipAlways(reason: AutomaticReconnectSkipReason) {
            admission = { reason }
            val base = OpenClawStatus(true, "wss://gateway.example.ts.net/", true, OpenClawLinkState.Disconnected)
            when (reason) {
                AutomaticReconnectSkipReason.Disabled -> base.copy(enabled = false)
                AutomaticReconnectSkipReason.InvalidEndpoint -> base.copy(endpointValid = false)
                AutomaticReconnectSkipReason.Unpaired -> base.copy(link = OpenClawLinkState.Unpaired)
                AutomaticReconnectSkipReason.AwaitingApproval -> base.copy(link = OpenClawLinkState.AwaitingApproval("request"))
                AutomaticReconnectSkipReason.Revoked -> base.copy(link = OpenClawLinkState.Revoked)
                AutomaticReconnectSkipReason.IdentityChanged -> null
            }?.let { emitStatus(it) }
        }

        /** Runs before admission, with the 1-based call index. It models waiting for the assistant lock. */
        var beforeAdmission: suspend Rig.(Int) -> Unit = {}

        val coordinator = OpenClawConnectionCoordinator(
            assistantStatus = statusSource(status),
            reconnectIfEligible = { onAdmitted -> fakeAutomatic(onAdmitted) },
            reconnectManually = { fakeManual() },
            requestTailscale = {
                events += "hint"
                tailscaleCalls++
                tailscaleError?.let { throw it }
                tailscaleResult
            },
            retryDelay = { ms -> delays += ms; delay(ms) },
            diagnostic = { what, fields ->
                if (traceSinkThrows) error("sink down")
                eventsAtTrace += events.toList()
                traces += what to fields.toMap()
            },
        )

        /** The recorded decisions only, without the event name. */
        val decisions: List<Map<String, String>> get() = traces.map { it.second }

        fun start(context: Context, on: CoroutineScope = scope) = coordinator.start(context, on, edges)

        fun emit(link: OpenClawLinkState) {
            clientLink = link
            status.value = status.value.copy(link = link)
        }

        fun emitStatus(next: OpenClawStatus) {
            clientLink = next.link
            status.value = next
        }

        fun settle(link: OpenClawLinkState): OpenClawLinkState {
            emit(link)
            return link
        }

        fun attempt(link: OpenClawLinkState): AutomaticReconnectResult {
            emit(link)
            return AutomaticReconnectResult.Attempted(link)
        }

        private suspend fun fakeAutomatic(onAdmitted: () -> Unit): AutomaticReconnectResult {
            val call = ++automaticCalls
            beforeAdmission(call)
            admission(call)?.let {
                events += "skipped"
                return AutomaticReconnectResult.Skipped(it)
            }
            events += "admitted"
            onAdmitted()
            events += "connect"
            emit(OpenClawLinkState.Connecting)
            val result = automatic()
            if (result is AutomaticReconnectResult.Attempted) status.value = status.value.copy(link = clientLink)
            return result
        }

        private suspend fun fakeManual(): OpenClawLinkState {
            manualCalls++
            events += "manual"
            emit(OpenClawLinkState.Connecting)
            val link = manual()
            status.value = status.value.copy(link = clientLink)
            return link
        }
    }

    private fun TestScope.session() = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    private val failNetwork = OpenClawLinkState.Failed(OpenClawFailure.NetworkUnavailable)


    @Test fun `retries eligible failures at 5 10 20 40 then 60 seconds without repeating Tailscale`() = runTest {
        val rig = Rig(backgroundScope, automatic = { attempt(failNetwork) })

        rig.start(context)
        runCurrent()
        for (expected in listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L)) {
            assertEquals(expected, rig.delays.last())
            advanceTimeBy(expected)
            runCurrent()
        }

        assertEquals(7, rig.automaticCalls)
        assertEquals(1, rig.tailscaleCalls)
    }

    @Test fun `Ready resets the retry delay and a wake sends the next Tailscale hint`() = runTest {
        val outcomes = ArrayDeque(
            listOf(OpenClawLinkState.Failed(OpenClawFailure.Timeout), OpenClawLinkState.Ready, failNetwork),
        )
        val rig = Rig(backgroundScope, automatic = { attempt(outcomes.removeFirst()) })

        rig.start(context)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        rig.coordinator.onWake()
        runCurrent()

        assertEquals(listOf(5_000L, 5_000L), rig.delays)
        assertEquals(2, rig.tailscaleCalls)
    }

    @Test fun `many wake and network signals during one connect do not start another connect`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, automatic = {
            entered.complete(Unit)
            release.await()
            attempt(OpenClawLinkState.Ready)
        })

        rig.start(context)
        runCurrent()
        assertTrue(entered.isCompleted)
        repeat(5) {
            rig.coordinator.onWake()
            rig.edges.value = it + 1L
        }
        runCurrent()
        release.complete(Unit)
        runCurrent()

        assertEquals(1, rig.automaticCalls)
    }

    @Test fun `missing Tailscale receiver remains diagnostic and still reconnects`() = runTest {
        val rig = Rig(
            backgroundScope,
            automatic = { attempt(OpenClawLinkState.Ready) },
            tailscaleResult = TailscaleStartup.ConnectionRequestResult(receiverSupported = false, sent = true),
        )

        rig.start(context)
        runCurrent()

        assertEquals(1, rig.automaticCalls)
        assertEquals(TailscaleHintDiagnostic(receiverSupported = false, sent = true), rig.coordinator.tailscaleHint.value)
        assertEquals(OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)
    }

    @Test fun `an admitted attempt sends one hint after admission and before connect`() = runTest {
        var seenWhileConnecting: OpenClawRecoveryState? = null
        val rig = Rig(backgroundScope, automatic = {
            seenWhileConnecting = coordinator.recovery.value
            attempt(OpenClawLinkState.Ready)
        })

        rig.start(context)
        runCurrent()

        assertEquals(listOf("admitted", "hint", "connect"), rig.events.take(3))
        assertEquals(1, rig.tailscaleCalls)
        val diagnostic = TailscaleHintDiagnostic(receiverSupported = true, sent = true)
        assertEquals(OpenClawRecoveryState.Connecting(diagnostic), seenWhileConnecting)
    }

    @Test fun `skipped admissions send no hint for start wake or network`() = runTest {
        val reasons = listOf(
            AutomaticReconnectSkipReason.Disabled,
            AutomaticReconnectSkipReason.InvalidEndpoint,
            AutomaticReconnectSkipReason.Unpaired,
            AutomaticReconnectSkipReason.AwaitingApproval,
            AutomaticReconnectSkipReason.Revoked,
        )
        for (reason in reasons) {
            val rig = Rig(backgroundScope, automatic = { error("a skipped admission must not connect") })
            rig.skipAlways(reason)

            rig.start(context)
            runCurrent()
            rig.coordinator.onWake()
            runCurrent()
            rig.edges.value = 1L
            runCurrent()

            assertEquals(reason.name, 0, rig.tailscaleCalls)
            assertNull(reason.name, rig.coordinator.tailscaleHint.value)
            assertTrue(reason.name, rig.events.none { it == "hint" || it == "connect" })
            assertEquals(reason.name, OpenClawRecoveryState.Stopped(reason), rig.coordinator.recovery.value)
            rig.coordinator.stop()
        }
    }

    @Test fun `TLS and challenge failures continue at the sixty second cap`() = runTest {
        for (failure in listOf(OpenClawFailure.TlsRejected, OpenClawFailure.ChallengeFailed)) {
            val rig = Rig(backgroundScope, automatic = { attempt(OpenClawLinkState.Failed(failure)) })
            rig.start(context)
            runCurrent()
            repeat(4) { index ->
                advanceTimeBy(listOf(5_000L, 10_000L, 20_000L, 40_000L)[index])
                runCurrent()
            }
            assertEquals(60_000L, rig.delays.last())
            assertEquals(OpenClawRecoveryState.Retrying(60_000L, failure), rig.coordinator.recovery.value)
            rig.coordinator.stop()
        }
    }

    @Test fun `disabled approval and revoked states cancel automatic work`() = runTest {
        val cases = listOf(
            OpenClawStatus(false, endpoint, true, OpenClawLinkState.Disconnected) to AutomaticReconnectSkipReason.Disabled,
            OpenClawStatus(true, endpoint, true, OpenClawLinkState.AwaitingApproval("request")) to AutomaticReconnectSkipReason.AwaitingApproval,
            OpenClawStatus(true, endpoint, true, OpenClawLinkState.Revoked) to AutomaticReconnectSkipReason.Revoked,
        )
        for ((status, reason) in cases) {
            val rig = Rig(backgroundScope, automatic = { attempt(failNetwork) })
            rig.start(context)
            runCurrent()
            rig.status.value = status
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(OpenClawRecoveryState.Stopped(reason), rig.coordinator.recovery.value)
            assertEquals(1, rig.automaticCalls)
            rig.coordinator.stop()
        }
    }

    @Test fun `stop cancels future work without changing the existing pairing`() = runTest {
        val rig = Rig(backgroundScope, automatic = { attempt(failNetwork) })
        rig.start(context)
        runCurrent()
        rig.coordinator.stop()
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(1, rig.automaticCalls)
        assertEquals(OpenClawRecoveryState.Stopped(null), rig.coordinator.recovery.value)
    }

    @Test fun `manual reconnect queues with an active session and falls back directly while inactive`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rig = Rig(
            backgroundScope,
            automatic = {
                entered.complete(Unit)
                release.await()
                attempt(OpenClawLinkState.Ready)
            },
        )
        rig.start(context)
        runCurrent()
        val manual = async { rig.coordinator.requestManualReconnect() }
        release.complete(Unit)
        runCurrent()
        manual.await()
        assertEquals(1, rig.manualCalls)

        rig.coordinator.stop()
        rig.coordinator.requestManualReconnect()
        assertEquals(2, rig.manualCalls)
    }

    // --- reconciliation with the latest authoritative status ---------------------------------------

    @Test fun `a returned Ready does not hide a link that dropped before completion`() = runTest {
        for (observedBetween in listOf(false, true)) {
            val rig = Rig(backgroundScope, automatic = {
                if (automaticCalls == 1) {
                    emit(OpenClawLinkState.Ready)
                    if (observedBetween) yield() // the collector sees Ready, then the drop
                    emit(OpenClawLinkState.Disconnected)
                    AutomaticReconnectResult.Attempted(OpenClawLinkState.Ready)
                } else {
                    attempt(OpenClawLinkState.Ready)
                }
            })

            rig.start(context)
            runCurrent()

            val label = "observedBetween=$observedBetween"
            assertEquals(label, OpenClawRecoveryState.Retrying(5_000L, null), rig.coordinator.recovery.value)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(label, 2, rig.automaticCalls)
            assertEquals(label, OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)
            assertEquals("the drop retry is a timer, so no new hint: $label", 1, rig.tailscaleCalls)
            rig.coordinator.stop()
        }
    }

    @Test fun `a manual Ready that dropped before completion also retries`() = runTest {
        val rig = Rig(
            backgroundScope,
            automatic = { attempt(OpenClawLinkState.Ready) },
            manual = {
                emit(OpenClawLinkState.Ready)
                yield()
                emit(OpenClawLinkState.Disconnected)
                OpenClawLinkState.Ready
            },
        )
        rig.start(context)
        runCurrent()

        rig.coordinator.requestManualReconnect()
        runCurrent()

        assertEquals(OpenClawRecoveryState.Retrying(5_000L, null), rig.coordinator.recovery.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, rig.automaticCalls)
        assertEquals(OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)
    }

    @Test fun `a drop after completion retries once without a new hint`() = runTest {
        val rig = Rig(backgroundScope, automatic = { attempt(OpenClawLinkState.Ready) })
        rig.start(context)
        runCurrent()
        assertEquals(OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)

        rig.emit(OpenClawLinkState.Disconnected)
        runCurrent()

        assertEquals(2, rig.automaticCalls)
        assertEquals(1, rig.tailscaleCalls)
    }

    @Test fun `own Connecting and Failed emissions leave the chosen backoff intact`() = runTest {
        val rig = Rig(backgroundScope, automatic = {
            val failure = OpenClawLinkState.Failed(OpenClawFailure.Timeout)
            emit(failure)
            yield() // the collector observes the failure while the attempt is still running
            AutomaticReconnectResult.Attempted(failure)
        })

        rig.start(context)
        runCurrent()
        assertEquals(listOf(5_000L), rig.delays)
        assertEquals(1, rig.automaticCalls)

        // Late events from the same attempt while its retry is pending change nothing.
        rig.emit(OpenClawLinkState.Connecting)
        rig.emit(OpenClawLinkState.Failed(OpenClawFailure.Timeout))
        runCurrent()
        assertEquals(1, rig.automaticCalls)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, rig.automaticCalls)
        assertEquals(listOf(5_000L, 10_000L), rig.delays)
    }

    @Test fun `terminal status wins over an old returned Ready and nothing retries`() = runTest {
        val cases = listOf(
            OpenClawStatus(false, endpoint, true, OpenClawLinkState.Disconnected) to AutomaticReconnectSkipReason.Disabled,
            OpenClawStatus(true, endpoint, false, OpenClawLinkState.Disconnected) to AutomaticReconnectSkipReason.InvalidEndpoint,
            OpenClawStatus(true, endpoint, true, OpenClawLinkState.Unpaired) to AutomaticReconnectSkipReason.Unpaired,
            OpenClawStatus(true, endpoint, true, OpenClawLinkState.AwaitingApproval("request")) to AutomaticReconnectSkipReason.AwaitingApproval,
            OpenClawStatus(true, endpoint, true, OpenClawLinkState.Revoked) to AutomaticReconnectSkipReason.Revoked,
        )
        for ((terminal, reason) in cases) {
            for (manual in listOf(false, true)) {
                val label = "$reason manual=$manual"
                // The attempt cannot be cancelled in time: it still returns an old Ready after the status went terminal.
                val lateReady: suspend Rig.() -> Unit = {
                    emitStatus(terminal)
                    withContext(NonCancellable) { yield() }
                }
                val rig = Rig(
                    backgroundScope,
                    automatic = { if (manual) attempt(failNetwork) else { lateReady(); AutomaticReconnectResult.Attempted(OpenClawLinkState.Ready) } },
                    manual = { lateReady(); OpenClawLinkState.Ready },
                )
                rig.start(context)
                runCurrent()
                if (manual) {
                    rig.coordinator.requestManualReconnect()
                    runCurrent()
                }
                advanceTimeBy(120_000)
                runCurrent()

                assertEquals(label, OpenClawRecoveryState.Stopped(reason), rig.coordinator.recovery.value)
                assertEquals(label, 1, rig.automaticCalls)
                rig.coordinator.stop()
            }
        }
    }

    @Test fun `a failed manual attempt with a terminal status does not retry`() = runTest {
        val rig = Rig(
            backgroundScope,
            automatic = { attempt(OpenClawLinkState.Ready) },
            manual = {
                emitStatus(OpenClawStatus(true, endpoint, true, OpenClawLinkState.Revoked))
                withContext(NonCancellable) { yield() }
                OpenClawLinkState.Failed(OpenClawFailure.AuthRejected)
            },
        )
        rig.start(context)
        runCurrent()

        rig.coordinator.requestManualReconnect()
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(OpenClawRecoveryState.Stopped(AutomaticReconnectSkipReason.Revoked), rig.coordinator.recovery.value)
        assertEquals(1, rig.automaticCalls)
        assertEquals(1, rig.manualCalls)
    }

    @Test fun `a stale skip of any reason re-evaluates once without a hint when the latest status is eligible`() = runTest {
        for (reason in AutomaticReconnectSkipReason.values()) {
            val rig = Rig(backgroundScope, automatic = { attempt(OpenClawLinkState.Ready) })
            rig.admission = { call -> if (call == 1) reason else null }

            rig.start(context)
            runCurrent()

            assertEquals(reason.name, 2, rig.automaticCalls)
            assertEquals(reason.name, OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)
            assertEquals("a skipped admission and its re-evaluation send no hint: $reason", 0, rig.tailscaleCalls)
            rig.coordinator.stop()
        }
    }

    @Test fun `disabled then enabled before the attempt completes is not a permanent stop`() = runTest {
        val rig = Rig(backgroundScope, automatic = { attempt(OpenClawLinkState.Ready) })
        rig.admission = { call -> if (call == 1) AutomaticReconnectSkipReason.Disabled else null }
        rig.beforeAdmission = { call ->
            if (call == 1) {
                // The collector sees only the final, eligible status while the attempt is still running.
                emitStatus(status.value.copy(enabled = false))
                emitStatus(status.value.copy(enabled = true))
            }
        }

        rig.start(context)
        runCurrent()

        assertEquals(2, rig.automaticCalls)
        assertEquals(OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)
        assertEquals(0, rig.tailscaleCalls)
    }

    @Test fun `a terminal latest status wins over any stale skip`() = runTest {
        for (reason in AutomaticReconnectSkipReason.values()) {
            val rig = Rig(backgroundScope, automatic = { error("a skipped admission must not connect") })
            rig.admission = { reason }
            rig.beforeAdmission = { emitStatus(status.value.copy(link = OpenClawLinkState.Revoked)) }

            rig.start(context)
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()

            assertEquals(reason.name, OpenClawRecoveryState.Stopped(AutomaticReconnectSkipReason.Revoked), rig.coordinator.recovery.value)
            assertEquals(reason.name, 1, rig.automaticCalls)
            assertEquals(reason.name, 0, rig.tailscaleCalls)
            rig.coordinator.stop()
        }
    }

    @Test fun `persistent stale skips re-evaluate once then follow the capped timer without a hint`() = runTest {
        val rig = Rig(backgroundScope, automatic = { error("a skipped admission must not connect") })
        rig.admission = { AutomaticReconnectSkipReason.Disabled } // the status stays eligible

        rig.start(context)
        runCurrent()
        assertEquals(2, rig.automaticCalls)
        assertEquals(OpenClawRecoveryState.Retrying(5_000L, null), rig.coordinator.recovery.value)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, rig.automaticCalls)
        assertEquals(listOf(5_000L, 10_000L), rig.delays)
        assertEquals(0, rig.tailscaleCalls)
        assertTrue(rig.events.none { it == "hint" || it == "connect" })
    }

    @Test fun `a stale generation skip while still eligible re-evaluates instead of stopping`() = runTest {
        val rig = Rig(backgroundScope, automatic = {
            if (automaticCalls == 1) {
                emit(OpenClawLinkState.Disconnected) // the skip leaves the client's own link as it was
                AutomaticReconnectResult.Skipped(AutomaticReconnectSkipReason.IdentityChanged)
            } else {
                attempt(OpenClawLinkState.Ready)
            }
        })

        rig.start(context)
        runCurrent()

        assertEquals(2, rig.automaticCalls)
        assertEquals(OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)
        assertEquals("a re-evaluation is not a startup event", 1, rig.tailscaleCalls)
    }

    @Test fun `a repeated stale generation skip falls back to the backoff timer`() = runTest {
        val rig = Rig(backgroundScope, automatic = {
            emit(OpenClawLinkState.Disconnected)
            AutomaticReconnectResult.Skipped(AutomaticReconnectSkipReason.IdentityChanged)
        })

        rig.start(context)
        runCurrent()

        assertEquals(2, rig.automaticCalls)
        assertEquals(OpenClawRecoveryState.Retrying(5_000L, null), rig.coordinator.recovery.value)
    }

    // --- repeated sessions --------------------------------------------------------------------------

    @Test fun `a result from a stopped session cannot change the next session`() = runTest {
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, automatic = {
            if (automaticCalls == 1) {
                withContext(NonCancellable) { gate1.await() }
                AutomaticReconnectResult.Attempted(OpenClawLinkState.Ready)
            } else {
                gate2.await()
                attempt(failNetwork)
            }
        })
        val diagnostic = TailscaleHintDiagnostic(receiverSupported = true, sent = true)

        rig.start(context, session())
        runCurrent()
        rig.coordinator.stop()
        rig.start(context, session())
        runCurrent()
        assertEquals(OpenClawRecoveryState.Connecting(diagnostic), rig.coordinator.recovery.value)

        gate1.complete(Unit)
        runCurrent()
        assertEquals(OpenClawRecoveryState.Connecting(diagnostic), rig.coordinator.recovery.value)
        assertTrue(rig.delays.isEmpty())

        gate2.complete(Unit)
        runCurrent()
        assertEquals(
            OpenClawRecoveryState.Retrying(5_000L, OpenClawFailure.NetworkUnavailable),
            rig.coordinator.recovery.value,
        )
        assertEquals(listOf(5_000L), rig.delays)
        assertEquals(2, rig.automaticCalls)
    }

    @Test fun `a hook from a stopped session sends no hint`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, automatic = { attempt(OpenClawLinkState.Ready) })
        rig.beforeAdmission = { call -> if (call == 1) withContext(NonCancellable) { gate.await() } }

        rig.start(context, session())
        runCurrent()
        rig.coordinator.stop()
        rig.start(context, session())
        runCurrent()
        assertEquals(1, rig.tailscaleCalls)

        gate.complete(Unit) // the stale attempt is admitted late and runs its hook
        runCurrent()

        assertEquals(1, rig.tailscaleCalls)
        assertEquals(OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)
    }

    @Test fun `two quick starts leave exactly one session and one set of collectors`() = runTest {
        val rig = Rig(backgroundScope, automatic = { attempt(failNetwork) })

        rig.start(context, session())
        rig.start(context, session())
        runCurrent()
        assertEquals(1, rig.automaticCalls)
        assertEquals(1, rig.tailscaleCalls)

        rig.edges.value = 1L
        runCurrent()

        assertEquals(2, rig.automaticCalls)
        assertEquals(2, rig.tailscaleCalls)
    }

    @Test fun `a new session starts its own backoff`() = runTest {
        val rig = Rig(backgroundScope, automatic = { attempt(failNetwork) })

        rig.start(context, session())
        runCurrent()
        rig.coordinator.stop()
        rig.start(context, session())
        runCurrent()

        assertEquals(listOf(5_000L, 5_000L), rig.delays)
        rig.coordinator.stop()
    }

    @Test fun `stop during a connect cancels it and nothing retries`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        val rig = Rig(backgroundScope, automatic = {
            try {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
                attempt(OpenClawLinkState.Ready)
            } finally {
                cancelled = true
            }
        })
        rig.start(context)
        runCurrent()
        assertTrue(entered.isCompleted)

        rig.coordinator.stop()
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()

        assertTrue(cancelled)
        assertEquals(1, rig.automaticCalls)
        assertTrue(rig.delays.isEmpty())
        assertEquals(OpenClawRecoveryState.Stopped(null), rig.coordinator.recovery.value)
    }

    // --- stale collectors ---------------------------------------------------------------------------

    /** Holds the first collector's next value until [gate] opens, even after that collector is cancelled. */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class FirstCollectorGate(private val real: StateFlow<OpenClawStatus>) : StateFlow<OpenClawStatus> by real {
        val gate = CompletableDeferred<Unit>()
        var holding = false
        private var collectors = 0

        override suspend fun collect(collector: FlowCollector<OpenClawStatus>): Nothing {
            val index = ++collectors
            real.collect { value ->
                if (index == 1 && holding) withContext(NonCancellable) { gate.await() }
                collector.emit(value)
            }
        }
    }

    @Test fun `a status value delivered late to a stopped session's collector changes nothing`() = runTest {
        var gated: FirstCollectorGate? = null
        val rig = Rig(
            backgroundScope,
            automatic = {
                if (automaticCalls == 1) CompletableDeferred<Unit>().await()
                attempt(OpenClawLinkState.Ready)
            },
            statusSource = { real -> FirstCollectorGate(real).also { gated = it } },
        )
        rig.start(context, session())
        runCurrent()
        gated!!.holding = true
        rig.emit(OpenClawLinkState.Disconnected) // the old collector takes this value, then stalls
        runCurrent()

        rig.coordinator.stop()
        rig.start(context, session())
        runCurrent()
        assertEquals(2, rig.automaticCalls)
        assertEquals(OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)

        gated!!.gate.complete(Unit)
        runCurrent()

        assertEquals("a stale status must not start an attempt for the new session", 2, rig.automaticCalls)
        assertEquals(OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)
    }

    @Test fun `a network edge delivered late to a stopped session's collector changes nothing`() = runTest {
        val lateEdge = CompletableDeferred<Unit>()
        val staleEdges = object : Flow<Long> {
            override suspend fun collect(collector: FlowCollector<Long>) {
                withContext(NonCancellable) { lateEdge.await() }
                collector.emit(1L)
            }
        }
        val rig = Rig(backgroundScope, automatic = {
            if (automaticCalls == 1) CompletableDeferred<Unit>().await()
            attempt(OpenClawLinkState.Ready)
        })
        rig.coordinator.start(context, session(), staleEdges)
        runCurrent()
        rig.coordinator.stop()
        rig.start(context, session())
        runCurrent()
        assertEquals(2, rig.automaticCalls)
        assertEquals(2, rig.tailscaleCalls)

        lateEdge.complete(Unit)
        runCurrent()

        assertEquals("a stale edge must not start an attempt for the new session", 2, rig.automaticCalls)
        assertEquals(2, rig.tailscaleCalls)
        assertEquals(OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)
    }

    // --- car startup with a late internet -----------------------------------------------------------

    @Test fun `offline for three minutes the timer keeps its backoff and the later edge connects once with one hint`() = runTest {
        var online = false
        val rig = Rig(backgroundScope, automatic = { attempt(if (online) OpenClawLinkState.Ready else failNetwork) })
        val monitor = NetworkAvailableMonitor(
            context,
            reachabilityProbe = { online },
            scope = backgroundScope,
            clock = { testScheduler.currentTime + 1L },
        )
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifi = ShadowNetwork.newInstance(7)
        val validated = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        monitor.start()
        rig.coordinator.start(context, backgroundScope, monitor.edges)
        val callback = shadowOf(connectivity).networkCallbacks.single()
        callback.onCapabilitiesChanged(wifi, validated) // Wi-Fi at ignition, no internet yet: the probe fails
        runCurrent()
        advanceTimeBy(190_000)
        runCurrent()

        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), rig.delays)
        assertEquals(6, rig.automaticCalls)
        assertEquals("timer retries send no hint", 1, rig.tailscaleCalls)
        assertEquals(
            OpenClawRecoveryState.Retrying(60_000L, OpenClawFailure.NetworkUnavailable),
            rig.coordinator.recovery.value,
        )

        online = true
        callback.onCapabilitiesChanged(wifi, validated) // the same network reports again once the internet arrives
        runCurrent()

        assertEquals(7, rig.automaticCalls)
        assertEquals(2, rig.tailscaleCalls)
        assertEquals(listOf("admitted", "hint", "connect"), rig.events.takeLast(3))
        assertEquals(listOf("admitted", "hint"), rig.eventsAtTrace.last().takeLast(2))
        assertEquals("network", rig.decisions.last()["origin"])
        assertEquals(OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals("no duplicate attempt at the cancelled timer deadline", 7, rig.automaticCalls)
        assertEquals(2, rig.tailscaleCalls)
        assertEquals(OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)
        rig.coordinator.stop()
        monitor.stop()
    }

    // --- diagnostics --------------------------------------------------------------------------------

    private val allowedDecisionValues = mapOf(
        "origin" to setOf("start", "wake", "network", "timer", "status", "stale"),
        "admission" to setOf("admitted", "skipped"),
        "reason" to setOf("disabled", "invalid_endpoint", "unpaired", "awaiting_approval", "revoked", "identity_changed"),
        "hint" to setOf("not_requested", "requested", "request_error"),
        "receiver" to setOf("supported", "missing"),
        "sent" to setOf("yes", "no"),
    )

    private fun assertAllowlisted(rig: Rig) {
        assertTrue("some decision was recorded", rig.traces.isNotEmpty())
        for ((what, fields) in rig.traces) {
            assertEquals("openclaw_auto", what)
            for ((key, value) in fields) {
                assertTrue("$key=$value", allowedDecisionValues[key]?.contains(value) == true)
            }
        }
    }

    private fun admitted(origin: String, hint: String = "not_requested") =
        mapOf("origin" to origin, "admission" to "admitted", "hint" to hint)

    private fun admittedWithHint(origin: String, receiver: String = "supported", sent: String = "yes") =
        admitted(origin, "requested") + mapOf("receiver" to receiver, "sent" to sent)

    private fun skipped(origin: String, reason: String) =
        mapOf("origin" to origin, "admission" to "skipped", "reason" to reason)

    @Test fun `skipped admissions record their origin and reason and no hint outcome`() = runTest {
        val rig = Rig(backgroundScope, automatic = { error("a skipped admission must not connect") })
        rig.skipAlways(AutomaticReconnectSkipReason.Revoked)

        rig.start(context)
        runCurrent()
        rig.coordinator.onWake()
        runCurrent()
        rig.edges.value = 1L
        runCurrent()

        assertEquals(
            listOf(skipped("start", "revoked"), skipped("wake", "revoked"), skipped("network", "revoked")),
            rig.decisions,
        )
        assertEquals(0, rig.tailscaleCalls)
        assertAllowlisted(rig)
    }

    @Test fun `an admitted attempt records its hint outcome after the hint and before connect`() = runTest {
        val results = listOf(
            TailscaleStartup.ConnectionRequestResult(receiverSupported = true, sent = true) to admittedWithHint("start"),
            TailscaleStartup.ConnectionRequestResult(receiverSupported = false, sent = false) to
                admittedWithHint("start", receiver = "missing", sent = "no"),
        )
        for ((result, expected) in results) {
            val rig = Rig(backgroundScope, automatic = { attempt(OpenClawLinkState.Ready) }, tailscaleResult = result)
            rig.start(context)
            runCurrent()

            assertEquals(listOf("admitted", "hint", "connect"), rig.events.take(3))
            assertEquals("recorded after the hint, before connect", listOf("admitted", "hint"), rig.eventsAtTrace.single())
            assertEquals(listOf(expected), rig.decisions)
            assertAllowlisted(rig)
            rig.coordinator.stop()
        }
    }

    @Test fun `timer status and stale re-evaluations record their origin and request no hint`() = runTest {
        val rig = Rig(backgroundScope, automatic = {
            attempt(if (automaticCalls == 2) OpenClawLinkState.Ready else failNetwork)
        })
        rig.start(context)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        rig.emit(OpenClawLinkState.Disconnected) // a drop after Ready
        runCurrent()

        assertEquals(listOf(admittedWithHint("start"), admitted("timer"), admitted("status")), rig.decisions)
        assertEquals(1, rig.tailscaleCalls)
        assertAllowlisted(rig)
        rig.coordinator.stop()

        val stale = Rig(backgroundScope, automatic = { attempt(OpenClawLinkState.Ready) })
        stale.admission = { call -> if (call == 1) AutomaticReconnectSkipReason.Disabled else null }
        stale.start(context)
        runCurrent()

        assertEquals(listOf(skipped("start", "disabled"), admitted("stale")), stale.decisions)
        assertEquals(0, stale.tailscaleCalls)
        assertAllowlisted(stale)
    }

    @Test fun `coalesced service events are recorded with the attempt they start`() = runTest {
        val release = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, automatic = {
            if (automaticCalls == 1) {
                release.await()
                attempt(failNetwork)
            } else {
                attempt(OpenClawLinkState.Ready)
            }
        })
        rig.start(context)
        runCurrent()
        rig.edges.value = 1L
        runCurrent()
        rig.coordinator.onWake() // both coalesce into the next attempt; the first one names it
        runCurrent()
        release.complete(Unit)
        runCurrent()
        rig.coordinator.onWake()
        runCurrent()

        assertEquals(
            listOf(admittedWithHint("start"), admittedWithHint("network"), admittedWithHint("wake")),
            rig.decisions,
        )
        assertEquals(3, rig.automaticCalls)
        assertEquals(3, rig.tailscaleCalls)
        assertAllowlisted(rig)
    }

    @Test fun `a failed Tailscale request records a fixed code and no exception text`() = runTest {
        val rig = Rig(backgroundScope, automatic = { attempt(OpenClawLinkState.Ready) })
        rig.tailscaleError = IllegalStateException("wss://gateway.example.ts.net/ token=secret request=abc")

        rig.start(context)
        runCurrent()

        assertEquals(listOf(admitted("start", hint = "request_error")), rig.decisions)
        val recorded = rig.traces.toString()
        for (sensitive in listOf("gateway", "secret", "token", "request=", "wss", "IllegalState")) {
            assertFalse(sensitive, recorded.contains(sensitive))
        }
        assertEquals(OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)
        assertNull(rig.coordinator.tailscaleHint.value)
        assertAllowlisted(rig)
    }

    @Test fun `a throwing diagnostic sink leaves recovery unchanged`() = runTest {
        fun script(sinkThrows: Boolean): List<Any?> {
            val rig = Rig(backgroundScope, automatic = {
                attempt(if (automaticCalls >= 3) OpenClawLinkState.Ready else failNetwork)
            })
            rig.admission = { call -> if (call == 2) AutomaticReconnectSkipReason.Disabled else null }
            rig.traceSinkThrows = sinkThrows
            rig.start(context, session())
            runCurrent()
            advanceTimeBy(5_000) // timer, then a stale skip and its re-evaluation
            runCurrent()
            rig.coordinator.onWake()
            runCurrent()
            val outcome = listOf(
                rig.events.toList(), rig.delays.toList(), rig.coordinator.recovery.value,
                rig.coordinator.tailscaleHint.value, rig.automaticCalls, rig.tailscaleCalls,
            )
            rig.coordinator.stop()
            return outcome
        }

        val normal = script(sinkThrows = false)
        assertEquals(OpenClawRecoveryState.Ready, normal[2])
        assertEquals(normal, script(sinkThrows = true))
    }

    @Test fun `a stale hook or result from a stopped session records no decision`() = runTest {
        val hookGate = CompletableDeferred<Unit>()
        val skipGate = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, automatic = { attempt(OpenClawLinkState.Ready) })
        rig.beforeAdmission = { call ->
            when (call) {
                1 -> withContext(NonCancellable) { hookGate.await() }
                2 -> withContext(NonCancellable) { skipGate.await() }
            }
        }
        rig.admission = { call -> if (call == 2) AutomaticReconnectSkipReason.Disabled else null }

        rig.start(context, session())
        runCurrent()
        rig.coordinator.stop()
        rig.start(context, session())
        runCurrent()
        rig.coordinator.stop()
        rig.start(context, session())
        runCurrent()
        assertEquals(listOf(admittedWithHint("start")), rig.decisions)

        hookGate.complete(Unit) // the first session is admitted late and runs its hook
        skipGate.complete(Unit) // the second session's skip returns late
        runCurrent()

        assertEquals(listOf(admittedWithHint("start")), rig.decisions)
        assertEquals(1, rig.tailscaleCalls)
        assertEquals(OpenClawRecoveryState.Ready, rig.coordinator.recovery.value)
    }
}
