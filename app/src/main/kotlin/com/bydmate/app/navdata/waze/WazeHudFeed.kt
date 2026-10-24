package com.bydmate.app.navdata.waze

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.cluster.SteeringWheelKeyService
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.navdata.NavAppId
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavRead

/** Waze nav bar (active window only) -> [NavGuidanceHub], experimental, off by default.
 *
 *  Only a CHANGED parsed observation is freshness evidence ([last]); a read attempt that parses to the
 *  last accepted observation calls no hub call, so the hub's own 10 s detail and 30 s route holds expire an
 *  unchanged screen. Attempts ([lastAttemptMs], the 2 s timer) never move a deadline.
 *
 *  One [gate] guards [on], [generation] and [last]. A read takes a [ticket] first, does every foreign call
 *  (root fetch, node reads, recycle) OUTSIDE the gate, then [commit]s. Lock order: gate, then the hub's
 *  monitor; the hub never calls the feed. No arrow, no maneuver mapping: the maneuver is always 0. */
object WazeHudFeed {
    private const val DEBOUNCE_MS = 500L
    private const val TIMER_MS = 2_000L

    private val gate = Any()
    @Volatile private var on = false
    private var generation = 0L
    private var last: WazeParse.Guidance? = null
    private var timer: Handler? = null

    /** Raised by event and timer attempts only; never evidence. */
    @Volatile internal var lastAttemptMs = 0L
    /** Edge guard of the ignored-read trace: the last reason traced, null after a valid read. */
    @Volatile private var lastIgnored: WazeIgnoreReason? = null

    internal var clock: () -> Long = { System.currentTimeMillis() }
    /** The ACTIVE Waze window's root, or null: one provider for the event and the timer path. */
    @Volatile internal var rootProvider: () -> AccessibilityNodeInfo? =
        { SteeringWheelKeyService.instance?.findActiveWazeRoot() }

    val enabled: Boolean get() = on
    internal val generationForTest: Long get() = synchronized(gate) { generation }

    /** The current generation, null while off. */
    fun ticket(): Long? = synchronized(gate) { if (on) generation else null }

    /** Publishes [guidance] when [ticket] is current, the feed is on and it differs from [last]; [last] moves
     *  only when the hub accepts. Nothing foreign, suspending or blocking runs under the gate. */
    fun commit(ticket: Long, guidance: WazeParse.Guidance, nowMs: Long): Boolean = synchronized(gate) {
        if (!on || generation != ticket || guidance == last) return false
        val read = NavRead(maneuverGaode = 0, distanceMeters = guidance.distanceM, road = guidance.road)
        val accepted = NavGuidanceHub.publishAccepted(NavAppId.WAZE, read, nowMs)
        if (accepted) last = guidance
        accepted
    }

    /** Disable and "end the Waze route" are one critical section: a late [commit] either ran before it (and
     *  [NavGuidanceHub.end] removes its route) or sees the new generation. [last] is kept (D1). */
    fun setEnabled(want: Boolean) {
        synchronized(gate) {
            if (on == want) return
            on = want
            generation++
            timer?.removeCallbacksAndMessages(null)
            timer = null
            if (want) startTimer(generation) else NavGuidanceHub.end(NavAppId.WAZE)
        }
    }

    fun onEvent(event: AccessibilityEvent?) {
        if (!on) return
        val nowMs = clock()
        if (!shouldProcess(event?.packageName?.toString(), event?.eventType ?: 0, nowMs, lastAttemptMs)) return
        lastAttemptMs = nowMs
        attempt(ticket() ?: return, nowMs)
    }

    /** Catch-up read: the debounce can drop the last change of a burst when no later event comes. Never evidence. */
    internal fun onTimer(generation: Long, nowMs: Long) {
        if (ticket() != generation) return
        lastAttemptMs = nowMs
        attempt(generation, nowMs)
    }

    fun shouldProcess(pkg: String?, eventType: Int, nowMs: Long, lastAttemptMs: Long): Boolean {
        if (pkg != WazeNavBarReader.PACKAGE) return false
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        return nowMs - lastAttemptMs >= DEBOUNCE_MS
    }

    internal fun resetForTest() {
        synchronized(gate) {
            on = false
            generation = 0L
            last = null
            timer?.removeCallbacksAndMessages(null)
            timer = null
        }
        lastAttemptMs = 0L
        lastIgnored = null
        clock = { System.currentTimeMillis() }
        rootProvider = { SteeringWheelKeyService.instance?.findActiveWazeRoot() }
    }

    /** One read: root, texts, parse, commit. Every foreign call is outside the gate. */
    private fun attempt(ticket: Long, nowMs: Long) {
        val root = runCatching { rootProvider() }.getOrNull() ?: return
        val texts = try {
            WazeNavBarReader.read(root)
        } finally {
            @Suppress("DEPRECATION") runCatching { root.recycle() }
        }
        when (val parsed = WazeGuidanceParser.parse(texts?.distance, texts?.street)) {
            is WazeParse.Guidance -> {
                lastIgnored = null
                commit(ticket, parsed, nowMs)
            }
            is WazeParse.Ignore -> if (lastIgnored != parsed.reason) {
                lastIgnored = parsed.reason
                Trace.event(TraceArea.HUD, "waze-read-ignored", "reason" to parsed.reason.name.lowercase())
            }
        }
    }

    /** Main-looper chain of this generation; a tick of an older generation stops itself. */
    private fun startTimer(gen: Long) {
        val handler = Handler(Looper.getMainLooper())
        timer = handler
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (ticket() != gen) return
                onTimer(gen, clock())
                handler.postDelayed(this, TIMER_MS)
            }
        }, TIMER_MS)
    }
}
