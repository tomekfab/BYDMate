package com.bydmate.app.voice.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@Suppress("LargeClass") // Durable transitions, ownership and report receipts share one disk-backed ledger fixture.
class ActionLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000_000L
    private var identity: String? = "device-a"

    private fun dir() = File(tmp.root, "openclaw")
    private fun ledger() = ActionLedger(dir(), { identity }, { clock })
    private fun ActionLedger.start(turn: String, proposal: String = "p-$turn") =
        assertEquals(BeginResult.Created, begin(turn, proposal))

    @Test
    fun `begin creates an accepted entry`() {
        val ledger = ledger()
        assertEquals(BeginResult.Created, ledger.begin("t1", "p1"))
        assertEquals(LedgerEntry("t1", "p1", LedgerState.ACCEPTED, null, clock), ledger.entry("t1"))
    }

    @Test
    fun `a repeated turn key is a duplicate in every state`() {
        val ledger = ledger()
        ledger.start("t1", "p1")
        assertEquals(BeginResult.Duplicate, ledger.begin("t1", "p1"))
        assertEquals(BeginResult.Duplicate, ledger.begin("t1", "other"))
        assertTrue(ledger.transition("t1", LedgerState.REJECTED, "x"))
        assertEquals(BeginResult.Duplicate, ledger.begin("t1", "p9"))
        assertEquals("p1", ledger.entry("t1")!!.proposalId)
    }

    @Test
    fun `a repeated proposal id under another turn is a duplicate`() {
        val ledger = ledger()
        ledger.start("t1", "p1")
        assertEquals(BeginResult.Duplicate, ledger.begin("t2", "p1"))
        assertNull(ledger.entry("t2"))
    }

    @Test
    fun `entryByProposal finds the entry`() {
        val ledger = ledger()
        ledger.start("t1", "p1")
        ledger.start("t2", "p2")
        assertEquals("t2", ledger.entryByProposal("p2")!!.turnKey)
        assertNull(ledger.entryByProposal("nope"))
    }

    @Test
    fun `legal edges are accepted and carry the reason and time`() {
        val ledger = ledger()
        ledger.start("t1")
        clock += 5
        assertTrue(ledger.transition("t1", LedgerState.CONFIRM_PENDING))
        assertTrue(ledger.transition("t1", LedgerState.DISPATCHING))
        clock += 5
        assertTrue(ledger.transition("t1", LedgerState.FAILED, "gate"))
        assertEquals(LedgerEntry("t1", "p-t1", LedgerState.FAILED, "gate", clock), ledger.entry("t1"))
    }

    @Test
    fun `illegal edges are refused and change nothing`() {
        val ledger = ledger()
        ledger.start("t1")
        assertFalse("ACCEPTED cannot finish", ledger.transition("t1", LedgerState.DONE))
        assertFalse(ledger.transition("t1", LedgerState.UNCERTAIN))
        assertFalse(ledger.transition("t1", LedgerState.SAVED))
        assertTrue(ledger.transition("t1", LedgerState.DISPATCHING))
        assertTrue(ledger.transition("t1", LedgerState.DONE))
        assertFalse("DONE is terminal", ledger.transition("t1", LedgerState.DISPATCHING))
        assertFalse(ledger.transition("t1", LedgerState.CANCELLED))
        assertEquals(LedgerState.DONE, ledger.entry("t1")!!.state)
        assertFalse("unknown turn", ledger.transition("missing", LedgerState.CANCELLED))
    }

    @Test
    fun `every terminal state accepts nothing`() {
        val terminal = listOf(
            LedgerState.DONE, LedgerState.FAILED, LedgerState.BLOCKED, LedgerState.REJECTED, LedgerState.CANCELLED,
            LedgerState.EXPIRED, LedgerState.UNCERTAIN, LedgerState.SAVED, LedgerState.DISMISSED,
        )
        val path = mapOf(
            LedgerState.DONE to listOf(LedgerState.DISPATCHING, LedgerState.DONE),
            LedgerState.FAILED to listOf(LedgerState.DISPATCHING, LedgerState.FAILED),
            LedgerState.BLOCKED to listOf(LedgerState.DISPATCHING, LedgerState.BLOCKED),
            LedgerState.UNCERTAIN to listOf(LedgerState.DISPATCHING, LedgerState.UNCERTAIN),
            LedgerState.REJECTED to listOf(LedgerState.REJECTED),
            LedgerState.CANCELLED to listOf(LedgerState.CANCELLED),
            LedgerState.EXPIRED to listOf(LedgerState.EXPIRED),
            LedgerState.SAVED to listOf(LedgerState.SAVE_PENDING, LedgerState.SAVED),
            LedgerState.DISMISSED to listOf(LedgerState.SAVE_PENDING, LedgerState.DISMISSED),
        )
        terminal.forEach { end ->
            val ledger = ledger()
            val turn = "t-$end"
            ledger.start(turn)
            path.getValue(end).forEach { assertTrue("$end via $it", ledger.transition(turn, it)) }
            LedgerState.values().forEach { to -> assertFalse("$end -> $to", ledger.transition(turn, to)) }
            assertEquals(end, ledger.entry(turn)!!.state)
        }
    }

    @Test
    fun `save pending edges follow the plan`() {
        val ledger = ledger()
        ledger.start("t1")
        assertTrue(ledger.transition("t1", LedgerState.SAVE_PENDING))
        assertFalse(ledger.transition("t1", LedgerState.DISPATCHING))
        assertTrue(ledger.transition("t1", LedgerState.CANCELLED))
    }

    @Test
    fun `a restart keeps entries and maps the unfinished states`() {
        val first = ledger()
        listOf("acc", "conf", "disp", "done", "save", "unc").forEach { first.start(it) }
        assertTrue(first.transition("conf", LedgerState.CONFIRM_PENDING))
        assertTrue(first.transition("disp", LedgerState.DISPATCHING))
        assertTrue(first.transition("done", LedgerState.DISPATCHING))
        assertTrue(first.transition("done", LedgerState.DONE, "ok"))
        assertTrue(first.transition("save", LedgerState.SAVE_PENDING))
        assertTrue(first.transition("unc", LedgerState.DISPATCHING))
        assertTrue(first.transition("unc", LedgerState.UNCERTAIN))

        val restarted = ledger()
        assertEquals(LedgerState.EXPIRED, restarted.entry("acc")!!.state)
        assertEquals(LedgerState.EXPIRED, restarted.entry("conf")!!.state)
        assertEquals(LedgerState.UNCERTAIN, restarted.entry("disp")!!.state)
        assertEquals(LedgerState.DONE, restarted.entry("done")!!.state)
        assertEquals("ok", restarted.entry("done")!!.reason)
        assertEquals(LedgerState.SAVE_PENDING, restarted.entry("save")!!.state)
        assertEquals(LedgerState.UNCERTAIN, restarted.entry("unc")!!.state)
        assertEquals("p-disp", restarted.entry("disp")!!.proposalId)
        assertEquals("disp", restarted.entryByProposal("p-disp")!!.turnKey)
        assertEquals(BeginResult.Duplicate, restarted.begin("disp", "fresh"))
        assertEquals(BeginResult.Duplicate, restarted.begin("new-turn", "p-done"))
    }

    @Test
    fun `an uncertain action stays uncertain after a second restart`() {
        val first = ledger()
        first.start("t1")
        assertTrue(first.transition("t1", LedgerState.DISPATCHING))
        assertEquals(LedgerState.UNCERTAIN, ledger().entry("t1")!!.state)
        assertEquals(LedgerState.UNCERTAIN, ledger().entry("t1")!!.state)
    }

    @Test
    fun `begin is unavailable when the directory cannot be created`() {
        val blocker = dir()
        blocker.parentFile!!.mkdirs()
        blocker.writeText("not a directory")
        val ledger = ledger()
        assertEquals(BeginResult.Unavailable, ledger.begin("t1", "p1"))
        assertNull(ledger.entry("t1"))
        assertTrue("still the blocking file", blocker.isFile)
        assertEquals(BeginResult.Unavailable, ledger.begin("t1", "p1"))
    }

    @Test
    fun `a failed write on begin returns unavailable and creates nothing`() {
        val ledger = ledger()
        blockTempFile()
        assertEquals(BeginResult.Unavailable, ledger.begin("t1", "p1"))
        assertNull(ledger.entry("t1"))
        assertNull(ledger.entryByProposal("p1"))
        assertFalse(File(dir(), ActionLedger.FILE_NAME).exists())
    }

    @Test
    fun `a failed write on transition returns false and leaves memory unchanged`() {
        val ledger = ledger()
        ledger.start("t1")
        blockTempFile()
        assertFalse(ledger.transition("t1", LedgerState.DISPATCHING, "x"))
        assertEquals(LedgerState.ACCEPTED, ledger.entry("t1")!!.state)
        assertNull(ledger.entry("t1")!!.reason)
        unblockTempFile()
        assertEquals("disk still holds ACCEPTED, which loads as EXPIRED", LedgerState.EXPIRED, ledger().entry("t1")!!.state)
        assertTrue(ledger.transition("t1", LedgerState.DISPATCHING))
    }

    @Test
    fun `a failed write on a report leaves the old report`() {
        val ledger = ledger()
        ledger.setPendingReport("p1", "first")
        blockTempFile()
        ledger.setPendingReport("p2", "second")
        assertEquals(PendingReport("p1", "first"), ledger.pendingReport())
        ledger.clearReport("p1")
        assertEquals(PendingReport("p1", "first"), ledger.pendingReport())
    }

    @Test
    fun `a new identity namespace discards the whole file`() {
        val first = ledger()
        first.start("t1")
        first.setPendingReport("p-t1", "line")
        identity = "device-b"
        val other = ledger()
        assertNull(other.entry("t1"))
        assertNull(other.pendingReport())
        assertEquals(BeginResult.Created, other.begin("t1", "p-t1"))
        identity = "device-a"
        assertNull("the old namespace is gone for good", ledger().entry("t1"))
    }

    @Test
    fun `the identity is checked on every operation`() {
        val ledger = ledger()
        ledger.start("t1")
        identity = "device-b"
        assertNull(ledger.entry("t1"))
        assertEquals(BeginResult.Created, ledger.begin("t1", "p-t1"))
        identity = "device-a"
        assertNull(ledger.entry("t1"))
    }

    @Test
    fun `a null identity makes the ledger unavailable and discards the file`() {
        val ledger = ledger()
        ledger.start("t1")
        ledger.setPendingReport("p-t1", "line")
        identity = null
        assertEquals(BeginResult.Unavailable, ledger.begin("t2", "p2"))
        assertNull(ledger.entry("t1"))
        assertFalse(ledger.transition("t1", LedgerState.DISPATCHING))
        assertNull(ledger.pendingReport())
        ledger.setPendingReport("p2", "line")
        assertFalse(File(dir(), ActionLedger.FILE_NAME).exists())
        identity = "device-a"
        assertNull(ledger().entry("t1"))
        assertNull(ledger().pendingReport())
    }

    @Test
    fun `a corrupt file is treated as empty`() {
        dir().mkdirs()
        File(dir(), ActionLedger.FILE_NAME).writeText("{not json")
        val ledger = ledger()
        assertNull(ledger.entry("t1"))
        assertEquals(BeginResult.Created, ledger.begin("t1", "p1"))
        assertNotNull(ledger().entry("t1"))
    }

    @Test
    fun `the report survives a restart and clearReport needs the matching proposal`() {
        val first = ledger()
        first.setPendingReport("p1", "NOT_EXECUTED recovered")
        assertEquals(PendingReport("p1", "NOT_EXECUTED recovered"), ledger().pendingReport())
        val second = ledger()
        second.clearReport("other")
        assertEquals(PendingReport("p1", "NOT_EXECUTED recovered"), second.pendingReport())
        assertEquals(PendingReport("p1", "NOT_EXECUTED recovered"), ledger().pendingReport())
        second.clearReport("p1")
        assertNull(second.pendingReport())
        assertNull(ledger().pendingReport())
    }

    @Test
    fun `there is one report slot and a new report replaces the old one`() {
        val ledger = ledger()
        ledger.setPendingReport("p1", "one")
        ledger.setPendingReport("p2", "two")
        assertEquals(PendingReport("p2", "two"), ledger().pendingReport())
        ledger.clearReport("p1")
        assertEquals(PendingReport("p2", "two"), ledger.pendingReport())
    }

    @Test
    fun `the report has no age expiry`() {
        val ledger = ledger()
        ledger.setPendingReport("p1", "one")
        clock += 10 * ActionLedger.MAX_AGE_MS
        assertEquals(PendingReport("p1", "one"), ledger().pendingReport())
    }

    @Test
    fun `entries older than 24 hours are dropped on load`() {
        val first = ledger()
        first.start("old")
        clock += ActionLedger.MAX_AGE_MS - 1
        first.start("young")
        clock += 1
        val restarted = ledger()
        assertNotNull("exactly 24 h is kept", restarted.entry("old"))
        clock += 1
        val later = ledger()
        assertNull(later.entry("old"))
        assertNotNull(later.entry("young"))
        assertEquals(BeginResult.Created, later.begin("old", "p-old"))
    }

    @Test
    fun `begin drops expired entries too`() {
        val ledger = ledger()
        ledger.start("old")
        clock += ActionLedger.MAX_AGE_MS + 1
        assertEquals(BeginResult.Created, ledger.begin("old", "p-old"))
        assertEquals(BeginResult.Created, ledger.begin("t2", "p2"))
    }

    @Test
    fun `the bound drops the oldest terminal entry first`() {
        val ledger = ledger()
        for (i in 1..ActionLedger.MAX_ENTRIES) {
            clock += 1
            ledger.start("t$i")
        }
        clock += 1
        assertTrue(ledger.transition("t5", LedgerState.REJECTED))
        assertTrue(ledger.transition("t9", LedgerState.CANCELLED))
        clock += 1
        ledger.start("t65")
        assertNull("oldest terminal is dropped", ledger.entry("t5"))
        assertNotNull("older but unfinished stays", ledger.entry("t1"))
        assertNotNull(ledger.entry("t9"))
        assertNotNull(ledger.entry("t65"))
        clock += 1
        ledger.start("t66")
        assertNull(ledger.entry("t9"))
        assertNotNull(ledger.entry("t1"))
        clock += 1
        ledger.start("t67")
        assertNull("no terminal left: the oldest remaining goes", ledger.entry("t1"))
        assertNotNull(ledger.entry("t2"))
        assertNotNull(ledger.entry("t67"))
        val restarted = ledger()
        assertEquals(ActionLedger.MAX_ENTRIES, (1..67).count { restarted.entry("t$it") != null })
    }

    @Test
    fun `retention - a second instance with the same identity sees the same data and clear empties it`() {
        val first = ledger()
        first.start("t1")
        assertTrue(first.transition("t1", LedgerState.DISPATCHING))
        assertTrue(first.transition("t1", LedgerState.DONE, "ok"))
        first.setPendingReport("p-t1", "line")

        val second = ledger()
        assertEquals(LedgerState.DONE, second.entry("t1")!!.state)
        assertEquals(PendingReport("p-t1", "line"), second.pendingReport())
        assertEquals(BeginResult.Duplicate, ledger().begin("t1", "again"))

        second.clear()
        assertNull(second.entry("t1"))
        assertNull(second.pendingReport())
        assertNull(ledger().entry("t1"))
        assertNull(ledger().pendingReport())
        assertEquals(BeginResult.Created, ledger().begin("t1", "p-t1"))
    }

    // --- live-operation ownership (tickets) ---
    // A ticket guards one in-flight operation against the entry it admitted. It is never persisted and
    // never decides durable state: the entry in actions.json stays the only record.

    private fun ActionLedger.admitted(turn: String, proposal: String = "p-$turn"): LedgerTicket {
        val result = admit(turn, proposal)
        assertTrue("expected Admitted but was $result", result is AdmitResult.Admitted)
        return (result as AdmitResult.Admitted).ticket
    }

    @Test
    fun `a ticket settles its own entry and writes state and report in one durable write`() {
        val ledger = ledger()
        val ticket = ledger.admitted("t1")
        assertEquals(TransitionResult.Applied, ledger.move(ticket, LedgerState.DISPATCHING))
        assertEquals(TransitionResult.Applied, ledger.move(ticket, LedgerState.DONE, "OK", "line-1"))
        val fresh = ledger()
        assertEquals(LedgerState.DONE, fresh.entry("t1")!!.state)
        assertEquals("OK", fresh.entry("t1")!!.reason)
        assertEquals(PendingReport("p-t1", "line-1"), fresh.pendingReport())
    }

    @Test
    fun `admit reports duplicates and unavailability like begin`() {
        val ledger = ledger()
        ledger.admitted("t1", "p1")
        assertEquals(AdmitResult.Duplicate, ledger.admit("t1", "other"))
        assertEquals(AdmitResult.Duplicate, ledger.admit("t2", "p1"))
        identity = null
        assertEquals(AdmitResult.Unavailable, ledger.admit("t3", "p3"))
    }

    @Test
    fun `an illegal edge reports the current state and changes neither state nor report`() {
        val ledger = ledger()
        val ticket = ledger.admitted("t1")
        assertEquals(TransitionResult.Applied, ledger.move(ticket, LedgerState.CANCELLED, "CALLER", "cancelled-line"))
        assertEquals(
            TransitionResult.IllegalEdge(LedgerState.CANCELLED),
            ledger.move(ticket, LedgerState.REJECTED, "CONFIRM_DECLINED", "declined-line"),
        )
        assertEquals(LedgerState.CANCELLED, ledger.entry("t1")!!.state)
        assertEquals("CALLER", ledger.entry("t1")!!.reason)
        assertEquals(PendingReport("p-t1", "cancelled-line"), ledger.pendingReport())
    }

    @Test
    fun `a failed write leaves state and report unchanged`() {
        val ledger = ledger()
        val ticket = ledger.admitted("t1")
        ledger.setPendingReport("p-old", "old")
        blockTempFile()
        assertEquals(TransitionResult.PersistFailed, ledger.move(ticket, LedgerState.CANCELLED, "x", "new"))
        assertEquals(LedgerState.ACCEPTED, ledger.entry("t1")!!.state)
        assertEquals(PendingReport("p-old", "old"), ledger.pendingReport())
        unblockTempFile()
        assertEquals(TransitionResult.Applied, ledger.move(ticket, LedgerState.CANCELLED, "x", "new"))
    }

    @Test
    fun `a ticket from another ledger instance is stale even when key proposal and counter are identical`() {
        val first = ledger()
        val old = first.admitted("t1", "p1")
        val second = ledger()
        // second instance over the same directory and identity: the entry is loaded, so it is a duplicate there
        assertEquals(AdmitResult.Duplicate, second.admit("t1", "p1"))
        assertEquals(TransitionResult.Stale, second.move(old, LedgerState.CANCELLED, "x", "line"))

        // a fresh namespace in a new directory: the same ids are re-begun and the counters line up
        val other = ActionLedger(File(tmp.root, "other"), { identity }, { clock })
        val reborn = other.admitted("t1", "p1")
        assertEquals("the counters restart, so tokens can match", old.token, reborn.token)
        assertEquals(TransitionResult.Stale, other.move(old, LedgerState.CANCELLED, "x", "line"))
        assertEquals(LedgerState.ACCEPTED, other.entry("t1")!!.state)
        assertNull(other.pendingReport())
        assertEquals(TransitionResult.Applied, other.move(reborn, LedgerState.CANCELLED, "x", "mine"))
        // and the old instance cannot settle the reborn entry either
        assertEquals(TransitionResult.Stale, first.move(reborn, LedgerState.DISPATCHING))
    }

    @Test
    fun `a restarted ledger cannot be settled by tickets of the previous process`() {
        val first = ledger()
        val old = first.admitted("t1", "p1")
        assertEquals(TransitionResult.Applied, first.move(old, LedgerState.DISPATCHING))
        val restarted = ledger()
        assertEquals(LedgerState.UNCERTAIN, restarted.entry("t1")!!.state)
        assertEquals(TransitionResult.Stale, restarted.move(old, LedgerState.DONE, "OK", "line"))
        assertEquals(LedgerState.UNCERTAIN, restarted.entry("t1")!!.state)
        assertNull(restarted.pendingReport())
    }

    @Test
    fun `after clear on the same identity a re-begun identical key and proposal is not settled by the old ticket`() {
        val ledger = ledger()
        val old = ledger.admitted("t1", "p1")
        assertEquals(TransitionResult.Applied, ledger.move(old, LedgerState.DISPATCHING))
        ledger.clear()
        val reborn = ledger.admitted("t1", "p1")
        assertEquals(TransitionResult.Stale, ledger.move(old, LedgerState.DONE, "OK", "old-line"))
        assertEquals(LedgerState.ACCEPTED, ledger.entry("t1")!!.state)
        assertNull(ledger.pendingReport())
        assertEquals(TransitionResult.Applied, ledger.move(reborn, LedgerState.DISPATCHING))
    }

    @Test
    fun `after clear a distinct re-begun id is not settled by the old ticket either`() {
        val ledger = ledger()
        val old = ledger.admitted("t1", "p1")
        ledger.clear()
        val other = ledger.admitted("t2", "p2")
        assertEquals(TransitionResult.Stale, ledger.move(old, LedgerState.CANCELLED, "x", "old-line"))
        assertEquals(LedgerState.ACCEPTED, ledger.entry("t2")!!.state)
        assertNull(ledger.pendingReport())
        assertEquals(TransitionResult.Applied, ledger.move(other, LedgerState.CANCELLED, "x"))
    }

    @Test
    fun `an identity switch makes old tickets stale for identical and distinct ids`() {
        val ledger = ledger()
        val sameIds = ledger.admitted("t1", "p1")
        val distinct = ledger.admitted("t2", "p2")
        identity = "device-b"
        val reborn = ledger.admitted("t1", "p1")
        assertEquals(TransitionResult.Stale, ledger.move(sameIds, LedgerState.CANCELLED, "x", "old-line"))
        assertEquals(TransitionResult.Stale, ledger.move(distinct, LedgerState.CANCELLED, "x", "old-line"))
        assertEquals(LedgerState.ACCEPTED, ledger.entry("t1")!!.state)
        assertNull(ledger.pendingReport())
        assertEquals(TransitionResult.Applied, ledger.move(reborn, LedgerState.CANCELLED, "x", "mine"))
        identity = null
        assertEquals(TransitionResult.Stale, ledger.move(reborn, LedgerState.CANCELLED, "x"))
        identity = "device-a"
        assertEquals(TransitionResult.Stale, ledger.move(sameIds, LedgerState.CANCELLED, "x"))
    }

    @Test
    fun `a pruned entry that is re-begun is not settled by the old ticket`() {
        val ledger = ledger()
        val old = ledger.admitted("t1", "p1")
        clock += ActionLedger.MAX_AGE_MS + 1
        // any operation prunes the 24 h old entry
        val reborn = ledger.admitted("t1", "p1")
        assertEquals(TransitionResult.Stale, ledger.move(old, LedgerState.CANCELLED, "x", "old-line"))
        assertEquals(LedgerState.ACCEPTED, ledger.entry("t1")!!.state)
        assertEquals(TransitionResult.Applied, ledger.move(reborn, LedgerState.CANCELLED, "x"))
    }

    @Test
    fun `the state change applies after dropReport but the stale-epoch report is dropped`() {
        val ledger = ledger()
        val ticket = ledger.admitted("t1")
        assertEquals(TransitionResult.Applied, ledger.move(ticket, LedgerState.DISPATCHING))
        ledger.setPendingReport("p-other", "other-line")
        ledger.dropReport()
        assertNull(ledger.pendingReport())
        assertEquals(TransitionResult.Applied, ledger.move(ticket, LedgerState.DONE, "OK", "late-line"))
        assertEquals(LedgerState.DONE, ledger.entry("t1")!!.state)
        assertNull("old session: no report", ledger.pendingReport())
        assertNull(ledger().pendingReport())
    }

    @Test
    fun `clearReport does not bump the epoch but dropReport does`() {
        val ledger = ledger()
        val before = ledger.admitted("t1")
        ledger.setPendingReport("p-x", "x")
        ledger.clearReport("p-x")
        assertEquals(TransitionResult.Applied, ledger.move(before, LedgerState.CANCELLED, "x", "kept"))
        assertEquals(PendingReport("p-t1", "kept"), ledger.pendingReport())

        val dropped = ledger.admitted("t2")
        ledger.dropReport()
        assertEquals(TransitionResult.Applied, ledger.move(dropped, LedgerState.CANCELLED, "x", "lost"))
        assertNull(ledger.pendingReport())
        val after = ledger.admitted("t3")
        assertEquals(TransitionResult.Applied, ledger.move(after, LedgerState.CANCELLED, "x", "again"))
        assertEquals(PendingReport("p-t3", "again"), ledger.pendingReport())
    }

    @Test
    fun `tickets are not durable and the entry file format is unchanged`() {
        val ledger = ledger()
        val ticket = ledger.admitted("t1")
        val text = File(dir(), ActionLedger.FILE_NAME).readText()
        assertFalse(text, text.contains("token") || text.contains("ledgerId") || text.contains("epoch"))
        assertTrue(ledger.move(ticket, LedgerState.DISPATCHING) == TransitionResult.Applied)
    }

    @Test
    fun `a failed drop write still hides the old report at once and a later write removes it from the file`() {
        val ledger = ledger()
        ledger.setPendingReport("p1", "old")
        blockTempFile()
        ledger.dropReport()
        assertNull("suppressed at once", ledger.pendingReport())
        unblockTempFile()
        ledger.start("t9")
        assertNull(ledger.pendingReport())
        assertNull("the next successful write removed it from the file", ledger().pendingReport())
    }

    @Test
    fun `a failed drop write does not let a later settlement persist the old report`() {
        val ledger = ledger()
        val ticket = ledger.admitted("t1")
        ledger.setPendingReport("p-old", "old")
        blockTempFile()
        ledger.dropReport()
        unblockTempFile()
        assertEquals(TransitionResult.Applied, ledger.move(ticket, LedgerState.DISPATCHING))
        assertNull(ledger.pendingReport())
        assertNull(ledger().pendingReport())
    }

    @Test
    fun `owns is true only for the live admission`() {
        val ledger = ledger()
        val ticket = ledger.admitted("t1")
        assertTrue(ledger.owns(ticket))
        assertEquals(TransitionResult.Applied, ledger.move(ticket, LedgerState.CANCELLED))
        assertTrue("ownership is about the admission, not the state", ledger.owns(ticket))
        ledger.clear()
        assertFalse(ledger.owns(ticket))
        val other = ledger.admitted("t1", "p-t1")
        assertTrue(ledger.owns(other))
        assertFalse(ledger.owns(ticket))
        assertFalse(ledger().owns(other))
        identity = "device-b"
        assertFalse(ledger.owns(other))
        identity = null
        assertFalse(ledger.owns(other))
    }

    // --- report receipts: exact, instance- and generation-bound consumption ---

    @Test
    fun `a receipt consumes its report exactly once`() {
        val ledger = ledger()
        assertNull(ledger.offerReport())
        ledger.setPendingReport("p1", "line")
        val offer = ledger.offerReport()!!
        assertEquals(PendingReport("p1", "line"), offer.report)
        assertTrue(ledger.consumeReport(offer.receipt))
        assertNull(ledger.pendingReport())
        assertNull("gone from the file too", ledger().pendingReport())
        assertFalse(ledger.consumeReport(offer.receipt))
    }

    @Test
    fun `offers are ordered and two offers of one report share its fate`() {
        val ledger = ledger()
        ledger.setPendingReport("p1", "line")
        val first = ledger.offerReport()!!
        val second = ledger.offerReport()!!
        assertTrue("later offers have a higher sequence", second.seq > first.seq)
        assertTrue(ledger.consumeReport(second.receipt))
        assertFalse("the same report is already consumed", ledger.consumeReport(first.receipt))
    }

    @Test
    fun `a receipt is stale after a same-id replacement without a forget`() {
        val ledger = ledger()
        ledger.setPendingReport("p1", "first")
        val offer = ledger.offerReport()!!
        ledger.setPendingReport("p1", "second")
        assertFalse(ledger.consumeReport(offer.receipt))
        assertEquals(PendingReport("p1", "second"), ledger.pendingReport())
    }

    @Test
    fun `a receipt is stale after a settlement of another proposal replaced the report`() {
        val ledger = ledger()
        val ticket = ledger.admitted("t1")
        ledger.setPendingReport("p-old", "old")
        val offer = ledger.offerReport()!!
        assertEquals(TransitionResult.Applied, ledger.move(ticket, LedgerState.CANCELLED, "x", "new"))
        assertFalse(ledger.consumeReport(offer.receipt))
        assertEquals(PendingReport("p-t1", "new"), ledger.pendingReport())
    }

    @Test
    fun `a receipt is stale after the report was cleared and a new one written`() {
        val ledger = ledger()
        ledger.setPendingReport("p1", "line")
        val offer = ledger.offerReport()!!
        ledger.clearReport("p1")
        ledger.setPendingReport("p1", "line")
        assertFalse(ledger.consumeReport(offer.receipt))
        assertEquals(PendingReport("p1", "line"), ledger.pendingReport())
    }

    @Test
    fun `a receipt is stale after clear and the same ids settled again`() {
        val ledger = ledger()
        val old = ledger.admitted("t1", "p1")
        assertEquals(TransitionResult.Applied, ledger.move(old, LedgerState.DISPATCHING))
        assertEquals(TransitionResult.Applied, ledger.move(old, LedgerState.DONE, "OK", "first"))
        val offer = ledger.offerReport()!!
        ledger.clear()
        val reborn = ledger.admitted("t1", "p1")
        assertEquals(TransitionResult.Applied, ledger.move(reborn, LedgerState.DISPATCHING))
        assertEquals(TransitionResult.Applied, ledger.move(reborn, LedgerState.DONE, "OK", "second"))
        assertFalse(ledger.consumeReport(offer.receipt))
        assertEquals(PendingReport("p1", "second"), ledger.pendingReport())
    }

    @Test
    fun `a receipt is stale after a reset drop even when the same id is written again`() {
        val ledger = ledger()
        ledger.setPendingReport("p1", "line")
        val offer = ledger.offerReport()!!
        ledger.dropReport()
        ledger.setPendingReport("p1", "line")
        assertFalse(ledger.consumeReport(offer.receipt))
        assertNotNull(ledger.pendingReport())
    }

    @Test
    fun `a receipt does not work in another ledger instance or under another identity`() {
        val first = ledger()
        first.setPendingReport("p1", "line")
        val offer = first.offerReport()!!
        val second = ledger()
        assertEquals(PendingReport("p1", "line"), second.pendingReport())
        assertFalse("another instance", second.consumeReport(offer.receipt))
        assertNotNull(second.pendingReport())
        assertTrue("its own receipt works", second.consumeReport(second.offerReport()!!.receipt))

        val third = ledger()
        third.setPendingReport("p2", "line")
        val mine = third.offerReport()!!
        identity = "device-b"
        assertFalse(third.consumeReport(mine.receipt))
        identity = "device-a"
        assertFalse(third.consumeReport(mine.receipt))
    }

    @Test
    fun `a settlement without a report keeps the receipt valid`() {
        val ledger = ledger()
        val ticket = ledger.admitted("t1")
        ledger.setPendingReport("p1", "line")
        val offer = ledger.offerReport()!!
        assertEquals(TransitionResult.Applied, ledger.move(ticket, LedgerState.DISPATCHING))
        assertTrue(ledger.consumeReport(offer.receipt))
        assertNull(ledger.pendingReport())
    }

    @Test
    fun `a failed consume write keeps the report and the receipt for a retry`() {
        val ledger = ledger()
        ledger.setPendingReport("p1", "line")
        val offer = ledger.offerReport()!!
        blockTempFile()
        assertFalse(ledger.consumeReport(offer.receipt))
        assertEquals(PendingReport("p1", "line"), ledger.pendingReport())
        unblockTempFile()
        assertTrue(ledger.consumeReport(offer.receipt))
        assertNull(ledger.pendingReport())
    }

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
}
