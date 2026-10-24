package com.bydmate.app.navdata.waze

import android.view.accessibility.AccessibilityNodeInfo

/** Reads the two Waze nav-bar text nodes from a Waze window root. The caller owns the root and
 *  recycles it; every node found here is recycled here. Framework calls can throw on a stale node. */
object WazeNavBarReader {
    const val PACKAGE = "com.waze"
    private const val DISTANCE_ID = "com.waze:id/navBarDistance"
    private const val STREET_ID = "com.waze:id/navBarStreetLine"

    /** The raw texts of the two nav-bar nodes; null when the node is missing or blank. */
    data class Texts(val distance: String?, val street: String?)

    /** Null for a null root or a root of another package; else the first non-blank text per id. */
    fun read(root: AccessibilityNodeInfo?): Texts? {
        root ?: return null
        val pkg = runCatching { root.packageName?.toString() }.getOrNull()
        if (pkg != PACKAGE) return null
        return Texts(firstText(root, DISTANCE_ID), firstText(root, STREET_ID))
    }

    /** Active-window policy (D2): [active] is returned only when it is a Waze window; any other
     *  root is recycled here and null is returned. No window list is ever consulted. */
    fun activeRoot(active: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        active ?: return null
        if (runCatching { active.packageName?.toString() }.getOrNull() == PACKAGE) return active
        recycle(active)
        return null
    }

    private fun firstText(root: AccessibilityNodeInfo, viewId: String): String? {
        val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }.getOrNull() ?: return null
        var first: String? = null
        for (node in nodes) {
            try {
                if (first == null) {
                    first = runCatching { node.text?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
                }
            } finally {
                recycle(node)
            }
        }
        return first
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        runCatching { node.recycle() }
    }
}
