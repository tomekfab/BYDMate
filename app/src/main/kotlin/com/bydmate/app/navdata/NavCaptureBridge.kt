package com.bydmate.app.navdata

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

@Volatile private var captureBackend: NavCaptureBackend = DefaultNavCaptureBackend()

/** Debug-only capture lifecycle. It never feeds the normal guidance hub or diagnostics path. */
object NavCaptureBridge {
    val supportedPackages = setOf("com.google.android.apps.maps", "com.waze")

    fun captureActivityIntent(context: Context): Intent? = runCatching { captureBackend.captureActivityIntent(context) }.getOrNull()
    fun start(packageName: String, context: Context? = null): Boolean = captureBackend.lifecycleBackend()?.let {
        runCatching { it.start(packageName, context) }.getOrDefault(false)
    } ?: false
    fun stop() = captureBackend.lifecycleBackend()?.let { runCatching { it.stop() } }
    fun delete() = captureBackend.lifecycleBackend()?.let { runCatching { it.delete() } }
    fun saveTo(context: Context, destination: android.net.Uri): Boolean = captureBackend.lifecycleBackend()?.let {
        runCatching { it.saveTo(context, destination) }.getOrDefault(false)
    } ?: false
    fun snapshot(): NavCaptureSnapshot = captureBackend.lifecycleBackend()?.let {
        runCatching { it.snapshot() }.getOrDefault(NavCaptureSnapshot())
    } ?: NavCaptureSnapshot()
    fun selectedPackage(): String? = snapshot().packageName
    fun acceptsNotification(packageName: String): Boolean {
        if (packageName !in supportedPackages) return false
        val state = snapshot()
        return state.recording && state.packageName == packageName
    }

    internal fun replaceForTest(value: NavCaptureBackend) { captureBackend = value }
    internal fun resetForTest() { captureBackend = DefaultNavCaptureBackend() }
}

private fun NavCaptureBackend.lifecycleBackend(): NavCaptureLifecycleBackend? = this as? NavCaptureLifecycleBackend

internal fun flushNavCaptureForTest() = captureBackend.lifecycleBackend()?.flushForTest()

internal fun NavCaptureBridge.privateCaptureFileForTest(context: Context) =
    captureBackend.lifecycleBackend()?.privateCaptureFile(context)
        ?: java.io.File(context.filesDir, "nav-capture/unavailable.jsonl")

/** Records only one of the fixed UI state markers; release backends intentionally ignore it. */
fun recordNavCaptureMarker(packageName: String, marker: NavCaptureMarker) {
    if (packageName in NavCaptureBridge.supportedPackages) runCatching { captureBackend.marker(packageName, marker) }
}

/** Copied data only; it has no framework-owned object fields. */
data class NavCaptureNotification(
    val packageName: String,
    val id: Int,
    val channelId: String?,
    val title: CharSequence?,
    val text: CharSequence?,
    val extras: Map<String, Any?>,
    val flags: Int = 0,
    val smallIcon: String? = null,
    val largeIcon: String? = null,
    val remoteViewsPresent: Boolean = false,
)

/** Notification callbacks copy primitives before they queue bounded work. */
object NavCaptureNotificationCallbacks {
    fun onPosted(input: NavCaptureNotification) = submit("posted", input)
    fun onRemoved(input: NavCaptureNotification) = submit("removed", input)

    private fun submit(kind: String, input: NavCaptureNotification) {
        if (input.packageName !in NavCaptureBridge.supportedPackages) return
        val cuts = TruncationTracker()
        val record = NavCaptureRecord(
            kind = kind, packageName = input.packageName, timestampMs = System.currentTimeMillis(),
            id = input.id, channelId = input.channelId, title = cuts.text(input.title?.toString()),
            text = cuts.text(input.text?.toString()),
            extras = cuts.extras(primitiveExtras(input.extras)), flags = input.flags, smallIcon = input.smallIcon,
            largeIcon = input.largeIcon, remoteViewsPresent = input.remoteViewsPresent, metadata = cuts.metadata(),
        )
        runCatching { captureBackend.notification(record) }
    }

    private fun primitiveExtras(extras: Map<String, Any?>): Map<String, String> = extras.mapNotNull { (key, value) ->
        primitiveValue(value)?.let { key to it }
    }.toMap()

    private fun primitiveValue(value: Any?): String? = when (value) {
        is String, is CharSequence, is Int, is Long, is Boolean, is Float, is Double -> value.toString()
        else -> null
    }
}

/** A11y capture is independent of HUD state and precedes the Yandex-only feed. */
object NavCaptureA11yCallbacks {
    fun onEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName !in NavCaptureBridge.supportedPackages) return
        val cuts = TruncationTracker()
        val record = NavCaptureRecord(
            kind = "accessibility", packageName = packageName, timestampMs = System.currentTimeMillis(),
            className = cuts.text(event.className?.toString()),
            text = cuts.text(event.text.joinToString("\n") { it.toString() }),
            contentDescription = cuts.text(event.contentDescription?.toString()), eventType = event.eventType,
            displayId = if (Build.VERSION.SDK_INT >= 33) event.displayId else null, windowId = event.windowId,
            metadata = cuts.metadata(),
        )
        runCatching { captureBackend.accessibility(record) }
    }

    /** The caller owns [root]. This collector recycles only children it obtains. */
    fun onRoot(root: AccessibilityNodeInfo?) {
        val packageName = root?.packageName?.toString() ?: return
        if (packageName !in NavCaptureBridge.supportedPackages) return
        val collected = A11yTreeCollector().collect(root)
        val record = NavCaptureRecord(
            kind = "accessibility_tree", packageName = packageName, timestampMs = System.currentTimeMillis(),
            tree = collected.text,
            metadata = if (collected.truncated) TruncationTracker.TRUNCATED else emptyMap(),
        )
        runCatching { captureBackend.accessibility(record) }
    }
}

/** Bounds recorded text by serialized bytes and remembers whether anything was cut. */
private class TruncationTracker {
    private var cut = false

    fun text(value: String?): String? = value?.let {
        val (bounded, truncated) = NavCaptureBudget.truncate(it, NavCaptureBudget.TEXT_BYTES)
        cut = cut || truncated
        bounded
    }

    fun extras(value: Map<String, String>): Map<String, String> {
        val (bounded, truncated) = NavCaptureBudget.boundExtras(value, NavCaptureBudget.EXTRAS_BYTES, NavCaptureBudget.MAX_EXTRAS_KEYS)
        cut = cut || truncated
        return bounded
    }

    fun metadata(): Map<String, String> = if (cut) TRUNCATED else emptyMap()

    companion object {
        val TRUNCATED = mapOf("truncated" to "true")
    }
}

private data class CollectedTree(val text: String, val truncated: Boolean)

private class A11yTreeCollector {
    private val pending = ArrayDeque<AccessibilityNodeInfo>()
    private val lines = ArrayList<String>()

    private var usedBytes = 0
    private var truncated = false

    fun collect(root: AccessibilityNodeInfo): CollectedTree {
        pending.addLast(root)
        try {
            while (pending.isNotEmpty() && !truncated) {
                if (lines.size >= MAX_A11Y_NODES) truncated = true else visit(pending.removeFirst(), root)
            }
        } finally {
            recyclePending()
        }
        return CollectedTree(lines.joinToString("\n"), truncated)
    }

    private fun visit(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo) {
        try {
            if (addLine(describe(node))) addChildren(node)
        } finally {
            if (node !== root) runCatching { node.recycle() }
        }
    }

    /** Adds [line] if its serialized bytes (plus the escaped newline before it) fit; else adds a cut of it and stops. */
    private fun addLine(line: String): Boolean {
        val separator = if (lines.isEmpty()) 0 else NEWLINE_BYTES
        val cost = separator + NavCaptureBudget.serializedBytes(line)
        if (usedBytes + cost <= NavCaptureBudget.TREE_BYTES) {
            lines += line
            usedBytes += cost
            return true
        }
        truncated = true
        val room = NavCaptureBudget.TREE_BYTES - usedBytes - separator
        if (room > 0) {
            val (cut, _) = NavCaptureBudget.truncate(line, room)
            if (cut.isNotEmpty()) lines += cut
        }
        return false
    }

    private fun describe(node: AccessibilityNodeInfo): String = listOfNotNull(
        node.viewIdResourceName?.let { "id=$it" }, node.text?.let { "text=$it" },
        node.contentDescription?.let { "desc=$it" }, node.className?.let { "class=$it" },
    ).joinToString(" ")

    private fun addChildren(node: AccessibilityNodeInfo) {
        for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
    }

    private fun recyclePending() {
        while (pending.isNotEmpty()) runCatching { pending.removeFirst().recycle() }
    }

    private companion object {
        const val MAX_A11Y_NODES = 300
        const val NEWLINE_BYTES = 2   // "\n" escapes to two bytes in the JSON line
    }
}

internal val navCaptureOrdinaryDiagnostics: List<String> = emptyList()

interface NavCaptureBackend {
    fun notification(record: NavCaptureRecord)
    fun accessibility(record: NavCaptureRecord)
    fun captureActivityIntent(context: Context): Intent?
    fun marker(packageName: String, marker: NavCaptureMarker) = Unit
}

interface NavCaptureLifecycleBackend : NavCaptureBackend {
    fun start(packageName: String, context: Context?): Boolean = false
    fun stop() = Unit
    fun delete() = Unit
    fun snapshot(): NavCaptureSnapshot = NavCaptureSnapshot()
    fun privateCaptureFile(context: Context) = java.io.File(context.filesDir, "nav-capture/unavailable.jsonl")
    fun saveTo(context: Context, destination: android.net.Uri): Boolean = false
    fun flushForTest() = Unit
}

data class NavCaptureSnapshot(
    val recording: Boolean = false, val packageName: String? = null, val eventCount: Int = 0,
    val queuedRecords: Int = 0, val droppedRecords: Int = 0, val remainingMs: Long = 0,
    val stopReason: NavCaptureStopReason? = null,
    val error: NavCaptureError? = null,
    val oversizedRecords: Int = 0,
) {
    val canSave: Boolean get() = !recording && queuedRecords == 0 && eventCount > 0 &&
        (error == null || error == NavCaptureError.EXPORT_FAILED)
}

enum class NavCaptureError { DELETE_FAILED, RESET_FAILED, EXPORT_FAILED, WRITE_FAILED }

enum class NavCaptureStopReason { TIME_LIMIT, FILE_LIMIT, RECORD_TOO_LARGE, STOPPED, DELETED }

enum class NavCaptureMarker(val wireName: String) {
    GUIDING("guiding"),
    FOREGROUND("foreground"),
    BACKGROUND("background"),
    TURN("turn"),
    REROUTE("reroute"),
    ARRIVED("arrived"),
    CANCEL("cancel"),
}

data class NavCaptureRecord(
    val kind: String, val packageName: String, val timestampMs: Long, val id: Int? = null,
    val channelId: String? = null, val title: String? = null, val text: String? = null,
    val extras: Map<String, String> = emptyMap(), val flags: Int = 0, val smallIcon: String? = null,
    val largeIcon: String? = null, val className: String? = null, val contentDescription: String? = null,
    val eventType: Int? = null, val displayId: Int? = null, val windowId: Int? = null, val tree: String? = null,
    val remoteViewsPresent: Boolean = false, val marker: String? = null, val metadata: Map<String, String> = emptyMap(),
) {
    val containsFrameworkObject: Boolean get() = false
    companion object {
        fun notification(packageName: String, text: String) = NavCaptureRecord("notification", packageName,
            System.currentTimeMillis(), text = text)
    }
}
