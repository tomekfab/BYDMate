package com.bydmate.app.agent

import android.app.NotificationManager
import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.data.vehicle.WriteAllowlist
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ActionExecutorTest {
    private val allowlist: WriteAllowlist by lazy {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/assets/competitor-actions.json").exists() }
            ?: error("Cannot find app/src/main/assets/competitor-actions.json from ${File(".").canonicalPath}")
        WriteAllowlist.loadProduction { File(root, "app/src/main/assets/competitor-actions.json").readText() }
    }
    private val registry: ActionRegistry by lazy { ActionRegistry(allowlist = allowlist) }
    private val validator by lazy { RegistryActionValidator(registry) }

    private val vehicleApi = mockk<VehicleApi>(relaxed = true) {
        coEvery { dispatch(any()) } returns Result.success(Unit)
    }
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        every { createConfigurationContext(any()) } answers { self as Context }
        every { getString(any<Int>()) } returns GATE_TEXT
        every { getString(any<Int>(), *anyVararg()) } returns GATE_TEXT
    }
    private val dispatcher = ActionDispatcher(
        vehicleApi, mockk<HelperClient>(relaxed = true), context,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
        mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
        mockk<com.bydmate.app.util.AppStrings>(relaxed = true), dagger.Lazy { mockk(relaxed = true) },
    )

    private class FakeConfirmer(var result: ConfirmResult = ConfirmResult.CONFIRMED) : ActionConfirmer {
        var calls = 0
        var title: String? = null
        var summary: String? = null
        val gate = CompletableDeferred<Unit>()
        var blockUntilReleased = false
        override suspend fun confirm(title: String, summary: String): ConfirmResult {
            calls++
            this.title = title
            this.summary = summary
            if (blockUntilReleased) gate.await()
            return result
        }
    }

    private class RecordingHooks(
        override val cancelled: Deferred<Unit> = CompletableDeferred(),
        var allowDispatch: Boolean = true,
        var onBefore: () -> Unit = {},
    ) : ExecutionHooks {
        var confirmPending = 0
        var beforeCalls = 0
        override fun onConfirmPending() { confirmPending++ }
        override suspend fun beforeDispatch(): Boolean { beforeCalls++; onBefore(); return allowDispatch }
    }

    private fun vehicle(id: String, value: Int? = null): ValidatedAction =
        (validator.validate(ActionCandidate("vehicle", id = id, value = value), null) as ActionValidation.Valid).action

    private fun navQuery(q: String): ValidatedAction =
        (validator.validate(ActionCandidate("navigate", query = q), null) as ActionValidation.Valid).action

    private fun navShortcut(s: String): ValidatedAction =
        (validator.validate(ActionCandidate("navigate", shortcut = s), null) as ActionValidation.Valid).action

    private fun executor(
        confirmer: ActionConfirmer = FakeConfirmer(),
        snapshot: () -> com.bydmate.app.data.remote.DiParsData? = { diParsData(speed = 0) },
        dispatcher: ActionDispatcher = this.dispatcher,
    ) = DispatcherActionExecutor(registry, dispatcher, confirmer, snapshot)

    @Test fun `comfort action dispatches once without asking`() = runBlocking {
        val confirmer = FakeConfirmer()
        val hooks = RecordingHooks()

        val outcome = executor(confirmer).execute(vehicle("ac_set_temp", 22), ActionOrigin.ASSISTANT, hooks)

        assertEquals(ActionOutcome.Done, outcome)
        coVerify(exactly = 1) { vehicleApi.dispatch("设置温度22") }
        assertEquals(0, confirmer.calls)
        assertEquals(0, hooks.confirmPending)
        assertEquals(1, hooks.beforeCalls)
    }

    @Test fun `confirm action confirmed at standstill dispatches once with the registry summary`() = runBlocking {
        dispatcher.readSpeedNow = { 0 }
        val confirmer = FakeConfirmer(ConfirmResult.CONFIRMED)
        val hooks = RecordingHooks()
        val action = vehicle("windows_open_all")

        val outcome = executor(confirmer).execute(action, ActionOrigin.ASSISTANT, hooks)

        assertEquals(ActionOutcome.Done, outcome)
        coVerify(exactly = 1) { vehicleApi.dispatch(any()) }
        assertEquals(1, confirmer.calls)
        assertEquals("Asystent", confirmer.title)
        assertEquals(registry.summarize(action), confirmer.summary)
        assertEquals(1, hooks.confirmPending)
        assertEquals(1, hooks.beforeCalls)
    }

    @Test fun `declined timed out and unavailable confirmations never dispatch`() = runBlocking {
        val expected = mapOf(
            ConfirmResult.DECLINED to ActionOutcome.Declined,
            ConfirmResult.TIMED_OUT to ActionOutcome.TimedOut,
            ConfirmResult.UNAVAILABLE to ActionOutcome.ConfirmUnavailable,
        )
        for ((result, outcome) in expected) {
            val hooks = RecordingHooks()
            assertEquals(outcome, executor(FakeConfirmer(result)).execute(vehicle("windows_open_all"), ActionOrigin.ASSISTANT, hooks))
            assertEquals(0, hooks.beforeCalls)
        }
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `cancelled completing while the overlay is pending gives Cancelled and no dispatch`() = runBlocking {
        val confirmer = FakeConfirmer().apply { blockUntilReleased = true }
        val cancelled = CompletableDeferred<Unit>()
        val hooks = RecordingHooks(cancelled = cancelled)

        val running = async { executor(confirmer).execute(vehicle("doors_lock"), ActionOrigin.ASSISTANT, hooks) }
        assertTrue("confirmer was never asked", withTimeoutOrNull(WAIT_MS) { while (confirmer.calls == 0) delay(5); true } == true)
        assertEquals(1, hooks.confirmPending)
        cancelled.complete(Unit)

        val outcome = withTimeoutOrNull(WAIT_MS) { running.await() }
        confirmer.gate.complete(Unit)
        assertEquals(ActionOutcome.Cancelled, outcome)
        assertEquals(0, hooks.beforeCalls)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `already cancelled never shows the overlay`() = runBlocking {
        val confirmer = FakeConfirmer()
        val hooks = RecordingHooks(cancelled = CompletableDeferred<Unit>().apply { complete(Unit) })

        assertEquals(ActionOutcome.Cancelled, executor(confirmer).execute(vehicle("doors_lock"), ActionOrigin.ASSISTANT, hooks))
        assertEquals(ActionOutcome.Cancelled, executor(confirmer).execute(vehicle("ac_set_temp", 22), ActionOrigin.ASSISTANT, hooks))

        assertEquals(0, confirmer.calls)
        assertEquals(0, hooks.confirmPending)
        assertEquals(0, hooks.beforeCalls)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `a confirmation that races with cancellation is dropped`() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        // The overlay answers CONFIRMED in the same instant the proposal is cancelled.
        val confirmer = object : ActionConfirmer {
            override suspend fun confirm(title: String, summary: String): ConfirmResult {
                cancelled.complete(Unit)
                return ConfirmResult.CONFIRMED
            }
        }
        val hooks = RecordingHooks(cancelled = cancelled)

        assertEquals(ActionOutcome.Cancelled, executor(confirmer).execute(vehicle("doors_lock"), ActionOrigin.ASSISTANT, hooks))

        assertEquals(0, hooks.beforeCalls)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `beforeDispatch false gives Cancelled and no dispatch for comfort and confirm`() = runBlocking {
        dispatcher.readSpeedNow = { 0 }
        val comfort = RecordingHooks(allowDispatch = false)
        assertEquals(ActionOutcome.Cancelled, executor().execute(vehicle("ac_set_temp", 22), ActionOrigin.ASSISTANT, comfort))
        val confirm = RecordingHooks(allowDispatch = false)
        assertEquals(ActionOutcome.Cancelled, executor().execute(vehicle("windows_open_all"), ActionOrigin.ASSISTANT, confirm))
        val nav = RecordingHooks(allowDispatch = false)
        assertEquals(ActionOutcome.Cancelled, executor().execute(navShortcut("home"), ActionOrigin.ASSISTANT, nav))

        assertEquals(1, comfort.beforeCalls)
        assertEquals(1, confirm.beforeCalls)
        assertEquals(1, nav.beforeCalls)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `window open confirmed at 130 km per h is blocked with the gate text and no write`() = runBlocking {
        dispatcher.readSpeedNow = { 130 }

        val outcome = executor(FakeConfirmer(ConfirmResult.CONFIRMED))
            .execute(vehicle("windows_open_all"), ActionOrigin.ASSISTANT, RecordingHooks())

        assertEquals(ActionOutcome.Blocked("gate", GATE_TEXT), outcome)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `unlock at 40 km per h is blocked`() = runBlocking {
        dispatcher.readSpeedNow = { 40 }

        val outcome = executor(FakeConfirmer(ConfirmResult.CONFIRMED))
            .execute(vehicle("doors_unlock"), ActionOrigin.ASSISTANT, RecordingHooks())

        assertTrue(outcome is ActionOutcome.Blocked)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `a vehicle write failure is a generic Failed, not Blocked`() = runBlocking {
        coEvery { vehicleApi.dispatch(any()) } returns Result.failure(IllegalStateException("boom"))

        val outcome = executor().execute(vehicle("ac_set_temp", 22), ActionOrigin.ASSISTANT, RecordingHooks())

        assertEquals(ActionOutcome.Failed("dispatch_failed"), outcome)
    }

    @Test fun `an untrusted dispatcher failure reason is never passed through`() = runBlocking {
        val mocked = mockk<ActionDispatcher>()
        coEvery { mocked.dispatch(any(), any()) } returns DispatchResult(false, "secret intent://x", trustedLocalizedReason = false)

        val outcome = executor(dispatcher = mocked).execute(navQuery("Szybowcowa 5"), ActionOrigin.ASSISTANT, RecordingHooks())

        assertEquals(ActionOutcome.Failed("dispatch_failed"), outcome)
    }

    @Test fun `a dispatcher exception is a generic Failed`() = runBlocking {
        val mocked = mockk<ActionDispatcher>()
        coEvery { mocked.dispatch(any(), any()) } throws IllegalStateException("boom")

        val outcome = executor(dispatcher = mocked).execute(navShortcut("work"), ActionOrigin.ASSISTANT, RecordingHooks())

        assertEquals(ActionOutcome.Failed("dispatch_failed"), outcome)
    }

    @Test fun `USER_ALIAS takes the same confirm path as ASSISTANT`() = runBlocking {
        dispatcher.readSpeedNow = { 0 }
        for (origin in ActionOrigin.entries) {
            val confirmer = FakeConfirmer(ConfirmResult.DECLINED)
            val hooks = RecordingHooks()
            assertEquals(ActionOutcome.Declined, executor(confirmer).execute(vehicle("trunk_open"), origin, hooks))
            assertEquals(1, confirmer.calls)
            assertEquals(1, hooks.confirmPending)
        }
        for (origin in ActionOrigin.entries) {
            val confirmer = FakeConfirmer()
            assertEquals(ActionOutcome.Done, executor(confirmer).execute(vehicle("ac_set_temp", 22), origin, RecordingHooks()))
            assertEquals(0, confirmer.calls)
        }
        coVerify(exactly = 2) { vehicleApi.dispatch("设置温度22") }
        coVerify(exactly = 0) { vehicleApi.dispatch(neq("设置温度22")) }
    }

    @Test fun `USER_ALIAS with default hooks still runs a comfort action`() = runBlocking {
        assertEquals(ActionOutcome.Done, executor().execute(vehicle("ac_set_temp", 22), ActionOrigin.USER_ALIAS))
        coVerify(exactly = 1) { vehicleApi.dispatch("设置温度22") }
    }

    @Test fun `navigation payloads are the mapper and shortcut action defs and never ask`() = runBlocking {
        val mocked = mockk<ActionDispatcher>()
        val captured = mutableListOf<ActionDef>()
        val slot = slot<ActionDef>()
        coEvery { mocked.dispatch(capture(slot), any()) } answers { captured += slot.captured; DispatchResult(true) }
        val confirmer = FakeConfirmer()
        val exec = executor(confirmer, dispatcher = mocked)

        assertEquals(ActionOutcome.Done, exec.execute(navQuery("Szybowcowa 5"), ActionOrigin.ASSISTANT, RecordingHooks()))
        assertEquals(ActionOutcome.Done, exec.execute(navShortcut("home"), ActionOrigin.USER_ALIAS, RecordingHooks()))
        assertEquals(ActionOutcome.Done, exec.execute(navShortcut("work"), ActionOrigin.ASSISTANT, RecordingHooks()))

        assertEquals(0, confirmer.calls)
        assertEquals(3, captured.size)
        assertEquals("navigate", captured[0].kind)
        assertEquals("", captured[0].command)
        assertEquals(JSONObject().put("query", "Szybowcowa 5").put("go", false).toString(), captured[0].payload)
        assertEquals("navigate", captured[1].kind)
        assertEquals(JSONObject().put("shortcut", "home").put("go", false).toString(), captured[1].payload)
        assertEquals(JSONObject().put("shortcut", "work").put("go", false).toString(), captured[2].payload)
        // Preview only: a home or work shortcut with go=true would press the Yandex start button through
        // accessibility, and the approved premise is that no route starts automatically.
        captured.forEach { assertFalse(it.payload, ActionDispatcher.autoGoRequested(JSONObject(it.payload))) }
    }

    @Test fun `vehicle ActionDef carries the resolved command, the id and kind param`() = runBlocking {
        val mocked = mockk<ActionDispatcher>()
        val slot = slot<ActionDef>()
        coEvery { mocked.dispatch(capture(slot), any()) } returns DispatchResult(true)

        executor(dispatcher = mocked).execute(vehicle("ac_set_temp", 22), ActionOrigin.ASSISTANT, RecordingHooks())

        assertEquals(ActionDef(command = "设置温度22", displayName = "ac_set_temp", kind = "param"), slot.captured)
    }

    @Test fun `the snapshot is read after beforeDispatch, not before`() = runBlocking {
        val events = mutableListOf<String>()
        val mocked = mockk<ActionDispatcher>()
        coEvery { mocked.dispatch(any(), any()) } answers { events += "dispatch"; DispatchResult(true) }
        val hooks = RecordingHooks(onBefore = { events += "before" })

        executor(snapshot = { events += "snapshot"; null }, dispatcher = mocked)
            .execute(vehicle("ac_set_temp", 22), ActionOrigin.ASSISTANT, hooks)

        assertEquals(listOf("before", "snapshot", "dispatch"), events)
    }

    @Test fun `cancelling the caller after beforeDispatch still dispatches once and returns the outcome`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { vehicleApi.dispatch(any()) } coAnswers { started.complete(Unit); release.await(); Result.success(Unit) }
        var outcome: ActionOutcome? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            outcome = executor().execute(vehicle("ac_set_temp", 22), ActionOrigin.ASSISTANT, RecordingHooks())
        }
        val dispatchStarted = withTimeoutOrNull(WAIT_MS) { started.await(); true } == true
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue("dispatch never started", dispatchStarted)

        coVerify(exactly = 1) { vehicleApi.dispatch("设置温度22") }
        assertEquals(ActionOutcome.Done, outcome)
    }

    @Test fun `cancelling the caller inside beforeDispatch still completes the write`() = runBlocking {
        lateinit var callerJob: kotlinx.coroutines.Job
        val hooks = RecordingHooks(onBefore = { callerJob.cancel() })
        callerJob = launch {
            executor().execute(vehicle("ac_set_temp", 22), ActionOrigin.ASSISTANT, hooks)
        }
        callerJob.join()

        coVerify(exactly = 1) { vehicleApi.dispatch("设置温度22") }
    }

    private companion object {
        const val WAIT_MS = 3_000L
        const val GATE_TEXT = "Zablokowane: prędkość"
    }
}
