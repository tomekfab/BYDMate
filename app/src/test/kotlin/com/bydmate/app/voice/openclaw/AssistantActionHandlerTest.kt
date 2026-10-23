package com.bydmate.app.voice.openclaw

import com.bydmate.app.agent.ActionExecutor
import com.bydmate.app.agent.ActionOrigin
import com.bydmate.app.agent.ActionOutcome
import com.bydmate.app.agent.ActionCandidate
import com.bydmate.app.agent.ActionRegistry
import com.bydmate.app.agent.ActionRejection
import com.bydmate.app.agent.ActionValidating
import com.bydmate.app.agent.ActionValidation
import com.bydmate.app.agent.ExecutionHooks
import com.bydmate.app.agent.RegistryActionValidator
import com.bydmate.app.agent.ValidatedAction
import com.bydmate.app.data.vehicle.WriteAllowlist
import com.bydmate.app.voice.VoiceJournalEntry.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@Suppress("LargeClass") // Admission, cancellation and settlement scenarios share the ledger and controlled execution fixture.
class AssistantActionHandlerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val allowlist: WriteAllowlist by lazy {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/assets/competitor-actions.json").exists() }
            ?: error("Cannot find app/src/main/assets/competitor-actions.json from ${File(".").canonicalPath}")
        WriteAllowlist.loadProduction { File(root, "app/src/main/assets/competitor-actions.json").readText() }
    }
    private val registry: ActionRegistry by lazy { ActionRegistry(allowlist = allowlist) }

    private var clock = 10_000_000L
    private var identity: String? = "device-a"
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun dir() = File(tmp.root, "openclaw")
    private fun newLedger(directory: File = dir()) = ActionLedger(directory, { identity }, { clock })
    private val ledger: ActionLedger by lazy { newLedger() }

    /** A validator that holds its caller thread until released (or 10 s), so a test can act mid-validation. */
    private class BlockingValidator(private val inner: ActionValidating) : ActionValidating {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun validate(candidate: ActionCandidate, registryVersion: String?): ActionValidation {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            return inner.validate(candidate, registryVersion)
        }
    }

    private val blockers = mutableListOf<BlockingValidator>()

    /** A latch that every test releases at the end, even when an assertion failed, so no thread stays blocked. */
    private class Gate {
        private val latch = CountDownLatch(1)
        fun open() = latch.countDown()
        fun await() { latch.await(10, TimeUnit.SECONDS) }
    }

    private val gates = mutableListOf<Gate>()
    private val threads = mutableListOf<Thread>()

    private fun gate() = Gate().also { gates += it }

    private suspend fun awaitGate(g: Gate) {
        val reached = CompletableDeferred<Unit>()
        val waiter = thread(isDaemon = true, name = "gate-wait") { g.await(); reached.complete(Unit) }
        threads += waiter
        withTimeout(5_000) { reached.await() }
    }

    /** The first validation blocks until released; later ones pass straight through. */
    private class OneShotBlocker(private val inner: ActionValidating) : ActionValidating {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val first = AtomicBoolean(true)
        override fun validate(candidate: ActionCandidate, registryVersion: String?): ActionValidation {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            return inner.validate(candidate, registryVersion)
        }
    }

    private val oneShots = mutableListOf<OneShotBlocker>()

    private fun oneShot() = OneShotBlocker(RegistryActionValidator(registry)).also { oneShots += it }

    private fun tempFile() = File(dir(), ActionLedger.TEMP_NAME)

    /** A non-empty directory in the temp-file slot makes every durable write fail. */
    private fun blockTempFile() {
        dir().mkdirs()
        tempFile().mkdirs()
        File(tempFile(), "keep").writeText("x")
    }

    private fun unblockTempFile() {
        File(tempFile(), "keep").delete()
        tempFile().delete()
    }

    private fun blocking() = BlockingValidator(RegistryActionValidator(registry)).also { blockers += it }

    @After
    fun tearDown() {
        blockers.forEach { it.release.countDown() }
        oneShots.forEach { it.release.countDown() }
        gates.forEach { it.open() }
        scope.cancel()
        threads.forEach { it.join(2_000) }
    }

    private fun handler(
        executor: ActionExecutor,
        sink: AliasProposalSink = AliasProposalSinkHolder(),
        with: ActionLedger = ledger,
        validator: ActionValidating = RegistryActionValidator(registry),
    ) = LedgerAssistantActionHandler(
        registry, validator, with, executor, sink, { clock }, { logs += it }, scope,
    )

    private class Fake(
        val script: suspend (ValidatedAction, ExecutionHooks) -> ActionOutcome = { _, hooks ->
            if (hooks.beforeDispatch()) {
                // the default script "dispatches" by counting in the caller below
                ActionOutcome.Done
            } else {
                ActionOutcome.Cancelled
            }
        },
    ) : ActionExecutor {
        val executes = AtomicInteger()
        val dispatches = AtomicInteger()
        override suspend fun execute(action: ValidatedAction, origin: ActionOrigin, hooks: ExecutionHooks): ActionOutcome {
            executes.incrementAndGet()
            return script(action, CountingHooks(hooks, dispatches))
        }
    }

    /** Counts every `beforeDispatch() == true`, which is the moment a real executor would write. */
    private class CountingHooks(private val inner: ExecutionHooks, private val dispatches: AtomicInteger) : ExecutionHooks {
        override val cancelled = inner.cancelled
        override fun onConfirmPending() = inner.onConfirmPending()
        override suspend fun beforeDispatch(): Boolean = inner.beforeDispatch().also { if (it) dispatches.incrementAndGet() }
    }

    private fun key(n: Int) = "aaaaaaaa-%04d-0000-0000-000000000000".format(n)
    private fun tag(n: Int) = OpenClawProtocol.turnTag(key(n))
    private fun prop(n: Int) = "prop-%04d".format(n)

    private val comfort = """{"kind":"vehicle","id":"ac_set_temp","value":22}"""
    private val confirm = """{"kind":"vehicle","id":"windows_open_all"}"""

    @Suppress("LongParameterList") // Named fields let each scenario vary one part of the protocol envelope.
    private fun envelope(
        n: Int,
        action: String = comfort,
        type: String = "action",
        proposalId: String = prop(n),
        requestId: String = tag(n),
        capabilities: String = registry.version,
        phrase: String? = null,
    ): ParsedEnvelope {
        val phraseField = phrase?.let { ""","phrase":"$it"""" } ?: ""
        return OpenClawActionProtocol.parseEnvelope(
            """{"schema":"bydmate.action/1","requestId":"$requestId","proposalId":"$proposalId",""" +
                """"capabilities":"$capabilities","type":"$type"$phraseField,"action":$action}""",
        )
    }

    private fun input(
        n: Int,
        envelope: ParsedEnvelope = envelope(n),
        recovered: Boolean = false,
        startedAtMs: Long = clock,
    ) = ProposalInput(key(n), envelope, recovered, startedAtMs)

    private fun spoken(r: AssistantActionResult): AssistantActionResult.Spoken {
        assertTrue("expected Spoken but was $r", r is AssistantActionResult.Spoken)
        return r as AssistantActionResult.Spoken
    }

    private suspend fun until(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(10) }
    }

    private fun state(n: Int) = ledger.entry(key(n))?.state

    // --- the main path ---

    @Test
    fun `a valid comfort action runs once and is reported with verified none`() = runBlocking {
        val fake = Fake()
        val h = handler(fake)
        val result = spoken(h.handle(input(1)))
        assertEquals("Wykonano: ustaw temperaturę klimatyzacji na 22°C", result.line)
        assertEquals(Outcome.OK, result.outcome)
        assertEquals("openclaw:ac_set_temp", result.label)
        assertEquals(1, fake.executes.get())
        assertEquals(1, fake.dispatches.get())
        assertEquals(LedgerState.DONE, state(1))
        val report = ledger.pendingReport()!!
        assertEquals(prop(1), report.proposalId)
        assertTrue(report.line, report.line.contains("\"status\":\"done\""))
        assertTrue(report.line, report.line.endsWith("\"verified\":\"none\"}"))
        until { h.activeProposals == 0 }
    }

    @Test
    fun `an invalid envelope is rejected with a synthetic id and no report`() = runBlocking {
        val fake = Fake()
        val h = handler(fake)
        val result = spoken(h.handle(ProposalInput(key(1), ParsedEnvelope.Invalid(ActionRejection.MALFORMED), false, clock)))
        assertEquals(PolishActionText.REJECTED, result.line)
        assertEquals(Outcome.NOT_UNDERSTOOD, result.outcome)
        assertEquals("openclaw:rejected", result.label)
        assertEquals(0, fake.executes.get())
        val entry = ledger.entry(key(1))!!
        assertEquals(LedgerState.REJECTED, entry.state)
        assertEquals("MALFORMED", entry.reason)
        assertEquals("invalid-${tag(1)}", entry.proposalId)
        assertNull(ledger.pendingReport())
        assertEquals(AssistantActionResult.Silent, h.handle(ProposalInput(key(1), ParsedEnvelope.Invalid(ActionRejection.MALFORMED), false, clock)))
    }

    @Test
    fun `a recovered proposal never executes and never opens an alias dialog`() = runBlocking {
        val fake = Fake()
        var presented = 0
        val h = handler(fake, AliasProposalSink { presented++; true })
        val action = spoken(h.handle(input(1, recovered = true)))
        assertEquals(PolishActionText.RECOVERED, action.line)
        val alias = spoken(h.handle(input(2, envelope(2, type = "alias_proposal", phrase = "do domu", action = comfort), recovered = true)))
        assertEquals(PolishActionText.RECOVERED, alias.line)
        assertEquals(0, fake.executes.get())
        assertEquals(0, presented)
        assertEquals("RECOVERED_NOT_EXECUTED", ledger.entry(key(1))!!.reason)
        assertEquals(LedgerState.REJECTED, state(2))
    }

    @Test
    fun `a second handle for the same turn is silent and a reused proposal id is silent`() = runBlocking {
        val fake = Fake()
        val h = handler(fake)
        spoken(h.handle(input(1)))
        assertEquals(AssistantActionResult.Silent, h.handle(input(1)))
        assertEquals(AssistantActionResult.Silent, h.handle(input(1, envelope(1, proposalId = "prop-9999"))))
        assertEquals(AssistantActionResult.Silent, h.handle(input(2, envelope(2, proposalId = prop(1)))))
        assertNull(ledger.entry(key(2)))
        assertEquals(1, fake.executes.get())
    }

    @Test
    fun `wrong request id, stale capabilities and an old turn are rejected before validation`() = runBlocking {
        val fake = Fake()
        val h = handler(fake)
        spoken(h.handle(input(1, envelope(1, requestId = "someoneelse1"))))
        spoken(h.handle(input(2, envelope(2, capabilities = "000000000000"))))
        val old = spoken(h.handle(input(3, startedAtMs = clock - 121_000)))
        assertEquals(PolishActionText.EXPIRED, old.line)
        spoken(h.handle(input(4, startedAtMs = clock - 120_000)))
        assertEquals("NOT_CORRELATED", ledger.entry(key(1))!!.reason)
        assertEquals("STALE_CAPABILITIES", ledger.entry(key(2))!!.reason)
        assertEquals(LedgerState.EXPIRED, state(3))
        assertEquals("EXPIRED", ledger.entry(key(3))!!.reason)
        assertEquals(LedgerState.DONE, state(4))
        assertEquals(1, fake.executes.get())
    }

    @Test
    fun `a massage id is not executable`() = runBlocking {
        val fake = Fake()
        val h = handler(fake)
        spoken(h.handle(input(1, envelope(1, action = """{"kind":"vehicle","id":"driver_massage_lvl1"}"""))))
        assertEquals("NOT_EXECUTABLE", ledger.entry(key(1))!!.reason)
        assertEquals(0, fake.executes.get())
    }

    @Test
    fun `an unwritable ledger fails closed`() = runBlocking {
        val blocked = File(tmp.root, "blocked").also { it.writeText("not a directory") }
        val fake = Fake()
        val h = handler(fake, with = newLedger(blocked))
        val result = spoken(h.handle(input(1)))
        assertEquals(PolishActionText.REJECTED, result.line)
        assertEquals(Outcome.ERROR, result.outcome)
        assertEquals(0, fake.executes.get())
        assertTrue(logs.toString(), logs.contains("action_rejected:LEDGER_UNAVAILABLE"))
    }

    @Test
    fun `a persist failure at the last veto is not a cancellation and never dispatches`() = runBlocking {
        val fake = Fake { _, hooks ->
            dir().deleteRecursively()
            dir().writeText("now a file")
            if (hooks.beforeDispatch()) ActionOutcome.Done else ActionOutcome.Cancelled
        }
        val h = handler(fake)
        val result = spoken(h.handle(input(1)))
        assertEquals(PolishActionText.REJECTED, result.line)
        assertEquals(Outcome.ERROR, result.outcome)
        assertEquals(0, fake.dispatches.get())
        assertTrue(logs.toString(), logs.contains("action_rejected:LEDGER_UNAVAILABLE"))
    }

    // --- outcome mapping ---

    private fun mapped(n: Int, script: suspend (ExecutionHooks) -> ActionOutcome): AssistantActionResult = runBlocking {
        val h = handler(Fake { _, hooks -> script(hooks) })
        h.handle(input(n, envelope(n, action = confirm)))
    }

    private suspend fun dispatching(hooks: ExecutionHooks, outcome: ActionOutcome): ActionOutcome {
        hooks.onConfirmPending()
        assertTrue(hooks.beforeDispatch())
        return outcome
    }

    @Test
    fun `each executor outcome maps to one ledger state, one line and one report`() {
        val blocked = spoken(mapped(1) { dispatching(it, ActionOutcome.Blocked("gate", "Najpierw zatrzymaj auto.")) })
        assertEquals("Najpierw zatrzymaj auto.", blocked.line)
        assertEquals(Outcome.BLOCKED, blocked.outcome)
        assertEquals(LedgerState.BLOCKED, state(1))

        val failed = spoken(mapped(2) { dispatching(it, ActionOutcome.Failed("dispatch_failed")) })
        assertEquals(PolishActionText.FAILED, failed.line)
        assertEquals(Outcome.ERROR, failed.outcome)
        assertEquals(LedgerState.FAILED, state(2))

        val declined = spoken(mapped(3) { it.onConfirmPending(); ActionOutcome.Declined })
        assertEquals(PolishActionText.DECLINED, declined.line)
        assertEquals(Outcome.BLOCKED, declined.outcome)
        assertEquals(LedgerState.REJECTED, state(3))
        assertEquals("CONFIRM_DECLINED", ledger.entry(key(3))!!.reason)
        assertTrue(ledger.pendingReport()!!.line, ledger.pendingReport()!!.line.contains("\"status\":\"declined\""))

        val timedOut = spoken(mapped(4) { it.onConfirmPending(); ActionOutcome.TimedOut })
        assertEquals(PolishActionText.TIMED_OUT, timedOut.line)
        assertEquals(LedgerState.EXPIRED, state(4))
        assertTrue(ledger.pendingReport()!!.line, ledger.pendingReport()!!.line.contains("\"status\":\"expired\""))

        val noOverlay = spoken(mapped(5) { it.onConfirmPending(); ActionOutcome.ConfirmUnavailable })
        assertEquals(PolishActionText.NO_OVERLAY, noOverlay.line)
        assertEquals(LedgerState.REJECTED, state(5))
        assertEquals("CONFIRM_UNAVAILABLE", ledger.entry(key(5))!!.reason)
    }

    @Test
    fun `an executor that throws after dispatch leaves the entry uncertain`() = runBlocking {
        val h = handler(Fake { _, hooks -> hooks.beforeDispatch(); error("boom") })
        val result = spoken(h.handle(input(1)))
        assertEquals(PolishActionText.UNCERTAIN, result.line)
        assertEquals(LedgerState.UNCERTAIN, state(1))
        assertTrue(ledger.pendingReport()!!.line.contains("\"status\":\"uncertain\""))
    }

    // --- cancellation ---

    @Test
    fun `cancelPending cancels a confirm-class action waiting for the driver`() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val fake = Fake { _, hooks ->
            hooks.onConfirmPending()
            waiting.complete(Unit)
            hooks.cancelled.await()
            ActionOutcome.Cancelled
        }
        val h = handler(fake)
        val result = async { h.handle(input(1, envelope(1, action = confirm))) }
        withTimeout(5_000) { waiting.await() }
        assertEquals(LedgerState.CONFIRM_PENDING, state(1))
        h.cancelPending(CancelReason.BARGE_IN)
        assertEquals(AssistantActionResult.Silent, withTimeout(5_000) { result.await() })
        assertEquals(LedgerState.CANCELLED, state(1))
        assertEquals(0, fake.dispatches.get())
        assertTrue(ledger.pendingReport()!!.line.contains("\"status\":\"cancelled\""))
        until { h.activeProposals == 0 }
    }

    @Test
    fun `a last veto after cancelPending never dispatches`() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val fake = Fake { _, hooks ->
            reached.complete(Unit)
            gate.await()
            if (hooks.beforeDispatch()) ActionOutcome.Done else ActionOutcome.Cancelled
        }
        val h = handler(fake)
        val result = async { h.handle(input(1)) }
        withTimeout(5_000) { reached.await() }
        h.cancelPending(CancelReason.LOCAL_PRIORITY)
        gate.complete(Unit)
        assertEquals(AssistantActionResult.Silent, withTimeout(5_000) { result.await() })
        assertEquals(0, fake.dispatches.get())
        assertEquals(LedgerState.CANCELLED, state(1))
    }

    @Test
    fun `opting out cancels pending proposals`() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val h = handler(Fake { _, hooks -> hooks.onConfirmPending(); waiting.complete(Unit); hooks.cancelled.await(); ActionOutcome.Cancelled })
        val result = async { h.handle(input(1, envelope(1, action = confirm))) }
        withTimeout(5_000) { waiting.await() }
        h.onOptOut()
        assertEquals(AssistantActionResult.Silent, withTimeout(5_000) { result.await() })
        assertEquals(LedgerState.CANCELLED, state(1))
        assertEquals("OPT_OUT", ledger.entry(key(1))!!.reason)
    }

    @Test
    fun `cancelling the caller before dispatch cancels the proposal through the hooks`() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val sawCancel = CompletableDeferred<Unit>()
        val fake = Fake { _, hooks ->
            hooks.onConfirmPending()
            waiting.complete(Unit)
            hooks.cancelled.await()
            sawCancel.complete(Unit)
            ActionOutcome.Cancelled
        }
        val h = handler(fake)
        val caller = launch { h.handle(input(1, envelope(1, action = confirm))) }
        withTimeout(5_000) { waiting.await() }
        caller.cancelAndJoin()
        withTimeout(5_000) { sawCancel.await() }
        until { state(1) == LedgerState.CANCELLED }
        assertEquals(0, fake.dispatches.get())
        assertTrue(ledger.pendingReport()!!.line.contains("\"status\":\"cancelled\""))
        until { h.activeProposals == 0 }
    }

    @Test
    fun `cancelling the caller after dispatch started lets the dispatch finish and returns the real result`() = runBlocking {
        val inDispatch = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val written = AtomicInteger()
        val fake = Fake { _, hooks ->
            assertTrue(hooks.beforeDispatch())
            inDispatch.complete(Unit)
            withContext(NonCancellable) {
                release.await()
                written.incrementAndGet()
            }
            ActionOutcome.Done
        }
        val h = handler(fake)
        val got = CompletableDeferred<AssistantActionResult>()
        val caller = launch { got.complete(h.handle(input(1))) }
        withTimeout(5_000) { inDispatch.await() }
        caller.cancel()
        assertEquals(LedgerState.DISPATCHING, state(1))
        release.complete(Unit)
        val result = spoken(withTimeout(5_000) { got.await() })
        assertEquals(Outcome.OK, result.outcome)
        assertEquals(LedgerState.DONE, state(1))
        assertEquals(1, written.get())
        assertEquals(1, fake.dispatches.get())
        caller.join()
        until { h.activeProposals == 0 }
    }

    // --- admission-time cancellation, owned pipeline ---

    private suspend fun awaitEntered(b: BlockingValidator) {
        withTimeout(5_000) { while (b.entered.count > 0) delay(10) }
    }

    @Test
    fun `cancelling the caller while the validator blocks neither hangs nor dispatches`() = runBlocking {
        val b = blocking()
        val fake = Fake()
        val h = handler(fake, validator = b)
        val caller = launch(Dispatchers.Default) { h.handle(input(1)) }
        awaitEntered(b)
        val joined = withTimeoutOrNull(2_000) { caller.cancelAndJoin(); true }
        b.release.countDown()
        assertNotNull("cancellation must not wait for the validator", joined)
        assertEquals(LedgerState.CANCELLED, state(1))
        assertEquals("CALLER", ledger.entry(key(1))!!.reason)
        until { h.activeProposals == 0 }
        assertEquals(0, fake.executes.get())
        assertEquals(0, fake.dispatches.get())
        assertEquals(LedgerState.CANCELLED, state(1))
        assertTrue(ledger.pendingReport()!!.line.contains("\"status\":\"cancelled\""))
    }

    @Test
    fun `cancelPending between admission and execution cancels the proposal`() = runBlocking {
        val b = blocking()
        val fake = Fake()
        val h = handler(fake, validator = b)
        val result = async(Dispatchers.Default) { h.handle(input(1)) }
        awaitEntered(b)
        h.cancelPending(CancelReason.BARGE_IN)
        b.release.countDown()
        assertEquals(AssistantActionResult.Silent, withTimeout(5_000) { result.await() })
        assertEquals(0, fake.dispatches.get())
        assertEquals(LedgerState.CANCELLED, state(1))
        assertEquals("BARGE_IN", ledger.entry(key(1))!!.reason)
        until { h.activeProposals == 0 }
    }

    @Test
    fun `a caller cancelled before the last veto is vetoed even when the ledger still says accepted`() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val fake = Fake { _, hooks ->
            reached.complete(Unit)
            gate.await()
            if (hooks.beforeDispatch()) ActionOutcome.Done else ActionOutcome.Cancelled
        }
        val h = handler(fake)
        val caller = launch(Dispatchers.Default) { h.handle(input(1)) }
        withTimeout(5_000) { reached.await() }
        caller.cancelAndJoin()
        gate.complete(Unit)
        until { h.activeProposals == 0 }
        assertEquals(0, fake.dispatches.get())
        assertEquals(LedgerState.CANCELLED, state(1))
    }

    // --- the cancellation winner keeps its settlement ---

    @Test
    fun `a decline timeout or failure that races a cancellation never replaces the cancelled settlement`() = runBlocking {
        val losers = listOf<ActionOutcome>(ActionOutcome.Declined, ActionOutcome.TimedOut, ActionOutcome.Failed("dispatch_failed"))
        losers.forEachIndexed { index, loser ->
            val n = index + 1
            val waiting = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val h = handler(Fake { _, hooks ->
                hooks.onConfirmPending()
                waiting.complete(Unit)
                gate.await()
                loser
            })
            val result = async { h.handle(input(n, envelope(n, action = confirm))) }
            withTimeout(5_000) { waiting.await() }
            h.cancelPending(CancelReason.BARGE_IN)
            gate.complete(Unit)
            assertEquals("$loser", AssistantActionResult.Silent, withTimeout(5_000) { result.await() })
            assertEquals("$loser", LedgerState.CANCELLED, state(n))
            val report = ledger.pendingReport()!!
            assertEquals("$loser", prop(n), report.proposalId)
            assertTrue("$loser ${report.line}", report.line.contains("\"status\":\"cancelled\""))
            until { h.activeProposals == 0 }
        }
    }

    // --- a completion belongs to the admission that started it ---

    private class Blocked(val inDispatch: CompletableDeferred<Unit> = CompletableDeferred(), val release: CompletableDeferred<Unit> = CompletableDeferred())

    /** First call dispatches and waits for [first].release, then ends as [firstOutcome]; later calls dispatch and end as Failed. */
    private fun twoRuns(first: Blocked, firstOutcome: ActionOutcome): Fake {
        val calls = AtomicInteger()
        return Fake { _, hooks ->
            if (calls.incrementAndGet() == 1) {
                assertTrue(hooks.beforeDispatch())
                first.inDispatch.complete(Unit)
                first.release.await()
                firstOutcome
            } else {
                assertTrue(hooks.beforeDispatch())
                ActionOutcome.Failed("dispatch_failed")
            }
        }
    }

    private suspend fun staleCompletion(switchIdentity: Boolean, reuseIds: Boolean) {
        val first = Blocked()
        val fake = twoRuns(first, ActionOutcome.Done)
        val h = handler(fake)
        val a = CompletableDeferred<AssistantActionResult>()
        val callerA = scope.launch { a.complete(h.handle(input(1))) }
        withTimeout(5_000) { first.inDispatch.await() }
        assertEquals(LedgerState.DISPATCHING, state(1))

        h.onIdentityForgotten()
        if (switchIdentity) identity = "device-b"
        val secondKey = if (reuseIds) 1 else 2
        val b = spoken(h.handle(input(secondKey, envelope(secondKey))))
        assertEquals(PolishActionText.FAILED, b.line)
        assertEquals(LedgerState.FAILED, state(secondKey))
        val reportBefore = ledger.pendingReport()
        assertTrue(reportBefore.toString(), reportBefore!!.line.contains("\"status\":\"failed\""))

        first.release.complete(Unit)
        val late = spoken(withTimeout(5_000) { a.await() })
        assertEquals("the dispatch really happened: the driver hears the true result", Outcome.OK, late.outcome)
        assertEquals(LedgerState.FAILED, state(secondKey))
        assertEquals("dispatch_failed", ledger.entry(key(secondKey))!!.reason)
        assertEquals("the new admission's report is untouched", reportBefore, ledger.pendingReport())
        if (!reuseIds) assertNull(ledger.entry(key(1)))
        assertEquals("no replay", 2, fake.executes.get())
        callerA.join()
        until { h.activeProposals == 0 }
    }

    @Test
    fun `an old completion cannot settle a reused key and proposal under a new identity`() = runBlocking {
        staleCompletion(switchIdentity = true, reuseIds = true)
    }

    @Test
    fun `an old completion cannot settle distinct ids under a new identity`() = runBlocking {
        staleCompletion(switchIdentity = true, reuseIds = false)
    }

    @Test
    fun `an old completion cannot settle a reused key and proposal after a clear on the same identity`() = runBlocking {
        staleCompletion(switchIdentity = false, reuseIds = true)
    }

    @Test
    fun `an old completion cannot settle distinct ids after a clear on the same identity`() = runBlocking {
        staleCompletion(switchIdentity = false, reuseIds = false)
    }

    @Test
    fun `a failure that completes after a forget still says failed and writes nothing`() = runBlocking {
        val first = Blocked()
        val h = handler(twoRuns(first, ActionOutcome.Failed("dispatch_failed")))
        val a = CompletableDeferred<AssistantActionResult>()
        scope.launch { a.complete(h.handle(input(1))) }
        withTimeout(5_000) { first.inDispatch.await() }
        h.onIdentityForgotten()
        first.release.complete(Unit)
        val result = spoken(withTimeout(5_000) { a.await() })
        assertEquals(PolishActionText.FAILED, result.line)
        assertEquals(Outcome.ERROR, result.outcome)
        assertNull(ledger.entry(key(1)))
        assertNull(ledger.pendingReport())
    }

    @Test
    fun `a conversation reset during dispatch settles the entry but drops the old report`() = runBlocking {
        val first = Blocked()
        val h = handler(twoRuns(first, ActionOutcome.Done))
        val a = CompletableDeferred<AssistantActionResult>()
        scope.launch { a.complete(h.handle(input(1))) }
        withTimeout(5_000) { first.inDispatch.await() }
        h.onConversationReset()
        first.release.complete(Unit)
        val result = spoken(withTimeout(5_000) { a.await() })
        assertEquals(Outcome.OK, result.outcome)
        assertEquals(LedgerState.DONE, state(1))
        assertNull("reported to an old session: nobody should see it", ledger.pendingReport())
    }

    @Test
    fun `cancelling an old caller after the key was reused leaves the new entry alone`() = runBlocking {
        val waitingA = CompletableDeferred<Unit>()
        val waitingB = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val fake = Fake { _, hooks ->
            if (calls.incrementAndGet() == 1) {
                hooks.onConfirmPending()
                waitingA.complete(Unit)
                hooks.cancelled.await()
                ActionOutcome.Cancelled
            } else {
                hooks.onConfirmPending()
                waitingB.complete(Unit)
                hooks.cancelled.await()
                ActionOutcome.Cancelled
            }
        }
        val h = handler(fake)
        val callerA = scope.launch { h.handle(input(1, envelope(1, action = confirm))) }
        withTimeout(5_000) { waitingA.await() }
        h.onIdentityForgotten()
        val callerB = scope.launch { h.handle(input(1, envelope(1, action = confirm))) }
        withTimeout(5_000) { waitingB.await() }
        assertEquals(LedgerState.CONFIRM_PENDING, state(1))

        callerA.cancelAndJoin()
        delay(100)
        assertEquals("the new admission is untouched", LedgerState.CONFIRM_PENDING, state(1))
        assertNull(ledger.pendingReport())
        assertTrue(callerB.isActive)
        h.cancelPending(CancelReason.BARGE_IN)
        until { state(1) == LedgerState.CANCELLED }
        callerB.join()
    }

    @Test
    fun `an alias callback after a forget or a restart settles nothing`() = runBlocking {
        val first = CopyOnWriteArrayList<AliasProposal>()
        val h = handler(Fake(), AliasProposalSink { first += it; true })
        h.handle(input(1, alias(1)))
        h.onIdentityForgotten()
        h.aliasSaved(first.single().proposalId)
        assertNull(ledger.entry(key(1)))
        assertNull(ledger.pendingReport())

        val second = CopyOnWriteArrayList<AliasProposal>()
        val h2 = handler(Fake(), AliasProposalSink { second += it; true })
        h2.handle(input(2, alias(2)))
        val restarted = handler(Fake(), AliasProposalSink { true }, with = newLedger())
        restarted.aliasSaved(second.single().proposalId)
        assertEquals("a late callback of a previous process has no live owner", LedgerState.SAVE_PENDING, state(2))
    }

    // --- R2: arbitration of cancel against the dispatch write ---

    @Test
    fun `a cancel that returns first never lets the dispatch follow even when its write failed`() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val fake = Fake { _, hooks ->
            reached.complete(Unit)
            gate.await()
            if (hooks.beforeDispatch()) ActionOutcome.Done else ActionOutcome.Cancelled
        }
        val h = handler(fake)
        val result = async { h.handle(input(1)) }
        withTimeout(5_000) { reached.await() }
        blockTempFile()
        h.cancelPending(CancelReason.BARGE_IN)
        assertEquals("the failed write left the entry open", LedgerState.ACCEPTED, state(1))
        unblockTempFile()
        gate.complete(Unit)
        assertEquals(AssistantActionResult.Silent, withTimeout(5_000) { result.await() })
        assertEquals("a cancel that returned first wins for good", 0, fake.dispatches.get())
        assertEquals(LedgerState.CANCELLED, state(1))
    }

    @Test
    fun `when the dispatch write wins a concurrent cancel cannot claim the proposal`() = runBlocking {
        val paused = gate()
        val resume = gate()
        val sawCancelled = AtomicBoolean(false)
        val fake = Fake { _, hooks ->
            val dispatched = hooks.beforeDispatch()
            sawCancelled.set(hooks.cancelled.isCompleted)
            if (dispatched) ActionOutcome.Done else ActionOutcome.Cancelled
        }
        val h = handler(fake)
        h.beforeDispatchWriteHook = { paused.open(); resume.await() }
        val result = async { h.handle(input(1)) }
        awaitGate(paused)
        blockTempFile()
        val canceller = thread(name = "w2-cancel") { h.cancelPending(CancelReason.BARGE_IN) }
        threads += canceller
        // Explicit order, no sleep: wait until the cancel is blocked behind the operation (or, in a broken build, done).
        withTimeout(5_000) { while (canceller.state != Thread.State.BLOCKED && canceller.isAlive) delay(5) }
        unblockTempFile()
        resume.open()
        val answer = spoken(withTimeout(5_000) { result.await() })
        canceller.join(5_000)
        assertFalse("the cancel thread must have finished", canceller.isAlive)
        assertEquals(Outcome.OK, answer.outcome)
        assertEquals("the dispatch won the write", 1, fake.dispatches.get())
        assertFalse("a cancel that lost must not complete the cancellation", sawCancelled.get())
        assertEquals(LedgerState.DONE, state(1))
        assertTrue(ledger.pendingReport()!!.line.contains("\"status\":\"done\""))
        assertFalse(logs.toString(), logs.contains("cancelled:BARGE_IN"))
    }

    // --- R2: alias presentations are operation-specific ---

    @Test
    fun `an old dialog Save after a forget and a reuse cannot settle the new proposal`() = runBlocking {
        val enteredA = gate()
        val releaseA = gate()
        val seen = CopyOnWriteArrayList<AliasProposal>()
        val calls = AtomicInteger()
        val h = handler(Fake(), AliasProposalSink { p ->
            seen += p
            if (calls.incrementAndGet() == 1) { enteredA.open(); releaseA.await() }
            true
        })
        // A is inside the sink: it has passed its last check and not yet presented (the pause after the final check).
        val a = async(Dispatchers.Default) { h.handle(input(1, alias(1))) }
        withTimeout(5_000) { while (seen.isEmpty()) delay(5) }
        h.onIdentityForgotten()
        assertTrue(h.handle(input(1, alias(1))) is AssistantActionResult.AliasPresented)
        assertEquals(LedgerState.SAVE_PENDING, state(1))
        releaseA.open()
        assertEquals(AssistantActionResult.Silent, withTimeout(5_000) { a.await() })

        val pA = seen[0].proposalId
        val pB = seen[1].proposalId
        assertNotEquals("each presentation has its own id", pA, pB)
        assertNotEquals(prop(1), pA)
        assertNotEquals(prop(1), pB)
        h.aliasSaved(pA)
        assertEquals("the old dialog's Save settles nothing", LedgerState.SAVE_PENDING, state(1))
        assertNull(ledger.pendingReport())
        h.aliasSaved(pB)
        assertEquals(LedgerState.SAVED, state(1))
        val line = ledger.pendingReport()!!.line
        assertTrue(line, line.contains("\"proposalId\":\"${prop(1)}\""))
        assertFalse(line, line.contains(pB))
        assertEquals(0, h.aliasRegistrations)
    }

    @Test
    fun `a proposal forgotten before it presents never reaches the sink and the new one still saves`() = runBlocking {
        val blocker = oneShot()
        val seen = CopyOnWriteArrayList<AliasProposal>()
        val h = handler(Fake(), AliasProposalSink { seen += it; true }, validator = blocker)
        val a = async(Dispatchers.Default) { h.handle(input(1, alias(1))) }
        withTimeout(5_000) { while (blocker.entered.count > 0) delay(5) }
        h.onIdentityForgotten()
        assertTrue(h.handle(input(1, alias(1))) is AssistantActionResult.AliasPresented)
        blocker.release.countDown()
        assertEquals(AssistantActionResult.Silent, withTimeout(5_000) { a.await() })
        assertEquals("the forgotten proposal never presents", 1, seen.size)
        assertEquals(1, h.aliasRegistrations)
        h.aliasSaved(seen.single().proposalId)
        assertEquals(LedgerState.SAVED, state(1))
        assertEquals("nothing leaks after the answer", 0, h.aliasRegistrations)
    }

    @Test
    fun `an old presentation cannot remove the slot of the new one and a forget clears every registration`() = runBlocking {
        val releaseA = gate()
        val releaseB = gate()
        val seen = CopyOnWriteArrayList<AliasProposal>()
        val calls = AtomicInteger()
        val h = handler(Fake(), AliasProposalSink { p ->
            seen += p
            if (calls.incrementAndGet() == 1) { releaseA.await(); false } else { releaseB.await(); true }
        })
        val a = async(Dispatchers.Default) { h.handle(input(1, alias(1))) }
        withTimeout(5_000) { while (seen.size < 1) delay(5) }
        h.onIdentityForgotten()
        assertEquals("a forget clears the slot and ticket of a presentation still in the sink", 0, h.aliasRegistrations)
        val b = async(Dispatchers.Default) { h.handle(input(1, alias(1))) }
        withTimeout(5_000) { while (seen.size < 2) delay(5) }
        releaseA.open()
        assertEquals(AssistantActionResult.Silent, withTimeout(5_000) { a.await() })

        // The Save arrives while B is still inside present: it is held and applied once B is save-pending.
        h.aliasSaved(seen[1].proposalId)
        releaseB.open()
        assertTrue(withTimeout(5_000) { b.await() } is AssistantActionResult.AliasPresented)
        assertEquals(LedgerState.SAVED, state(1))
        assertTrue(ledger.pendingReport()!!.line.contains("\"status\":\"saved\""))
        assertEquals(0, h.aliasRegistrations)
    }

    // --- R2: a failed reset write must not keep the old report ---

    @Test
    fun `after a failed reset write the next turn carries no old result and the next write clears the file`() = runBlocking {
        val h = handler(Fake())
        spoken(h.handle(input(1)))
        assertTrue(h.forTurn(key(2)).contains("bydmate.result"))
        blockTempFile()
        h.onConversationReset()
        assertFalse("suppressed at once", h.forTurn(key(3)).contains("bydmate.result"))
        unblockTempFile()
        spoken(h.handle(input(2)))
        assertEquals(prop(2), newLedger().pendingReport()!!.proposalId)
    }

    // --- R3: acknowledgements and offers are bound to the report they were made for ---

    /** Pauses the named thread once at [label] until [resume]; every other caller passes straight through. */
    private fun LedgerAssistantActionHandler.pauseAt(label: String, threadName: String, paused: Gate, resume: Gate) {
        reportHook = { at ->
            if (at == label && Thread.currentThread().name == threadName) {
                paused.open()
                resume.await()
            }
        }
    }

    private fun ackThread(h: LedgerAssistantActionHandler, name: String, turn: Int): Thread =
        thread(name = name) { h.onAdmitted(key(turn)) }.also { threads += it }

    private suspend fun joined(t: Thread) {
        t.join(5_000)
        assertFalse("thread ${t.name} must have finished", t.isAlive)
    }

    private suspend fun staleAckAfterForget(switchIdentity: Boolean) {
        val h = handler(Fake())
        spoken(h.handle(input(1)))
        assertTrue(h.forTurn(key(10)).contains("bydmate.result"))
        val paused = gate()
        val resume = gate()
        h.pauseAt("ack", "ack-A", paused, resume)
        val ackA = ackThread(h, "ack-A", 10)
        awaitGate(paused)

        h.onIdentityForgotten()
        if (switchIdentity) identity = "device-b"
        spoken(h.handle(input(1)))
        val bReport = ledger.pendingReport()!!
        assertTrue(h.forTurn(key(11)).contains("bydmate.result"))

        resume.open()
        joined(ackA)
        assertEquals("a stale ack must not clear the new settlement", bReport, ledger.pendingReport())
        h.onAdmitted(key(11))
        assertNull("the newer offer survived the stale ack and consumes its own report", ledger.pendingReport())
    }

    @Test
    fun `a stale ack after a forget cannot clear the new settlement of the same ids`() = runBlocking {
        staleAckAfterForget(switchIdentity = false)
    }

    @Test
    fun `a stale ack after a forget under another identity cannot clear the new settlement`() = runBlocking {
        staleAckAfterForget(switchIdentity = true)
    }

    @Test
    fun `a stale ack cannot clear a same-id replacement written without a forget`() = runBlocking {
        val h = handler(Fake())
        spoken(h.handle(input(1)))
        h.forTurn(key(10))
        val paused = gate()
        val resume = gate()
        h.pauseAt("ack", "ack-A", paused, resume)
        val ackA = ackThread(h, "ack-A", 10)
        awaitGate(paused)
        ledger.setPendingReport(prop(1), "NEW same-id line")
        resume.open()
        joined(ackA)
        assertEquals(PendingReport(prop(1), "NEW same-id line"), ledger.pendingReport())
    }

    @Test
    fun `a stale ack leaves a report that another proposal replaced`() = runBlocking {
        val h = handler(Fake())
        spoken(h.handle(input(1)))
        h.forTurn(key(10))
        val paused = gate()
        val resume = gate()
        h.pauseAt("ack", "ack-A", paused, resume)
        val ackA = ackThread(h, "ack-A", 10)
        awaitGate(paused)
        spoken(h.handle(input(3)))
        resume.open()
        joined(ackA)
        assertEquals(prop(3), ledger.pendingReport()!!.proposalId)
    }

    @Test
    fun `a stale forTurn cannot overwrite a newer offer`() = runBlocking {
        val h = handler(Fake())
        spoken(h.handle(input(1)))
        val paused = gate()
        val resume = gate()
        h.pauseAt("offered", "offer-1", paused, resume)
        val older = thread(name = "offer-1") { h.forTurn(key(10)) }.also { threads += it }
        awaitGate(paused)
        assertTrue(h.forTurn(key(11)).contains("bydmate.result"))
        resume.open()
        joined(older)
        h.onAdmitted(key(10))
        assertNotNull("the older turn's ack is not the current offer", ledger.pendingReport())
        h.onAdmitted(key(11))
        assertNull("the newer offer was kept and consumes the report", ledger.pendingReport())
    }

    @Test
    fun `an ack of an older offer leaves the report for the newer offer, which delivers it at least once`() = runBlocking {
        val h = handler(Fake())
        spoken(h.handle(input(1)))
        h.forTurn(key(10))
        val paused = gate()
        val resume = gate()
        h.pauseAt("ack", "ack-A", paused, resume)
        val ackA = ackThread(h, "ack-A", 10)
        awaitGate(paused)
        assertTrue(h.forTurn(key(11)).contains("bydmate.result"))
        resume.open()
        joined(ackA)
        assertNotNull("never lost: the newer offer still owns it", ledger.pendingReport())
        h.onAdmitted(key(11))
        assertNull(ledger.pendingReport())
    }

    // --- persistence and retention ---

    @Test
    fun `a restart after dispatching marks the turn uncertain and a replay is silent`() = runBlocking {
        val dispatching = CompletableDeferred<Unit>()
        val stuck = Fake { _, hooks ->
            hooks.beforeDispatch()
            dispatching.complete(Unit)
            awaitCancellation()
        }
        val caller = launch { handler(stuck).handle(input(1)) }
        withTimeout(5_000) { dispatching.await() }

        val restarted = newLedger()
        assertEquals(LedgerState.UNCERTAIN, restarted.entry(key(1))!!.state)
        val fake = Fake()
        assertEquals(AssistantActionResult.Silent, handler(fake, with = restarted).handle(input(1)))
        assertEquals(0, fake.executes.get())
        scope.cancel()
        caller.join()
    }

    @Test
    fun `entries survive a new handler on the same directory`() = runBlocking {
        spoken(handler(Fake()).handle(input(1)))
        val fake = Fake()
        val again = handler(fake, with = newLedger())
        assertEquals(AssistantActionResult.Silent, again.handle(input(1)))
        assertEquals(AssistantActionResult.Silent, again.handle(input(2, envelope(2, proposalId = prop(1)))))
        assertEquals(0, fake.executes.get())
    }

    // --- attachments and lifecycle ---

    @Test
    fun `forTurn carries the manifest, the instruction and the pending result until admitted`() = runBlocking {
        val h = handler(Fake())
        spoken(h.handle(input(1)))
        val text = h.forTurn(key(2))
        assertTrue(text.contains("${OpenClawActionProtocol.MANIFEST_SCHEMA} version=${registry.version}"))
        assertTrue(text.contains(OpenClawActionProtocol.ACTION_INSTRUCTION))
        assertTrue(text.contains("bydmate.result"))
        assertTrue(text.contains("\"proposalId\":\"${prop(1)}\""))
        assertTrue(h.forTurn(key(2)).contains("bydmate.result"))

        spoken(h.handle(input(3)))
        h.onAdmitted(key(2))
        assertEquals(prop(3), ledger.pendingReport()!!.proposalId)

        h.onAdmitted(key(9))
        assertNotNull(ledger.pendingReport())
        val second = h.forTurn(key(4))
        h.onAdmitted(key(4))
        assertTrue(second.contains(prop(3)))
        assertNull(ledger.pendingReport())
        assertFalse(h.forTurn(key(5)).contains("bydmate.result"))
    }

    @Test
    fun `a conversation reset drops the report but keeps the entries, a forget clears both`() = runBlocking {
        val h = handler(Fake())
        spoken(h.handle(input(1)))
        h.onConversationReset()
        assertNull(ledger.pendingReport())
        assertNotNull(ledger.entry(key(1)))
        spoken(h.handle(input(2)))
        h.onIdentityForgotten()
        assertNull(ledger.pendingReport())
        assertNull(ledger.entry(key(1)))
        assertNull(ledger.entry(key(2)))
    }

    // --- alias proposals ---

    private fun alias(n: Int) = envelope(n, type = "alias_proposal", phrase = "do domu", action = """{"kind":"navigate","shortcut":"home"}""")

    @Test
    fun `an alias proposal with no bound sink is refused and nothing executes`() = runBlocking {
        val holder = AliasProposalSinkHolder()
        val fake = Fake()
        val h = handler(fake, holder)
        val result = spoken(h.handle(input(1, alias(1))))
        assertEquals(PolishActionText.ALIAS_UNAVAILABLE, result.line)
        assertFalse(holder.bound)
        assertEquals("ALIAS_UNAVAILABLE", ledger.entry(key(1))!!.reason)
        assertEquals(LedgerState.REJECTED, state(1))
        assertTrue(ledger.pendingReport()!!.line.contains("\"reason\":\"alias_unavailable\""))
        assertEquals(0, fake.executes.get())
    }

    @Test
    fun `a presented alias waits for the driver, then is saved once`() = runBlocking {
        val seen = mutableListOf<AliasProposal>()
        val fake = Fake()
        val h = handler(fake, AliasProposalSink { seen += it; true })
        val result = h.handle(input(1, alias(1)))
        assertTrue(result is AssistantActionResult.AliasPresented)
        val proposal = (result as AssistantActionResult.AliasPresented).proposal
        assertEquals(proposal, seen.single())
        assertEquals(AliasProposal(tag(1), proposal.proposalId, "do domu", proposal.action, "Nawiguj do domu"), proposal)
        assertNotEquals("the dialog id is an opaque presentation id, not the protocol id", prop(1), proposal.proposalId)
        assertEquals(LedgerState.SAVE_PENDING, state(1))

        h.aliasSaved("unknown-proposal")
        h.aliasSaved(prop(1))
        assertEquals("the protocol id names no presentation", LedgerState.SAVE_PENDING, state(1))
        h.aliasSaved(proposal.proposalId)
        assertEquals(LedgerState.SAVED, state(1))
        val line = ledger.pendingReport()!!.line
        assertTrue(line, line.contains("\"status\":\"saved\""))
        assertTrue("protocol reports keep the original proposal id: $line", line.contains("\"proposalId\":\"${prop(1)}\""))
        assertEquals(prop(1), ledger.pendingReport()!!.proposalId)
        assertEquals(0, h.aliasRegistrations)
        h.aliasDismissed(proposal.proposalId)
        assertEquals(LedgerState.SAVED, state(1))
        assertEquals(AssistantActionResult.Silent, h.handle(input(1, alias(1))))
        assertEquals(0, fake.executes.get())
        assertEquals(1, seen.size)
    }

    @Test
    fun `a dismissed alias is reported as dismissed`() = runBlocking {
        val seen = CopyOnWriteArrayList<AliasProposal>()
        val h = handler(Fake(), AliasProposalSink { seen += it; true })
        h.handle(input(1, alias(1)))
        h.aliasDismissed(seen.single().proposalId)
        assertEquals(LedgerState.DISMISSED, state(1))
        assertTrue(ledger.pendingReport()!!.line.contains("\"status\":\"dismissed\""))
    }

    @Test
    fun `a callback during present is applied after the entry is save-pending`() = runBlocking {
        lateinit var h: LedgerAssistantActionHandler
        h = handler(Fake(), AliasProposalSink { h.aliasSaved(it.proposalId); true })
        assertTrue(h.handle(input(1, alias(1))) is AssistantActionResult.AliasPresented)
        assertEquals(LedgerState.SAVED, state(1))
        assertTrue(ledger.pendingReport()!!.line.contains("\"status\":\"saved\""))

        lateinit var d: LedgerAssistantActionHandler
        d = handler(Fake(), AliasProposalSink { d.aliasDismissed(it.proposalId); true })
        assertTrue(d.handle(input(2, alias(2))) is AssistantActionResult.AliasPresented)
        assertEquals(LedgerState.DISMISSED, state(2))
    }

    @Test
    fun `a callback during present is discarded when the sink then refuses`() = runBlocking {
        lateinit var h: LedgerAssistantActionHandler
        var presented = ""
        h = handler(Fake(), AliasProposalSink { presented = it.proposalId; h.aliasSaved(it.proposalId); false })
        val result = spoken(h.handle(input(1, alias(1))))
        assertEquals(PolishActionText.ALIAS_UNAVAILABLE, result.line)
        assertEquals(LedgerState.REJECTED, state(1))
        assertEquals("ALIAS_UNAVAILABLE", ledger.entry(key(1))!!.reason)
        assertFalse(ledger.pendingReport()!!.line.contains("saved"))
        h.aliasSaved(presented)
        assertEquals(LedgerState.REJECTED, state(1))
        assertEquals("a refused presentation leaves no slot or ticket", 0, h.aliasRegistrations)
    }
}
