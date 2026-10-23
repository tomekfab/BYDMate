package com.bydmate.app.data.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the most recent moment a default network can REALLY reach the public
 * internet. Android's `NET_CAPABILITY_VALIDATED` is a necessary but not
 * sufficient signal on DiLink: BYD ships several APNs (e.g. ims/system APN1)
 * that are flagged INTERNET+VALIDATED so the system can keep VoLTE / map
 * updates / telematics alive even when the user has toggled "Cellular Data"
 * off — but user-space apps can't actually reach the wider internet through
 * those APNs. Listening to VALIDATED alone fired the trigger in those cases.
 *
 * To distinguish a real-world reachable network we run a lightweight TCP
 * probe to a list of well-known DNS servers (Yandex / Quad9, port 53)
 * each time Android signals a fresh VALIDATED edge. The probe socket is
 * bound to the candidate network so traffic cannot silently fall back to a
 * different default. Only after the probe succeeds do we publish
 * [_lastAvailableAt] for the AutomationEngine.
 */
@Singleton
class NetworkAvailableMonitor internal constructor(
    private val context: Context,
    // Test seams: the probe and the scope it runs on, and the clock of a published edge.
    reachabilityProbe: (suspend (Network) -> Boolean)?,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context,
        reachabilityProbe = null,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        clock = System::currentTimeMillis,
    )

    private val probe: suspend (Network) -> Boolean =
        reachabilityProbe ?: { network -> withContext(Dispatchers.IO) { tryProbe(network) } }

    @Volatile private var _lastAvailableAt: Long = 0L
    // True while an async TCP probe is in flight. AutomationEngine consults this
    // before seeding the per-rule watermark on first observation: seeding 0
    // mid-probe and then handing the about-to-publish edge as "new" would
    // re-introduce the false-fire it tries to prevent. One extra poll tick of
    // latency is a fair price.
    @Volatile private var _probePending: Boolean = false

    val lastAvailableAt: Long get() = _lastAvailableAt
    val probePending: Boolean get() = _probePending

    private val _edges = MutableStateFlow(0L)
    /** [lastAvailableAt] as a flow, for work that waits for the internet (the Telegram report outbox). */
    val edges: StateFlow<Long> = _edges

    private val cm: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Guards the registration, its tracked network and the probe that owns it. Callbacks, probe
    // results, start and stop all decide under it, so a late event from a stopped registration
    // or a cancelled probe cannot change a newer one.
    private val lock = Any()
    private var registration: Registration? = null
    // Single in-flight probe — newer VALIDATED edges cancel any earlier
    // probe still running, avoiding stacked socket attempts when the
    // system spams capability changes during a network transition.
    // [probeOwner] identifies it: a cancelled probe can still return late.
    private var probeJob: Job? = null
    private var probeOwner: Any? = null

    /** The callback of one [start]; a stopped registration's late callbacks change nothing. */
    private inner class Registration {
        // Tracks which network is currently validated. Prevents repeated capabilities
        // callbacks for the SAME network from re-running the probe — without this,
        // every signal-strength tick on a stable WiFi would re-arm the trigger.
        // A failed probe releases it, so a later callback can probe the same network again.
        var trackedNetworkId: Long = NO_NETWORK

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                onCapabilities(this@Registration, network, hasInternet && validated)
            }

            override fun onLost(network: Network) {
                onNetworkGone(this@Registration, network.networkHandle, "tracked network lost")
            }
        }
    }

    private fun onCapabilities(owner: Registration, network: Network, validated: Boolean) {
        val nid = network.networkHandle
        if (!validated) {
            onNetworkGone(owner, nid, "validation lost on tracked network")
            return
        }
        synchronized(lock) {
            if (registration !== owner || owner.trackedNetworkId == nid) return
            owner.trackedNetworkId = nid
            Log.i(TAG, "candidate validated network (nid=$nid), probing reachability")
            probeReachabilityLocked(owner, network, nid)
        }
    }

    private fun onNetworkGone(owner: Registration, nid: Long, what: String) {
        synchronized(lock) {
            if (registration !== owner || owner.trackedNetworkId != nid) return
            owner.trackedNetworkId = NO_NETWORK
            // A probe still running for this network has nothing left to confirm.
            cancelProbeLocked()
            Log.i(TAG, "$what (nid=$nid)")
        }
    }

    private fun probeReachabilityLocked(owner: Registration, network: Network, nid: Long) {
        probeJob?.cancel()
        val probeId = Any()
        probeOwner = probeId
        _probePending = true
        probeJob = scope.launch {
            // A probe that ignores its cancellation still returns here; settleProbe drops it then.
            val reached = runCatching { probe(network) }.getOrDefault(false)
            settleProbe(owner, probeId, nid, reached)
        }
    }

    private fun settleProbe(owner: Registration, probeId: Any, nid: Long, reached: Boolean) {
        synchronized(lock) {
            // Race guard: by the time the probe returns, the user may have already switched to
            // yet another network, or the monitor stopped. Each of those replaced this probe's
            // ownership, and only the owner may publish or release its candidate.
            if (probeOwner !== probeId) {
                Log.i(TAG, "probe result for nid=$nid is no longer current — dropped")
                return
            }
            probeOwner = null
            probeJob = null
            _probePending = false
            if (!reached) {
                // The car's internet often arrives minutes after its Wi-Fi: a later VALIDATED
                // callback for this same network must be able to probe again.
                owner.trackedNetworkId = NO_NETWORK
                Log.i(TAG, "probe failed for nid=$nid — not publishing edge")
                return
            }
            _lastAvailableAt = clock()
            _edges.value = _lastAvailableAt
            Log.i(TAG, "validated+reachable network edge at $_lastAvailableAt (nid=$nid)")
        }
    }

    private fun cancelProbeLocked() {
        probeJob?.cancel()
        probeJob = null
        probeOwner = null
        _probePending = false
    }

    private fun tryProbe(network: Network): Boolean {
        for ((host, port) in PROBE_TARGETS) {
            val socket = Socket()
            try {
                network.bindSocket(socket)
                socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                Log.i(TAG, "probe ok via $host:$port")
                return true
            } catch (e: Exception) {
                Log.d(TAG, "probe to $host:$port failed: ${e.message}")
            } finally {
                runCatching { socket.close() }
            }
        }
        return false
    }

    fun start() {
        synchronized(lock) {
            if (registration != null) return
            // A fresh callback per start: the stopped one's late events are told apart by identity.
            val next = Registration()
            registration = next
            runCatching {
                // No initial-state guard. ConnectivityManager replays the current
                // VALIDATED network state to a freshly-registered callback — that
                // replay IS the "internet is available now" edge we want users to
                // be able to react to on app launch. Repeat-fires from service
                // restarts are best handled by the rule's cooldown setting.
                cm.registerDefaultNetworkCallback(next.callback)
                Log.i(TAG, "started")
            }.onFailure {
                registration = null
                Log.w(TAG, "start failed: ${it.message}")
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            val current = registration ?: return
            registration = null
            runCatching { cm.unregisterNetworkCallback(current.callback) }
            current.trackedNetworkId = NO_NETWORK
            // The running probe belongs to the stopped registration: its result must change nothing.
            cancelProbeLocked()
        }
        Log.i(TAG, "stopped")
    }

    private companion object {
        const val TAG = "NetworkAvailMon"
        const val PROBE_TIMEOUT_MS = 2000
        const val NO_NETWORK = -1L
        // Yandex DNS primary/secondary + Quad9 as a neutral fallback. We
        // explicitly avoid 1.1.1.1 / 8.8.8.8: BYD ships a kernel routing
        // allow-list on the system APN (rmnet_data1, table 20) that keeps
        // ~80 OEM-related IPs reachable even when the user has toggled
        // "Cellular Data" off — and Cloudflare's 1.1.1.1 is one of them.
        // Probing it produced false-positive edges where regular apps had
        // zero internet but our trigger still fired. Yandex / Quad9 DNS are
        // not on that allow-list, so a successful TCP handshake to any of
        // them really does prove user-space apps have working internet.
        val PROBE_TARGETS: List<Pair<String, Int>> = listOf(
            "77.88.8.8" to 53,
            "77.88.8.1" to 53,
            "9.9.9.9" to 53,
        )
    }
}
