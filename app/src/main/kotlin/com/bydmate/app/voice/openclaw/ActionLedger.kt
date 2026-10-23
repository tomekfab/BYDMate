package com.bydmate.app.voice.openclaw

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

enum class LedgerState {
    ACCEPTED, CONFIRM_PENDING, DISPATCHING, DONE, FAILED, BLOCKED, REJECTED, CANCELLED, EXPIRED, UNCERTAIN,
    SAVE_PENDING, SAVED, DISMISSED,
}

data class LedgerEntry(
    val turnKey: String,
    val proposalId: String,
    val state: LedgerState,
    val reason: String?,
    val updatedAt: Long,
)

sealed interface BeginResult {
    object Created : BeginResult
    object Duplicate : BeginResult
    object Unavailable : BeginResult
}

data class PendingReport(val proposalId: String, val line: String)

/**
 * Proof that one live operation admitted one ledger entry. It guards that operation against settling an entry that
 * only looks the same: a re-begun identical turn key and proposal id after [ActionLedger.clear], under a new identity,
 * after pruning, in another ledger instance or after a restart.
 *
 * It is not durable and not a second ledger. The persisted entry in `actions.json` stays the only record of a
 * proposal's state; the ticket only says whether *this* in-flight operation still owns that entry.
 * [ledgerId] is a random per-instance nonce, so counters that restart at 1 in another instance never match.
 */
class LedgerTicket internal constructor(
    val turnKey: String,
    val proposalId: String,
    internal val ledgerId: String,
    internal val token: Long,
    internal val reportEpoch: Long,
)

/**
 * Proof that a report was offered to a turn: it names the ledger instance and the exact report it was read from, so
 * only that report can be consumed with it. Not durable and not constructible outside the ledger; it decides nothing
 * about what is on disk. Consuming a receipt that no longer matches does nothing.
 */
internal class ReportReceipt internal constructor(internal val ledgerId: String, internal val generation: Long)

/** The report as read for a turn, with its [receipt] and an offer sequence ([seq]) that orders offers. */
internal class OfferedReport internal constructor(val report: PendingReport, val receipt: ReportReceipt, val seq: Long)

sealed interface AdmitResult {
    class Admitted(val ticket: LedgerTicket) : AdmitResult
    object Duplicate : AdmitResult
    object Unavailable : AdmitResult
}

sealed interface TransitionResult {
    object Applied : TransitionResult

    /** The ticket no longer owns an entry: cleared, another identity, pruned, another instance or a restart. */
    object Stale : TransitionResult
    data class IllegalEdge(val current: LedgerState) : TransitionResult
    object PersistFailed : TransitionResult
}

/**
 * The only replay guard for assistant actions: one entry per turn key, written to disk before dispatch.
 * Persisted in [dir] (like [OpenClawConversationStore]) and never keyed on or cleared by transport events;
 * only [clear] or a different device identity empties it. The identity is re-read on every operation, and a
 * missing identity makes the ledger unavailable. A failed durable write leaves memory unchanged.
 */
@Suppress("TooManyFunctions") // One synchronized ledger owns replay protection, durable transitions and report receipts.
class ActionLedger(
    private val dir: File,
    private val identityId: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var loadedFor: String? = null
    private var entries = LinkedHashMap<String, LedgerEntry>()
    private var report: PendingReport? = null

    // Live-operation ownership. In memory only: it guards in-flight operations and decides nothing durable.
    private val ledgerId = UUID.randomUUID().toString()
    private var nextToken = 1L
    private val owners = HashMap<String, Long>()
    private var reportEpoch = 0L

    // Report identity. Bumped only when the current report is replaced, consumed or invalidated; a settlement that
    // writes no report leaves it alone. In memory only, like the ownership above.
    private var reportGeneration = 0L
    private var offerSeq = 0L

    private val file get() = File(dir, FILE_NAME)
    private val temp get() = File(dir, TEMP_NAME)

    fun begin(turnKey: String, proposalId: String): BeginResult = when (admit(turnKey, proposalId)) {
        is AdmitResult.Admitted -> BeginResult.Created
        AdmitResult.Duplicate -> BeginResult.Duplicate
        AdmitResult.Unavailable -> BeginResult.Unavailable
    }

    /** [begin] that also hands the caller the ticket proving it admitted the new entry. */
    @Synchronized
    fun admit(turnKey: String, proposalId: String): AdmitResult {
        val id = ready() ?: return AdmitResult.Unavailable
        val live = prune(entries.values)
        if (live.containsKey(turnKey) || live.values.any { it.proposalId == proposalId }) return AdmitResult.Duplicate
        val next = prune(live.values + LedgerEntry(turnKey, proposalId, LedgerState.ACCEPTED, null, now()))
        if (!persist(id, next, report)) return AdmitResult.Unavailable
        adopt(next)
        val token = nextToken++
        owners[turnKey] = token
        return AdmitResult.Admitted(LedgerTicket(turnKey, proposalId, ledgerId, token, reportEpoch))
    }

    /**
     * One atomic settlement for the operation that admitted [ticket]: the state edge and, when [report] is given,
     * the pending report go out in a single durable write. The report is dropped (the state still moves) when the
     * conversation was reset since admission. Nothing changes unless the result is [TransitionResult.Applied].
     */
    @Synchronized
    fun move(ticket: LedgerTicket, to: LedgerState, reason: String? = null, report: String? = null): TransitionResult {
        val id = ready() ?: return TransitionResult.Stale
        if (ticket.ledgerId != ledgerId || owners[ticket.turnKey] != ticket.token) return TransitionResult.Stale
        val current = entries[ticket.turnKey] ?: return TransitionResult.Stale
        if (current.proposalId != ticket.proposalId) return TransitionResult.Stale
        if (to !in LEGAL_EDGES[current.state].orEmpty()) return TransitionResult.IllegalEdge(current.state)
        val next = LinkedHashMap(entries)
        next[ticket.turnKey] = current.copy(state = to, reason = reason, updatedAt = now())
        val writesReport = report != null && ticket.reportEpoch == reportEpoch
        val nextReport = if (writesReport) PendingReport(ticket.proposalId, report!!) else this.report
        if (!persist(id, next, nextReport)) return TransitionResult.PersistFailed
        entries = next
        this.report = nextReport
        if (writesReport) reportGeneration++
        return TransitionResult.Applied
    }

    /**
     * Whether [ticket] still owns the entry it admitted (same instance, same admission, same proposal id). Read-only,
     * and about the admission, not the state: [move] decides what a state edge may do. Used to avoid presenting
     * something whose operation was forgotten, cleared or replaced.
     */
    @Synchronized
    internal fun owns(ticket: LedgerTicket): Boolean {
        ready() ?: return false
        if (ticket.ledgerId != ledgerId || owners[ticket.turnKey] != ticket.token) return false
        return entries[ticket.turnKey]?.proposalId == ticket.proposalId
    }

    /**
     * The conversation was reset: the report goes away and reports of operations admitted before it can no longer be
     * written. Memory is cleared at once, so nothing is attached to a turn or carried into a later write even if the
     * write that removes it from the file fails. In that case the file keeps the old line until the next successful
     * write (any admission, settlement or report) rewrites it without it; a process restart before such a write
     * reloads the old line. That needs the storage to be failing at the moment of the reset and the process to stop
     * before any later write succeeds. The line is a code-only, best-effort report; the entries stay the only record.
     */
    @Synchronized
    fun dropReport() {
        val id = ready() ?: return
        reportEpoch++
        val had = report != null
        report = null
        reportGeneration++
        if (had) persist(id, entries, null)
    }

    @Synchronized
    fun transition(turnKey: String, to: LedgerState, reason: String? = null): Boolean {
        val id = ready() ?: return false
        val current = entries[turnKey] ?: return false
        if (to !in LEGAL_EDGES[current.state].orEmpty()) return false
        val next = LinkedHashMap(entries)
        next[turnKey] = current.copy(state = to, reason = reason, updatedAt = now())
        if (!persist(id, next, report)) return false
        entries = next
        return true
    }

    @Synchronized
    fun entry(turnKey: String): LedgerEntry? {
        ready() ?: return null
        return entries[turnKey]
    }

    @Synchronized
    fun entryByProposal(proposalId: String): LedgerEntry? {
        ready() ?: return null
        return entries.values.firstOrNull { it.proposalId == proposalId }
    }

    @Synchronized
    fun setPendingReport(proposalId: String, line: String) {
        val id = ready() ?: return
        val next = PendingReport(proposalId, line)
        if (persist(id, entries, next)) {
            report = next
            reportGeneration++
        }
    }

    /** The pending report with a receipt for exactly this report and a fresh offer sequence, in one atomic read. */
    @Synchronized
    internal fun offerReport(): OfferedReport? {
        ready() ?: return null
        val current = report ?: return null
        return OfferedReport(current, ReportReceipt(ledgerId, reportGeneration), ++offerSeq)
    }

    /**
     * Removes the report [receipt] was made for, once. False (and nothing changes) when the receipt is from another
     * instance, the report was replaced, consumed, dropped or cleared since, or the write failed; a failed write
     * keeps the receipt valid for a retry.
     */
    @Synchronized
    internal fun consumeReport(receipt: ReportReceipt): Boolean {
        val id = ready() ?: return false
        if (receipt.ledgerId != ledgerId || receipt.generation != reportGeneration || report == null) return false
        if (!persist(id, entries, null)) return false
        report = null
        reportGeneration++
        return true
    }

    @Synchronized
    fun pendingReport(): PendingReport? {
        ready() ?: return null
        return report
    }

    @Synchronized
    fun clearReport(proposalId: String) {
        val id = ready() ?: return
        if (report?.proposalId != proposalId) return
        if (persist(id, entries, null)) {
            report = null
            reportGeneration++
        }
    }

    /** The explicit forget: drops every entry and the report. */
    @Synchronized
    fun clear() {
        entries = LinkedHashMap()
        report = null
        resetOwnership()
        loadedFor = identityId()
        removeFiles()
        val id = loadedFor
        if (id != null && file.exists()) persist(id, entries, null)
    }

    /** The current identity with memory loaded for it; null when there is none (the file is then discarded). */
    private fun ready(): String? {
        val id = identityId()
        if (id == null) {
            entries = LinkedHashMap()
            report = null
            resetOwnership()
            loadedFor = null
            removeFiles()
            return null
        }
        if (id != loadedFor) load(id)
        return id
    }

    private fun load(id: String) {
        entries = LinkedHashMap()
        report = null
        resetOwnership()
        loadedFor = id
        if (!file.isFile) return
        try {
            val json = JSONObject(file.readText())
            if (json.getString("deviceId") != id) {
                removeFiles()
                return
            }
            val array = json.getJSONArray("entries")
            val loaded = (0 until array.length()).map { restart(readEntry(array.getJSONObject(it))) }
            val stored = json.optJSONObject("report")
            val pending = stored?.let { PendingReport(it.getString("proposalId"), it.getString("line")) }
            entries = prune(loaded)
            report = pending
        } catch (_: Exception) {
            entries = LinkedHashMap()
            report = null
            removeFiles()
        }
    }

    /** Entries and report are gone or replaced: no in-flight operation owns anything any more. */
    private fun resetOwnership() {
        owners.clear()
        reportEpoch++
        reportGeneration++
    }

    /** Keeps the owner map no larger than the entries it guards. */
    private fun adopt(next: LinkedHashMap<String, LedgerEntry>) {
        entries = next
        owners.keys.retainAll(next.keys)
    }

    /** What a process restart makes of a state: nothing is retried, so the unfinished ones are closed. */
    private fun restart(entry: LedgerEntry): LedgerEntry = when (entry.state) {
        LedgerState.DISPATCHING -> entry.copy(state = LedgerState.UNCERTAIN)
        LedgerState.CONFIRM_PENDING, LedgerState.ACCEPTED -> entry.copy(state = LedgerState.EXPIRED)
        else -> entry
    }

    /** Drops entries older than 24 h, then the oldest terminal ones, then the oldest remaining, down to 64. */
    private fun prune(all: Collection<LedgerEntry>): LinkedHashMap<String, LedgerEntry> {
        val cutoff = now() - MAX_AGE_MS
        val kept = all.filter { it.updatedAt >= cutoff }
        val dropped = if (kept.size <= MAX_ENTRIES) {
            emptySet()
        } else {
            kept.sortedWith(compareBy({ it.state !in TERMINAL }, { it.updatedAt }))
                .take(kept.size - MAX_ENTRIES)
                .map { it.turnKey }
                .toSet()
        }
        return LinkedHashMap<String, LedgerEntry>().also { map ->
            kept.filter { it.turnKey !in dropped }.forEach { map[it.turnKey] = it }
        }
    }

    private fun persist(id: String, toStore: Map<String, LedgerEntry>, pending: PendingReport?): Boolean = try {
        dir.mkdirs()
        val json = JSONObject()
            .put("deviceId", id)
            .put("entries", JSONArray(toStore.values.map(::writeEntry)))
        pending?.let { json.put("report", JSONObject().put("proposalId", it.proposalId).put("line", it.line)) }
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) throw IOException("could not store the action ledger")
        true
    } catch (_: Exception) {
        if (temp.isFile) temp.delete()
        false
    }

    private fun removeFiles() {
        file.delete()
        if (temp.isFile) temp.delete()
    }

    private fun writeEntry(entry: LedgerEntry): JSONObject = JSONObject()
        .put("turnKey", entry.turnKey)
        .put("proposalId", entry.proposalId)
        .put("state", entry.state.name)
        .put("updatedAt", entry.updatedAt)
        .also { json -> entry.reason?.let { json.put("reason", it) } }

    private fun readEntry(json: JSONObject) = LedgerEntry(
        turnKey = json.getString("turnKey"),
        proposalId = json.getString("proposalId"),
        state = LedgerState.valueOf(json.getString("state")),
        reason = if (json.isNull("reason")) null else json.getString("reason"),
        updatedAt = json.getLong("updatedAt"),
    )

    companion object {
        const val FILE_NAME = "actions.json"
        const val TEMP_NAME = "actions.json.tmp"
        const val MAX_ENTRIES = 64
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000

        private val LEGAL_EDGES: Map<LedgerState, Set<LedgerState>> = mapOf(
            LedgerState.ACCEPTED to setOf(
                LedgerState.CONFIRM_PENDING, LedgerState.DISPATCHING, LedgerState.REJECTED, LedgerState.CANCELLED,
                LedgerState.EXPIRED, LedgerState.SAVE_PENDING,
            ),
            LedgerState.CONFIRM_PENDING to setOf(
                LedgerState.DISPATCHING, LedgerState.REJECTED, LedgerState.CANCELLED, LedgerState.EXPIRED,
            ),
            LedgerState.DISPATCHING to setOf(
                LedgerState.DONE, LedgerState.FAILED, LedgerState.BLOCKED, LedgerState.UNCERTAIN,
            ),
            LedgerState.SAVE_PENDING to setOf(LedgerState.SAVED, LedgerState.DISMISSED, LedgerState.CANCELLED),
        )

        private val TERMINAL: Set<LedgerState> = LedgerState.values().toSet() - LEGAL_EDGES.keys
    }
}
