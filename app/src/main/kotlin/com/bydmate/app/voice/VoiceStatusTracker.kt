package com.bydmate.app.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Publishes the [VoiceStatus] the floating widget shows.
 *
 * Writers hold identity-checked handles: a [Session] is valid only while it is the tracker's
 * current open session, and a [Turn] only while it is that session's current turn. A late
 * callback from a cancelled turn or an old session therefore does nothing. Every check and
 * publish runs under one lock. Every dwell timer carries a generation number that is checked
 * under the same lock, so an old timer cannot fire into a newer state even if cancellation
 * arrives late.
 */
class VoiceStatusTracker(private val scope: CoroutineScope) {

    private val lock = Any()
    private val _status = MutableStateFlow(VoiceStatus.IDLE)
    val status: StateFlow<VoiceStatus> = _status

    internal var terminalDwellMs = 4_000L
    internal var speechTailMs = 1_500L
    internal var terminalCapMs = 20_000L
    internal var clock: () -> Long = System::currentTimeMillis

    private val log = ArrayList<VoiceStatus>()

    /** Test seam: every status the tracker publishes, in order. [status] is a conflated
     *  StateFlow, so a test that samples it can miss a briefly published state; this cannot. */
    internal val publishLog: List<VoiceStatus>
        get() = synchronized(lock) { log.toList() }

    private var current: Session? = null

    // Terminal dwell state. All of it is guarded by [lock].
    private var timerGen = 0L
    private var timerJob: Job? = null
    private var terminalPending = false
    private var terminalAt = 0L
    private var dwellOwner: Session? = null
    /** The dwell ended while speech was still playing; waiting for the tail or the cap. */
    private var holding = false

    fun openSession(language: VoiceLanguage, preparing: Boolean): Session {
        return synchronized(lock) {
            val cur = current
            if (cur?.let { it.open && it.preparing } == true && !preparing) {
                cur.preparing = false
                cancelDwell()
                publish(VoiceStage.LISTENING, null, false, cur.language)
                return@synchronized cur
            }
            cur?.open = false
            cancelDwell()
            val session = Session(language, preparing)
            current = session
            publish(if (preparing) VoiceStage.PREPARING else VoiceStage.LISTENING, null, false, language)
            session
        }
    }

    /** One-shot failure with no session (for example the GigaAM model is missing). */
    fun showFailure(language: VoiceLanguage) {
        synchronized(lock) {
            current?.open = false
            current = null
            cancelDwell()
            publish(VoiceStage.FAILED, null, false, language)
            startDwell(null)
        }
    }

    inner class Session internal constructor(
        internal val language: VoiceLanguage,
        internal var preparing: Boolean,
    ) {
        internal var open = true
        internal var turn: Turn? = null

        internal fun isCurrent() = open && current === this

        fun listening() {
            synchronized(lock) {
                if (!isCurrent()) return
                preparing = false
                cancelDwell()
                publish(VoiceStage.LISTENING, null, _status.value.speaking, language)
            }
        }

        fun openTurn(): Turn = synchronized(lock) {
            val t = Turn(this)
            if (isCurrent()) turn = t
            t
        }

        fun setSpeaking(on: Boolean) {
            synchronized(lock) {
                if (!isCurrent()) return
                val st = _status.value
                if (st.stage == VoiceStage.IDLE || st.speaking == on) return
                publish(st.copy(speaking = on))
                if (terminalPending && holding) {
                    armTimer(if (on) capRemaining() else minOf(speechTailMs, capRemaining())) { release() }
                }
            }
        }

        fun close(lingerTerminal: Boolean) {
            synchronized(lock) {
                if (!isCurrent()) return
                open = false
                current = null
                val st = _status.value
                if (lingerTerminal && st.stage.isTerminal && terminalPending) {
                    // Speech can no longer be reported once the session is gone.
                    if (st.speaking) {
                        publish(st.copy(speaking = false))
                        if (holding) armTimer(minOf(speechTailMs, capRemaining())) { release() }
                    }
                } else {
                    cancelDwell()
                    if (st != VoiceStatus.IDLE) publish(VoiceStatus.IDLE)
                }
            }
        }
    }

    inner class Turn internal constructor(private val session: Session) {
        private var heard: String? = null

        private fun isCurrent() = session.isCurrent() && session.turn === this

        fun processing(heard: String?) {
            synchronized(lock) {
                if (!isCurrent()) return
                this.heard = boundHeard(heard)
                cancelDwell()
                publish(VoiceStage.PROCESSING, this.heard, _status.value.speaking, session.language)
            }
        }

        fun running() = step(VoiceStage.RUNNING_COMMAND)

        fun waitingAssistant() = step(VoiceStage.WAITING_ASSISTANT)

        private fun step(stage: VoiceStage) {
            synchronized(lock) {
                if (!isCurrent()) return
                cancelDwell()
                publish(stage, heard, _status.value.speaking, session.language)
            }
        }

        fun finish(stage: VoiceStage) {
            require(stage.isTerminal) { "finish needs a terminal stage, got $stage" }
            synchronized(lock) {
                if (!isCurrent()) return
                cancelDwell()
                publish(stage, heard, _status.value.speaking, session.language)
                startDwell(session)
            }
        }
    }

    // ---- everything below runs under [lock] ----

    private fun publish(stage: VoiceStage, heard: String?, speaking: Boolean, language: VoiceLanguage) {
        publish(VoiceStatus(stage, heard, speaking, language))
    }

    private fun publish(next: VoiceStatus) {
        _status.value = next
        log.add(next)
    }

    private fun cancelDwell() {
        timerGen++
        timerJob?.cancel()
        timerJob = null
        terminalPending = false
        holding = false
        dwellOwner = null
    }

    private fun startDwell(owner: Session?) {
        terminalPending = true
        holding = false
        terminalAt = clock()
        dwellOwner = owner
        armTimer(terminalDwellMs) {
            if (_status.value.speaking) {
                holding = true
                armTimer(capRemaining()) { release() }
            } else {
                release()
            }
        }
    }

    private fun capRemaining() = (terminalCapMs - (clock() - terminalAt)).coerceAtLeast(0L)

    /** Replaces any running timer. [action] runs under [lock] and only if no newer timer or
     *  cancel happened in between. */
    private fun armTimer(delayMs: Long, action: () -> Unit) {
        timerJob?.cancel()
        val gen = ++timerGen
        timerJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            delay(delayMs)
            synchronized(lock) { if (gen == timerGen) action() }
        }
    }

    /** The terminal status has been shown long enough: back to LISTENING if its session is
     *  still open and ready, otherwise IDLE. */
    private fun release() {
        val owner = dwellOwner
        terminalPending = false
        holding = false
        dwellOwner = null
        timerJob = null
        if (owner != null && owner.isCurrent() && !owner.preparing) {
            publish(VoiceStage.LISTENING, null, false, owner.language)
        } else {
            publish(VoiceStatus.IDLE)
        }
    }
}
