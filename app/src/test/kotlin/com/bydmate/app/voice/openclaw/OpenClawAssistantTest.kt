package com.bydmate.app.voice.openclaw

import com.bydmate.app.voice.AssistantReply
import com.bydmate.app.voice.PolishVoiceReplies.Problem
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.bydmate.app.agent.ActionRejection

/** The text-only assistant behind the Polish profile and the settings connection test. */
@Suppress("LargeClass") // Opt-in, pairing and lifecycle scenarios share the assistant's fake preferences, credentials and chat.
class OpenClawAssistantTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Lifecycle : AssistantActionLifecycle {
        val optOuts = AtomicInteger()
        val resets = AtomicInteger()
        val forgets = AtomicInteger()
        override suspend fun onOptOut() { optOuts.incrementAndGet() }
        override fun onConversationReset() { resets.incrementAndGet() }
        override fun onIdentityForgotten() { forgets.incrementAndGet() }
    }

    private val fence = "```"
    private fun envelopeJson(requestId: String = "req-1") =
        """{"schema":"bydmate.action/1","requestId":"$requestId","proposalId":"prop-12345678","capabilities":"0123456789ab","type":"action","action":{"kind":"vehicle","id":"windows_close"}}"""
    private fun blockReply(prose: String = "Zamykam okna.") = "$prose\n${fence}bydmate-action\n${envelopeJson()}\n$fence"

    private class Prefs(var on: Boolean = false, var endpoint: String = "") : OpenClawPrefs {
        var readEnabled: (() -> Boolean)? = null
        override fun isEnabled() = readEnabled?.invoke() ?: on
        override fun setEnabled(enabled: Boolean) { on = enabled }
        override fun endpointText() = endpoint
        override fun setEndpointText(text: String) { endpoint = text.trim() }
    }

    private class Credentials(var stored: Boolean = false) : OpenClawCredentials {
        var clears = 0
        var clearFailure: RuntimeException? = null
        override fun exist() = stored
        override fun clear() {
            stored = false
            clears++
            clearFailure?.let { throw it }
        }
    }

    private class FakeChat : OpenClawChat {
        val link = MutableStateFlow<OpenClawLinkState>(OpenClawLinkState.Unpaired)
        override val linkState: StateFlow<OpenClawLinkState> = link
        val asked = mutableListOf<String>()
        val plainAsked = mutableListOf<String>()
        val actionAsked = mutableListOf<String>()
        val resends = AtomicInteger()
        val cancels = AtomicInteger()
        val connectionCancels = AtomicInteger()
        val connects = AtomicInteger()
        val pairedConnects = AtomicInteger()
        var cancelScript: suspend () -> Unit = {}
        var connectionCancelScript: suspend () -> Unit = {}
        var connectScript: suspend () -> OpenClawLinkState = { OpenClawLinkState.Ready }
        val disconnects = AtomicInteger()
        val resets = AtomicInteger()
        @Volatile var script: suspend (String) -> OpenClawTurnResult = { OpenClawTurnResult.Answer("Cześć, tu Bob.") }
        /** Like the real client's lifecycle generation: connect captures it at entry, invalidation bumps it. */
        val operation = AtomicLong()
        var disconnectScript: suspend () -> Unit = {}
        override suspend fun connect(): OpenClawLinkState {
            val captured = operation.get()
            connects.incrementAndGet()
            val result = connectScript()
            return if (operation.get() != captured) OpenClawLinkState.Disconnected else result
        }
        override suspend fun connectPaired(): OpenClawLinkState {
            pairedConnects.incrementAndGet()
            return connect()
        }
        override suspend fun pair() = OpenClawLinkState.AwaitingApproval("req-1")
        override suspend fun ask(text: String, onDelta: (String) -> Unit): OpenClawTurnResult {
            asked += text
            plainAsked += text
            return script(text)
        }
        override suspend fun askForActions(text: String, onDelta: (String) -> Unit): OpenClawTurnResult {
            asked += text
            actionAsked += text
            return script(text)
        }
        override suspend fun resendUncertain(onDelta: (String) -> Unit): OpenClawTurnResult {
            resends.incrementAndGet()
            return script("resend")
        }
        override suspend fun cancel() { cancels.incrementAndGet(); cancelScript() }
        override suspend fun cancelConnectionOperations() {
            operation.incrementAndGet()
            connectionCancels.incrementAndGet()
            connectionCancelScript()
        }
        var onReset: () -> Unit = {}
        override suspend fun resetConversation() { resets.incrementAndGet(); onReset() }
        override suspend fun disconnect() {
            operation.incrementAndGet()
            disconnects.incrementAndGet()
            disconnectScript()
        }
    }

    private class Rig(prefs: Prefs, val credentials: Credentials = Credentials(), val lifecycle: Lifecycle = Lifecycle()) {
        val chat = FakeChat()
        val built = mutableListOf<String>()
        val assistant = OpenClawAssistant(
            prefs, { endpoint -> built += endpoint.host; chat }, credentials, actionLifecycle = lifecycle,
        )
    }

    private val goodEndpoint = "wss://gateway.example.ts.net/"

    private fun problem(reply: AssistantReply) = (reply as AssistantReply.Problem)

    @Test fun `with the opt-in off nothing is built or sent`() = runBlocking {
        val r = Rig(Prefs(on = false, endpoint = goodEndpoint))
        val reply = problem(r.assistant.ask("Cześć"))

        assertEquals(Problem.AI_DISABLED, reply.problem)
        assertEquals(emptyList<String>(), r.built)
        assertEquals(emptyList<String>(), r.chat.asked)
    }

    @Test fun `turning on the Russian agent is not a thing this class knows about, the default is off`() {
        assertFalse(Prefs().isEnabled())
        assertFalse(OpenClawAssistant(Prefs(), { error("no client") }, Credentials()).isEnabled())
    }

    @Test fun `an enabled assistant without a valid private address sends nothing`() = runBlocking {
        for (bad in listOf("", "https://gateway.example.ts.net/", "ws://gateway.example.ts.net/", "wss://user:pw@host/", "nonsense")) {
            val r = Rig(Prefs(on = true, endpoint = bad))
            val reply = problem(r.assistant.ask("Cześć"))
            assertEquals(bad, Problem.ASSISTANT_UNAVAILABLE, reply.problem)
            assertEquals(bad, emptyList<String>(), r.built)
        }
    }

    @Test fun `automatic reconnect skips disabled invalid and unpaired assistants without building a client`() = runBlocking {
        val disabled = Rig(Prefs(on = false, endpoint = goodEndpoint), Credentials(stored = true))
        assertEquals(
            AutomaticReconnectResult.Skipped(AutomaticReconnectSkipReason.Disabled),
            disabled.assistant.reconnectIfEligible(),
        )
        assertEquals(emptyList<String>(), disabled.built)
        assertEquals(0, disabled.chat.connects.get())

        val invalid = Rig(Prefs(on = true, endpoint = "https://gateway.example.ts.net/"), Credentials(stored = true))
        assertEquals(
            AutomaticReconnectResult.Skipped(AutomaticReconnectSkipReason.InvalidEndpoint),
            invalid.assistant.reconnectIfEligible(),
        )
        assertEquals(emptyList<String>(), invalid.built)
        assertEquals(0, invalid.chat.connects.get())

        val unpaired = Rig(Prefs(on = true, endpoint = goodEndpoint))
        assertEquals(
            AutomaticReconnectResult.Skipped(AutomaticReconnectSkipReason.Unpaired),
            unpaired.assistant.reconnectIfEligible(),
        )
        assertEquals(emptyList<String>(), unpaired.built)
        assertEquals(0, unpaired.chat.connects.get())
    }

    @Test fun `automatic reconnect skips approval and revoked links without connecting`() = runBlocking {
        for (link in listOf(OpenClawLinkState.AwaitingApproval("req-1"), OpenClawLinkState.Revoked)) {
            val r = Rig(Prefs(on = true, endpoint = goodEndpoint), Credentials(stored = true))
            r.assistant.resetConversation() // Build the paired client so its current link is observable.
            r.chat.link.value = link

            val result = r.assistant.reconnectIfEligible()

            assertTrue(result is AutomaticReconnectResult.Skipped)
            assertEquals(0, r.chat.connects.get())
        }
    }

    @Test fun `automatic reconnect connects a paired enabled disconnected assistant once`() = runBlocking {
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint), Credentials(stored = true))

        val result = withTimeout(2_000) { r.assistant.reconnectIfEligible() }

        assertEquals(AutomaticReconnectResult.Attempted(OpenClawLinkState.Ready), result)
        assertEquals(listOf("gateway.example.ts.net"), r.built)
        assertEquals(1, r.chat.connects.get())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `opt-out cancels a pending automatic reconnect without forgetting the pairing`() = runTest {
        val credentials = Credentials(stored = true)
        val chat = FakeChat()
        val connectEntered = CompletableDeferred<Unit>()
        val releaseConnect = CompletableDeferred<Unit>()
        chat.connectScript = {
            connectEntered.complete(Unit)
            releaseConnect.await()
            OpenClawLinkState.Disconnected
        }
        chat.connectionCancelScript = { releaseConnect.complete(Unit) }
        val assistant = OpenClawAssistant(
            Prefs(on = true, endpoint = goodEndpoint), { chat }, credentials,
            turnDispatcher = StandardTestDispatcher(testScheduler),
        )
        val reconnecting = async { assistant.reconnectIfEligible() }
        try {
            runCurrent()
            assertTrue(connectEntered.isCompleted)

            assistant.setEnabled(false)
            runCurrent()

            assertEquals(1, chat.connectionCancels.get())
            assertEquals(0, credentials.clears)
            assertTrue(reconnecting.isCompleted)
        } finally {
            releaseConnect.complete(Unit)
            reconnecting.cancelAndJoin()
        }
    }

    /**
     * Opt-out or forget lands after admission passed but before the transport call starts. The
     * trigger runs from a preference read, which the old code did after releasing its mutex.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private class Gap(val scope: TestScope, val trigger: (Gap) -> Unit) {
        val prefs = Prefs(on = true, endpoint = "wss://gateway.example.ts.net/")
        val credentials = Credentials(stored = true)
        val chat = FakeChat()
        val gate = CompletableDeferred<Unit>()
        private var armed = false
        private var fired = false
        val assistant: OpenClawAssistant

        init {
            chat.connectScript = { gate.await(); OpenClawLinkState.Ready }
            chat.connectionCancelScript = { gate.complete(Unit) }
            chat.disconnectScript = { gate.complete(Unit) }
            prefs.readEnabled = {
                if (armed && !fired) {
                    fired = true
                    trigger(this)
                }
                prefs.on
            }
            assistant = OpenClawAssistant(
                prefs, { armed = true; chat }, credentials,
                turnDispatcher = StandardTestDispatcher(scope.testScheduler),
            )
        }
    }

    private fun TestScope.optOutInGap() = Gap(this) { gap ->
        launch(start = CoroutineStart.UNDISPATCHED) { gap.assistant.setEnabled(false) }
    }

    private fun TestScope.forgetInGap() = Gap(this) { gap ->
        launch(start = CoroutineStart.UNDISPATCHED) { gap.assistant.disconnect() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `opt-out between admission and transport entry stops the automatic connect and keeps pairing`() = runTest {
        val gap = optOutInGap()

        val result = withTimeout(5_000) { gap.assistant.reconnectIfEligible() }
        runCurrent()

        assertEquals(AutomaticReconnectResult.Attempted(OpenClawLinkState.Disconnected), result)
        assertFalse(gap.prefs.on)
        assertEquals(1, gap.chat.connectionCancels.get())
        assertEquals(0, gap.credentials.clears)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `forget between admission and transport entry cannot start an unpaired connect`() = runTest {
        val gap = forgetInGap()

        val result = withTimeout(5_000) { gap.assistant.reconnectIfEligible() }
        runCurrent()

        assertEquals(AutomaticReconnectResult.Attempted(OpenClawLinkState.Disconnected), result)
        assertEquals(1, gap.chat.disconnects.get())
        assertFalse(gap.credentials.stored)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `opt-out between the manual check and transport entry also stops the connect`() = runTest {
        val gap = optOutInGap()

        val result = withTimeout(5_000) { gap.assistant.reconnect() }
        runCurrent()

        assertEquals(OpenClawLinkState.Disconnected, result)
        assertFalse(gap.prefs.on)
        assertEquals(0, gap.credentials.clears)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `forget between the manual check and transport entry also stops the connect`() = runTest {
        val gap = forgetInGap()

        val result = withTimeout(5_000) { gap.assistant.reconnect() }
        runCurrent()

        assertEquals(OpenClawLinkState.Disconnected, result)
        assertFalse(gap.credentials.stored)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `cancelling the caller of an automatic reconnect cancels its transport call`() = runTest {
        val chat = FakeChat()
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        chat.connectScript = {
            try {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
                OpenClawLinkState.Ready
            } finally {
                cancelled = true
            }
        }
        val assistant = OpenClawAssistant(
            Prefs(on = true, endpoint = goodEndpoint), { chat }, Credentials(stored = true),
            turnDispatcher = StandardTestDispatcher(testScheduler),
        )
        val reconnecting = async { assistant.reconnectIfEligible() }
        runCurrent()
        assertTrue(entered.isCompleted)

        reconnecting.cancelAndJoin()

        assertTrue(cancelled)
    }

    @Test fun `an admitted automatic reconnect runs its hook once before the connect`() = runBlocking {
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint), Credentials(stored = true))
        val events = mutableListOf<String>()
        r.chat.connectScript = { events += "connect"; OpenClawLinkState.Ready }

        val result = withTimeout(2_000) { r.assistant.reconnectIfEligible { events += "hook" } }

        assertEquals(AutomaticReconnectResult.Attempted(OpenClawLinkState.Ready), result)
        assertEquals(listOf("hook", "connect"), events)
    }

    @Test fun `automatic reconnect uses the paired-only connect and manual reconnect keeps the plain connect`() = runBlocking {
        val automatic = Rig(Prefs(on = true, endpoint = goodEndpoint), Credentials(stored = true))
        withTimeout(2_000) { automatic.assistant.reconnectIfEligible() }
        assertEquals(1, automatic.chat.pairedConnects.get())
        assertEquals(1, automatic.chat.connects.get())

        val manual = Rig(Prefs(on = true, endpoint = goodEndpoint), Credentials(stored = true))
        withTimeout(2_000) { manual.assistant.reconnect() }
        assertEquals(0, manual.chat.pairedConnects.get())
        assertEquals(1, manual.chat.connects.get())
    }

    @Test fun `a skipped automatic admission never runs the hook`() = runBlocking {
        val rigs = listOf(
            Rig(Prefs(on = false, endpoint = goodEndpoint), Credentials(stored = true)),
            Rig(Prefs(on = true, endpoint = "https://gateway.example.ts.net/"), Credentials(stored = true)),
            Rig(Prefs(on = true, endpoint = goodEndpoint)),
        )
        val approval = Rig(Prefs(on = true, endpoint = goodEndpoint), Credentials(stored = true))
        approval.assistant.resetConversation()
        approval.chat.link.value = OpenClawLinkState.AwaitingApproval("req-1")
        val revoked = Rig(Prefs(on = true, endpoint = goodEndpoint), Credentials(stored = true))
        revoked.assistant.resetConversation()
        revoked.chat.link.value = OpenClawLinkState.Revoked

        var hooks = 0
        for (r in rigs + approval + revoked) {
            val result = r.assistant.reconnectIfEligible { hooks++ }
            assertTrue(result is AutomaticReconnectResult.Skipped)
            assertEquals(0, r.chat.connects.get())
        }
        assertEquals(0, hooks)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `opt-out during the hook does not bypass the fresh generation check`() = runTest {
        val credentials = Credentials(stored = true)
        val chat = FakeChat()
        val assistant = OpenClawAssistant(
            Prefs(on = true, endpoint = goodEndpoint), { chat }, credentials,
            turnDispatcher = StandardTestDispatcher(testScheduler),
        )

        // The hook runs outside the mutex: the opt-out completes inline, before the connect would start.
        val result = assistant.reconnectIfEligible {
            launch(start = CoroutineStart.UNDISPATCHED) { assistant.setEnabled(false) }
        }

        assertEquals(AutomaticReconnectResult.Skipped(AutomaticReconnectSkipReason.IdentityChanged), result)
        assertEquals(0, chat.connects.get())
        assertEquals(0, credentials.clears)
    }

    // Coroutine stack recovery may rethrow a copy whose cause chain holds the original failure.
    private fun Throwable?.isOrWraps(original: Throwable): Boolean = generateSequence(this) { it.cause }.any { it === original }

    // --- status watcher ordering ---------------------------------------------------------------------

    /**
     * A live link emission is in the watcher's hands: it has passed the "still the current client"
     * check and is about to publish. [interleave] runs at that point, from a preference read.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private class Watcher(val scope: TestScope, stored: Boolean, val interleave: (Watcher) -> Unit) {
        val prefs = Prefs(on = true, endpoint = "wss://gateway.example.ts.net/")
        val credentials = Credentials(stored = stored)
        val chat = FakeChat()
        private var armed = false
        val assistant = OpenClawAssistant(
            prefs, { chat }, credentials,
            turnDispatcher = StandardTestDispatcher(scope.testScheduler),
        )

        suspend fun emitReadyIntoTheRace() {
            assistant.resetConversation() // builds the client and starts its status watcher
            scope.runCurrent()
            prefs.readEnabled = {
                if (armed) {
                    armed = false
                    interleave(this)
                }
                prefs.on
            }
            armed = true
            chat.link.value = OpenClawLinkState.Ready
            scope.runCurrent()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a forget between the watcher check and its publish is not overwritten by the old Ready`() = runTest {
        val w = Watcher(this, stored = true) { w ->
            launch(start = CoroutineStart.UNDISPATCHED) { w.assistant.disconnect() }
        }

        w.emitReadyIntoTheRace()
        runCurrent()

        assertEquals(OpenClawLinkState.Unpaired, w.assistant.status.value.link)
        assertEquals(1, w.credentials.clears)
        assertEquals(1, w.chat.disconnects.get())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `an endpoint change between the watcher check and its publish is not overwritten by the old Ready`() = runTest {
        val w = Watcher(this, stored = false) { w ->
            launch(start = CoroutineStart.UNDISPATCHED) { w.assistant.setEndpoint("wss://other.example.ts.net/") }
        }

        w.emitReadyIntoTheRace()
        runCurrent()

        assertEquals(OpenClawLinkState.Unpaired, w.assistant.status.value.link)
        assertEquals("wss://other.example.ts.net/", w.assistant.status.value.endpoint)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a detached client cannot publish after a completed forget`() = runTest {
        val w = Watcher(this, stored = true) { }
        w.assistant.resetConversation()
        runCurrent()

        w.assistant.disconnect()
        w.chat.link.value = OpenClawLinkState.Ready
        runCurrent()

        assertEquals(OpenClawLinkState.Unpaired, w.assistant.status.value.link)
    }

    // --- status refresh ordering ---------------------------------------------------------------------

    /**
     * A forget that lands while a refresh is between reading the live link and publishing it. The
     * one-shot hook runs at the refresh's first preference read, after the link was captured.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private class RefreshRace(val scope: TestScope) {
        val prefs = Prefs(on = true, endpoint = "wss://gateway.example.ts.net/")
        val credentials = Credentials(stored = true)
        val chat = FakeChat()
        var armed = false
        val assistant: OpenClawAssistant

        init {
            prefs.readEnabled = {
                if (armed) {
                    armed = false
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { assistant.disconnect() }
                }
                prefs.on
            }
            assistant = OpenClawAssistant(
                prefs, { chat }, credentials,
                turnDispatcher = StandardTestDispatcher(scope.testScheduler),
            )
        }

        fun assertForgottenLast() {
            assertEquals(OpenClawLinkState.Unpaired, assistant.status.value.link)
            assertEquals(1, credentials.clears)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a forget during the refresh after an automatic reconnect is not overwritten by its Ready`() = runTest {
        val r = RefreshRace(this)
        r.chat.connectScript = { r.chat.link.value = OpenClawLinkState.Ready; r.armed = true; OpenClawLinkState.Ready }

        withTimeout(5_000) { r.assistant.reconnectIfEligible() }
        runCurrent()

        r.assertForgottenLast()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a forget during the refresh after a manual reconnect is not overwritten by its Ready`() = runTest {
        val r = RefreshRace(this)
        r.chat.connectScript = { r.chat.link.value = OpenClawLinkState.Ready; r.armed = true; OpenClawLinkState.Ready }

        withTimeout(5_000) { r.assistant.reconnect() }
        runCurrent()

        r.assertForgottenLast()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a forget during the refresh after opt-out is not overwritten by the old link`() = runTest {
        val r = RefreshRace(this)
        r.assistant.resetConversation() // builds the client
        r.chat.link.value = OpenClawLinkState.Disconnected
        runCurrent()
        r.chat.connectionCancelScript = { r.armed = true } // runs in the cleanup, before the trailing refresh

        r.assistant.setEnabled(false)
        runCurrent()

        r.assertForgottenLast()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a forget during the refresh after a local conversation reset is not overwritten`() = runTest {
        val r = RefreshRace(this)
        r.chat.link.value = OpenClawLinkState.Disconnected
        r.chat.onReset = { r.armed = true }

        r.assistant.resetConversation()
        runCurrent()

        r.assertForgottenLast()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a forget during the refresh after a turn is not overwritten by the old link`() = runTest {
        val r = RefreshRace(this)
        r.chat.link.value = OpenClawLinkState.Disconnected
        r.chat.script = { r.armed = true; OpenClawTurnResult.Answer("ok") }

        r.assistant.ask("Cześć")
        runCurrent()

        r.assertForgottenLast()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a forget during pairing still ends Unpaired`() = runTest {
        val r = RefreshRace(this)
        r.chat.link.value = OpenClawLinkState.Disconnected
        r.armed = true

        r.assistant.pair()
        runCurrent()

        r.assertForgottenLast()
    }

    // --- forgetting the device ----------------------------------------------------------------------

    /** A forget whose client cleanup waits on [gate]; later clients are fresh so a stray build is visible. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private class Forget(scope: TestScope, val credentials: Credentials = Credentials(stored = true)) {
        val prefs = Prefs(on = true, endpoint = "wss://gateway.example.ts.net/")
        val gate = CompletableDeferred<Unit>()
        val chats = mutableListOf<FakeChat>()
        val assistant = OpenClawAssistant(
            prefs,
            { FakeChat().also { chats += it; if (chats.size == 1) it.disconnectScript = { gate.await() } } },
            credentials,
            turnDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `admission and every client entry are blocked while a forget runs`() = runTest {
        val f = Forget(this)
        f.assistant.resetConversation() // builds the one client that the forget will tear down
        val forgetting = async { f.assistant.disconnect() }
        runCurrent()
        assertEquals(1, f.chats.size)

        val automatic = f.assistant.reconnectIfEligible { error("a skipped admission must not run the hook") }
        f.assistant.reconnect()
        f.assistant.pair()
        f.assistant.resetConversation()

        assertEquals(AutomaticReconnectResult.Skipped(AutomaticReconnectSkipReason.Unpaired), automatic)
        assertEquals("no client may be built during the forget", 1, f.chats.size)
        assertEquals(0, f.credentials.clears)

        f.gate.complete(Unit)
        forgetting.await()
        assertEquals(1, f.credentials.clears)
        assertEquals(OpenClawLinkState.Unpaired, f.assistant.status.value.link)
        assertEquals(1, f.chats.size)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a cancelled forget still clears the credentials and releases the barrier`() = runTest {
        val f = Forget(this)
        f.assistant.resetConversation()
        val forgetting = async { f.assistant.disconnect() }
        runCurrent()

        forgetting.cancel()
        runCurrent()
        f.gate.complete(Unit)
        runCurrent()
        forgetting.join()

        assertEquals(1, f.credentials.clears)
        assertEquals(OpenClawLinkState.Unpaired, f.assistant.status.value.link)
        // The barrier is gone: a later pairing is admitted again.
        f.credentials.stored = true
        val result = withTimeout(5_000) { f.assistant.reconnectIfEligible() }
        assertTrue(result is AutomaticReconnectResult.Attempted)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a failing client disconnect still clears credentials publishes the status and releases the barrier`() = runTest {
        val f = Forget(this)
        f.assistant.resetConversation()
        val boom = IllegalStateException("disconnect failed")
        f.chats.single().disconnectScript = { throw boom }

        val thrown = runCatching { f.assistant.disconnect() }.exceptionOrNull()

        assertTrue("the original failure is preserved", thrown.isOrWraps(boom))
        assertEquals(1, f.credentials.clears)
        assertEquals(OpenClawLinkState.Unpaired, f.assistant.status.value.link)
        f.credentials.stored = true
        assertTrue(withTimeout(5_000) { f.assistant.reconnectIfEligible() } is AutomaticReconnectResult.Attempted)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a failing credential clear still publishes the status and keeps the first failure`() = runTest {
        val first = IllegalStateException("disconnect failed")
        val second = IllegalStateException("clear failed")
        val f = Forget(this)
        f.assistant.resetConversation()
        f.chats.single().disconnectScript = { throw first }
        f.credentials.clearFailure = second

        val thrown = runCatching { f.assistant.disconnect() }.exceptionOrNull()

        assertTrue(thrown.isOrWraps(first))
        assertTrue(first.suppressed.contains(second))
        assertEquals(OpenClawLinkState.Unpaired, f.assistant.status.value.link)
        f.credentials.clearFailure = null
        f.credentials.stored = true
        assertTrue(withTimeout(5_000) { f.assistant.reconnectIfEligible() } is AutomaticReconnectResult.Attempted)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a failing credential clear alone is reported after the status and barrier cleanup`() = runTest {
        val clearFailure = IllegalStateException("clear failed")
        val f = Forget(this)
        f.gate.complete(Unit)
        f.assistant.resetConversation()
        f.credentials.clearFailure = clearFailure

        val thrown = runCatching { f.assistant.disconnect() }.exceptionOrNull()

        assertTrue(thrown.isOrWraps(clearFailure))
        assertEquals(OpenClawLinkState.Unpaired, f.assistant.status.value.link)
        f.credentials.clearFailure = null
        f.credentials.stored = true
        assertTrue(withTimeout(5_000) { f.assistant.reconnectIfEligible() } is AutomaticReconnectResult.Attempted)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a failing status publication still releases the barrier`() = runTest {
        val f = Forget(this)
        f.gate.complete(Unit)
        f.assistant.resetConversation()
        val boom = IllegalStateException("snapshot failed")
        f.prefs.readEnabled = { throw boom }

        val thrown = runCatching { f.assistant.disconnect() }.exceptionOrNull()

        assertTrue(thrown.isOrWraps(boom))
        assertEquals(1, f.credentials.clears)
        f.prefs.readEnabled = null
        f.credentials.stored = true
        assertTrue(withTimeout(5_000) { f.assistant.reconnectIfEligible() } is AutomaticReconnectResult.Attempted)
    }

    @Test fun `an enabled assistant asks the one configured Gateway and returns its answer`() = runBlocking {
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint))
        val reply = r.assistant.ask("Cześć, kto tam?")

        assertEquals(AssistantReply.Answer("Cześć, tu Bob."), reply)
        assertEquals(listOf("gateway.example.ts.net"), r.built)
        assertEquals(listOf("Cześć, kto tam?"), r.chat.asked)
    }

    @Test fun `every transport outcome becomes a Polish problem, and none is retried`() = runBlocking {
        val cases = listOf(
            OpenClawTurnResult.NeedsApproval("r") to Problem.APPROVAL_REQUIRED,
            OpenClawTurnResult.Revoked to Problem.PAIRING_REQUIRED,
            OpenClawTurnResult.Failed(OpenClawFailure.NetworkUnavailable) to Problem.NO_CONNECTION,
            OpenClawTurnResult.Failed(OpenClawFailure.Timeout) to Problem.NO_CONNECTION,
            OpenClawTurnResult.Failed(OpenClawFailure.TlsRejected) to Problem.NO_CONNECTION,
            OpenClawTurnResult.Failed(OpenClawFailure.AuthRejected) to Problem.PAIRING_REQUIRED,
            OpenClawTurnResult.Failed(OpenClawFailure.ServerError) to Problem.ASSISTANT_UNAVAILABLE,
            OpenClawTurnResult.Failed(OpenClawFailure.ScopeMismatch) to Problem.ASSISTANT_UNAVAILABLE,
            OpenClawTurnResult.Failed(OpenClawFailure.RequestTooLarge) to Problem.ASSISTANT_UNAVAILABLE,
            OpenClawTurnResult.ResendTooLarge to Problem.ASSISTANT_UNAVAILABLE,
            OpenClawTurnResult.Uncertain to Problem.ASSISTANT_UNAVAILABLE,
        )
        for ((result, expected) in cases) {
            val r = Rig(Prefs(on = true, endpoint = goodEndpoint))
            r.chat.script = { result }
            val reply = problem(r.assistant.ask("Cześć"))
            assertEquals(result.toString(), expected, reply.problem)
            assertTrue(reply.detail, reply.detail.isNotBlank())
            assertEquals("asked once", 1, r.chat.asked.size)
            assertEquals(
                result.toString(),
                result is OpenClawTurnResult.Uncertain || result is OpenClawTurnResult.ResendTooLarge,
                reply.uncertain,
            )
        }
    }

    @Test fun `a first send that is too large is not uncertain but a too-large resend keeps the original unresolved`() = runBlocking {
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint))
        r.chat.script = { OpenClawTurnResult.Failed(OpenClawFailure.RequestTooLarge) }

        val first = problem(r.assistant.ask("Cześć"))
        assertFalse("nothing was sent, so nothing is unresolved", first.uncertain)
        assertEquals(PolishAssistantText.failure(OpenClawFailure.RequestTooLarge), first.detail)

        r.chat.script = { OpenClawTurnResult.ResendTooLarge }
        val retry = problem(r.assistant.resendUncertain())
        assertEquals(Problem.ASSISTANT_UNAVAILABLE, retry.problem)
        assertTrue("the original may have arrived, so the retry stays offered", retry.uncertain)
        assertEquals(PolishAssistantText.RESEND_TOO_LARGE, retry.detail)
        assertEquals("the retry was asked for once and not repeated", 1, r.chat.resends.get())
    }

    @Test fun `the too-large resend text says the retry was not sent and the original is unresolved`() {
        val retry = PolishAssistantText.RESEND_TOO_LARGE
        assertTrue(retry, retry.contains("Ponowienie nie zostało wysłane"))
        assertTrue(retry, retry.contains("Nadal nie wiadomo, czy asystent dostał pierwotne pytanie"))
        assertNotEquals(PolishAssistantText.UNCERTAIN, retry)
        val first = PolishAssistantText.failure(OpenClawFailure.RequestTooLarge)
        assertNotEquals(first, retry)
        assertTrue(first, first.contains("Nie została wysłana"))
        assertFalse("a first send has no original to doubt", first.contains("nie wiadomo"))
    }

    @Test fun `an aborted turn is silent`() = runBlocking {
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint))
        r.chat.script = { OpenClawTurnResult.Aborted }
        assertEquals(AssistantReply.Aborted, r.assistant.ask("Cześć"))
    }

    @Test fun `a caller that gives up aborts the turn on the Gateway`() = runBlocking {
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint))
        val never = CompletableDeferred<OpenClawTurnResult>()
        r.chat.script = { never.await() }
        val waiter = async { r.assistant.ask("Cześć") }
        withTimeout(2_000) { while (r.chat.asked.isEmpty()) delay(10) }

        waiter.cancelAndJoin()

        withTimeout(2_000) { while (r.chat.cancels.get() == 0) delay(10) }
        assertEquals(1, r.chat.cancels.get())
        never.complete(OpenClawTurnResult.Answer("Spóźniona odpowiedź"))
        Unit
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun optOutWinsQueuedTurn(resend: Boolean) = runTest {
        val prefs = Prefs(on = true, endpoint = goodEndpoint)
        val chat = FakeChat()
        val assistant = OpenClawAssistant(
            prefs, { chat }, Credentials(),
            turnDispatcher = StandardTestDispatcher(testScheduler),
        )
        // The caller reaches runTurn, but the transport coroutine cannot enter ask yet.
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                if (resend) assistant.resendUncertain() else assistant.ask("Cześć")
            }
        }
        try {
            assertTrue(chat.asked.isEmpty())
            assertEquals(0, chat.resends.get())

            assistant.setEnabled(false)
            runCurrent()

            assertFalse(prefs.on)
            assertEquals("disabled queued turn must not reach chat.ask", emptyList<String>(), chat.asked)
            assertEquals("disabled queued resend must not reach transport", 0, chat.resends.get())
            assertTrue("the invalidated caller must finish", caller.isCompleted)
            caller.await()
        } finally {
            caller.cancelAndJoin()
        }
    }

    @Test fun `opt-out invalidates an ask queued before transport admission`() = optOutWinsQueuedTurn(false)

    @Test fun `opt-out invalidates a resend queued before transport admission`() = optOutWinsQueuedTurn(true)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `opt-out aborts an admitted ask and finishes its caller`() = runTest {
        val prefs = Prefs(on = true, endpoint = goodEndpoint)
        val chat = FakeChat()
        val admitted = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<OpenClawTurnResult>()
        chat.script = {
            admitted.complete(Unit)
            try {
                finish.await()
            } finally {
                stopped.complete(Unit)
            }
        }
        val assistant = OpenClawAssistant(
            prefs, { chat }, Credentials(),
            turnDispatcher = StandardTestDispatcher(testScheduler),
        )
        val caller = async { runCatching { assistant.ask("Cześć") } }
        try {
            runCurrent()
            assertTrue("transport entered before opt-out", admitted.isCompleted)

            assistant.setEnabled(false)
            runCurrent()

            assertTrue("opt-out must send an abort to the admitted chat", chat.cancels.get() > 0)
            assertTrue("opt-out must cancel the admitted transport coroutine", stopped.isCompleted)
            assertTrue("the disabled caller must not wait for a remote response", caller.isCompleted)
            assertFalse(caller.await().getOrNull() is AssistantReply.Answer)
        } finally {
            // The old implementation leaves its detached turn alive. Always open the fake
            // response gate, so RED is an assertion failure rather than a leaked wait.
            finish.complete(OpenClawTurnResult.Aborted)
            runCurrent()
            caller.cancelAndJoin()
        }
    }

    @Test fun `the address cannot change while a device is paired`() = runBlocking {
        val credentials = Credentials(stored = true)
        val prefs = Prefs(on = true, endpoint = goodEndpoint)
        val r = Rig(prefs, credentials)

        assertFalse(r.assistant.setEndpoint("wss://other.example.ts.net/"))
        assertEquals(goodEndpoint, prefs.endpoint)
        assertTrue("same address is not a change", r.assistant.setEndpoint(goodEndpoint))

        r.assistant.disconnect()
        assertTrue(r.assistant.setEndpoint("wss://other.example.ts.net/"))
        assertEquals("wss://other.example.ts.net/", prefs.endpoint)
    }

    @Test fun `disconnect clears the stored pairing even when no client was ever built`() = runBlocking {
        val credentials = Credentials(stored = true)
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint), credentials)

        r.assistant.disconnect()

        assertEquals(1, credentials.clears)
        assertEquals(emptyList<String>(), r.built)
        assertEquals(OpenClawLinkState.Unpaired, r.assistant.status.value.link)
    }

    @Test fun `pairing needs the opt-in and an address, otherwise nothing connects`() = runBlocking {
        val off = Rig(Prefs(on = false, endpoint = goodEndpoint))
        off.assistant.pair()
        assertEquals(emptyList<String>(), off.built)

        val noAddress = Rig(Prefs(on = true, endpoint = ""))
        noAddress.assistant.pair()
        assertEquals(emptyList<String>(), noAddress.built)

        val ok = Rig(Prefs(on = true, endpoint = goodEndpoint))
        assertEquals(OpenClawLinkState.AwaitingApproval("req-1"), ok.assistant.pair())
        assertEquals(listOf("gateway.example.ts.net"), ok.built)
    }

    @Test fun `resetting the local conversation works while the opt-in is off`() = runBlocking {
        val r = Rig(Prefs(on = false, endpoint = goodEndpoint), Credentials(stored = true))

        r.assistant.resetConversation()

        assertEquals(1, r.chat.resets.get())
        assertEquals(0, r.chat.cancels.get())
        assertEquals(0, r.credentials.clears)
    }

    @Test fun `turning the opt-in off cancels a running turn but keeps the pairing`() = runBlocking {
        val credentials = Credentials(stored = true)
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint), credentials)
        r.assistant.ask("Cześć") // builds the client

        r.assistant.setEnabled(false)

        assertEquals(1, r.chat.cancels.get())
        assertEquals(0, credentials.clears)
        assertFalse(r.assistant.isEnabled())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `cancelled disable still aborts turns and connection operations`() = runTest {
        val prefs = Prefs(on = true, endpoint = goodEndpoint)
        val chat = FakeChat()
        val turnStopped = CompletableDeferred<Unit>()
        val abortEntered = CompletableDeferred<Unit>()
        val allowAbort = CompletableDeferred<Unit>()
        chat.script = {
            try { CompletableDeferred<OpenClawTurnResult>().await() }
            finally { turnStopped.complete(Unit) }
        }
        chat.cancelScript = { abortEntered.complete(Unit); allowAbort.await() }
        val assistant = OpenClawAssistant(prefs, { chat }, Credentials(), StandardTestDispatcher(testScheduler))
        val caller = async { assistant.ask("Cześć") }
        runCurrent()
        val disabling = async { assistant.setEnabled(false) }
        try {
            runCurrent()
            assertTrue(abortEntered.isCompleted)
            disabling.cancel()
            runCurrent()
            allowAbort.complete(Unit)
            runCurrent()

            assertFalse(prefs.on)
            assertTrue(turnStopped.isCompleted)
            assertEquals("connection cleanup must survive cancellation of disable", 1, chat.connectionCancels.get())
            assertTrue(caller.isCompleted)
        } finally {
            allowAbort.complete(Unit)
            disabling.cancelAndJoin()
            caller.cancelAndJoin()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `failed turn abort still cancels connection operations and local turn`() = runTest {
        val prefs = Prefs(on = true, endpoint = goodEndpoint)
        val chat = FakeChat()
        val stopped = CompletableDeferred<Unit>()
        chat.script = {
            try { CompletableDeferred<OpenClawTurnResult>().await() }
            finally { stopped.complete(Unit) }
        }
        val failure = IllegalStateException("abort failed")
        chat.cancelScript = { throw failure }
        val assistant = OpenClawAssistant(prefs, { chat }, Credentials(), StandardTestDispatcher(testScheduler))
        val caller = async { assistant.ask("Cześć") }
        try {
            runCurrent()
            val result = runCatching { assistant.setEnabled(false) }
            runCurrent()

            val thrown = result.exceptionOrNull()
            assertTrue(thrown is IllegalStateException)
            assertEquals(failure.message, thrown?.message)
            assertTrue(stopped.isCompleted)
            assertEquals("connection cleanup must run even if abort fails", 1, chat.connectionCancels.get())
            assertTrue(caller.isCompleted)
        } finally {
            caller.cancelAndJoin()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `failed status refresh after opt-out still cleans up admitted work`() = runTest {
        val prefs = Prefs(on = true, endpoint = goodEndpoint)
        val chat = FakeChat()
        val stopped = CompletableDeferred<Unit>()
        chat.script = {
            try { CompletableDeferred<OpenClawTurnResult>().await() }
            finally { stopped.complete(Unit) }
        }
        val assistant = OpenClawAssistant(prefs, { chat }, Credentials(), StandardTestDispatcher(testScheduler))
        val caller = async { runCatching { assistant.ask("Cześć") } }
        try {
            runCurrent()
            // Fail exactly the first status snapshot after the persisted switch changes.
            var failed = false
            prefs.readEnabled = {
                if (!prefs.on && !failed) {
                    failed = true
                    error("preference read failed")
                }
                prefs.on
            }
            val result = runCatching { assistant.setEnabled(false) }
            runCurrent()

            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertFalse(prefs.on)
            assertEquals(1, chat.cancels.get())
            assertEquals(1, chat.connectionCancels.get())
            assertTrue(stopped.isCompleted)
            assertTrue(caller.isCompleted)
        } finally {
            prefs.readEnabled = null
            caller.cancelAndJoin()
        }
    }

    @Test fun `the status follows the live link while pairing waits for approval`() = runBlocking {
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint))
        r.assistant.pair() // builds the client and starts following its link

        r.chat.link.value = OpenClawLinkState.AwaitingApproval("req-9")
        withTimeout(2_000) { while (r.assistant.status.value.link != OpenClawLinkState.AwaitingApproval("req-9")) delay(10) }
        r.chat.link.value = OpenClawLinkState.Ready
        withTimeout(2_000) { while (r.assistant.status.value.link != OpenClawLinkState.Ready) delay(10) }
    }

    @Test fun `status text is Polish for every state`() {
        fun text(enabled: Boolean, valid: Boolean, link: OpenClawLinkState) =
            PolishAssistantText.link(OpenClawStatus(enabled, "wss://x/", valid, link))
        assertEquals("Wyłączony", text(false, true, OpenClawLinkState.Ready))
        assertEquals("Podaj adres bramy (wss://…)", text(true, false, OpenClawLinkState.Ready))
        assertEquals("Połączono", text(true, true, OpenClawLinkState.Ready))
        assertEquals("Nie sparowano", text(true, true, OpenClawLinkState.Unpaired))
        assertTrue(text(true, true, OpenClawLinkState.AwaitingApproval(null)).contains("zatwierdzenie"))
        for (f in OpenClawFailure.values()) assertTrue(f.name, PolishAssistantText.failure(f).isNotBlank())
    }

    // --- Step 7b: action mapping, plain probe path and lifecycle ----------------------------------------

    @Test fun `ask sends an action turn and askText sends a plain one`() = runBlocking {
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint))

        r.assistant.ask("Zamknij okna")
        r.assistant.askText("Cześć")

        assertEquals(listOf("Zamknij okna"), r.chat.actionAsked)
        assertEquals(listOf("Cześć"), r.chat.plainAsked)
    }

    @Test fun `an action answer with a valid block maps to a proposal that keeps recovered and the turn key`() = runBlocking {
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint))
        r.chat.script = { OpenClawTurnResult.ActionAnswer(blockReply(), recovered = true, turnKey = "turn-7") }
        val before = System.currentTimeMillis()

        val reply = r.assistant.ask("Zamknij okna") as AssistantReply.Proposal

        assertEquals("Zamykam okna.", reply.prose)
        assertTrue(reply.recovered)
        assertEquals("turn-7", reply.turnKey)
        assertTrue(reply.startedAtMs in before..System.currentTimeMillis())
        val ok = reply.envelope as ParsedEnvelope.Ok
        assertEquals("prop-12345678", ok.envelope.proposalId)
        assertEquals("windows_close", ok.envelope.candidate.id)
    }

    @Test fun `a text-only action answer maps to a plain answer`() = runBlocking {
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint))
        r.chat.script = { OpenClawTurnResult.ActionAnswer("Jest 21 stopni.", recovered = false, turnKey = "turn-1") }

        assertEquals(AssistantReply.Answer("Jest 21 stopni."), r.assistant.ask("Ile stopni?"))
    }

    @Test fun `two action blocks map to a proposal with a malformed envelope`() = runBlocking {
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint))
        val twice = "Ok.\n${fence}bydmate-action\n${envelopeJson()}\n$fence\n${fence}bydmate-action\n${envelopeJson()}\n$fence"
        r.chat.script = { OpenClawTurnResult.ActionAnswer(twice, recovered = false, turnKey = "turn-2") }

        val reply = r.assistant.ask("Zamknij okna") as AssistantReply.Proposal

        assertEquals(ParsedEnvelope.Invalid(ActionRejection.MALFORMED), reply.envelope)
        assertEquals("turn-2", reply.turnKey)
    }

    @Test fun `askText never yields a proposal even when the transport returns an action answer`() = runBlocking {
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint))
        val text = blockReply()
        r.chat.script = { OpenClawTurnResult.ActionAnswer(text, recovered = false, turnKey = "turn-3") }

        assertEquals(AssistantReply.Answer(text), r.assistant.askText("Zamknij okna"))
    }

    @Test fun `opt-out calls onOptOut once and still cancels the running turn`() = runBlocking {
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint))
        val gate = CompletableDeferred<OpenClawTurnResult>()
        r.chat.script = { gate.await() }
        r.chat.cancelScript = { gate.complete(OpenClawTurnResult.Aborted) }
        val asking = async { r.assistant.ask("Cześć") }
        withTimeout(2_000) { while (r.chat.asked.isEmpty()) delay(10) }

        r.assistant.setEnabled(false)

        assertEquals(1, r.lifecycle.optOuts.get())
        assertEquals(1, r.chat.cancels.get())
        assertEquals(AssistantReply.Aborted, withTimeout(2_000) { asking.await() })
    }

    @Test fun `enabling does not call onOptOut`() = runBlocking {
        val r = Rig(Prefs(on = false, endpoint = goodEndpoint))
        r.assistant.setEnabled(true)
        assertEquals(0, r.lifecycle.optOuts.get())
    }

    @Test fun `resetConversation calls onConversationReset`() = runBlocking {
        val r = Rig(Prefs(on = true, endpoint = goodEndpoint))

        r.assistant.resetConversation()

        assertEquals(1, r.lifecycle.resets.get())
        assertEquals(1, r.chat.resets.get())
    }

    @Test fun `clearing the credentials calls onForget`() {
        val dir = File(tmp.root, "openclaw")
        var forgets = 0
        val credentials = StoredOpenClawCredentials(
            OpenClawIdentityStore(dir, FakeSecretCipher()), OpenClawConversationStore(dir), onForget = { forgets++ },
        )

        credentials.clear()

        assertEquals(1, forgets)
    }
}
