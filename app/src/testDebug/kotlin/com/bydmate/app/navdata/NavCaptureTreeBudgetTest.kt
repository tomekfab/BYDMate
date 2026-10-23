package com.bydmate.app.navdata

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Byte-budget behaviour of the recorder callbacks, measured on the lines a real store writes. */
@Suppress("DEPRECATION")   // recycle() is the pooling contract the collector keeps
@RunWith(RobolectricTestRunner::class)
class NavCaptureTreeBudgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory get() = File(context.filesDir, "nav-capture")

    private class FakeSink : NavCaptureStore.Sink {
        val lines = mutableListOf<String>()
        override fun append(line: String): Boolean = lines.add(line.removeSuffix("\n"))
        override fun delete(): Boolean { lines.clear(); return true }
    }

    private val sink = FakeSink()
    private val store = NavCaptureStore(clockMs = { 100L }, sink = sink)

    @Before
    fun setUp() {
        directory.deleteRecursively()
        assertTrue(store.start("com.waze"))
        NavCaptureBridge.replaceForTest(object : NavCaptureBackend {
            override fun notification(record: NavCaptureRecord) = store.record(record)
            override fun accessibility(record: NavCaptureRecord) = store.record(record)
            override fun captureActivityIntent(context: Context) = null
        })
    }

    @After
    fun tearDown() {
        NavCaptureBridge.resetForTest()
        directory.deleteRecursively()
    }

    private fun node(
        id: String? = null, text: String? = null, className: String? = null, children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo = mockk<AccessibilityNodeInfo>(relaxed = true).also { node ->
        every { node.packageName } returns "com.waze"
        every { node.viewIdResourceName } returns id
        every { node.text } returns text
        every { node.contentDescription } returns null
        every { node.className } returns className
        every { node.childCount } returns children.size
        children.forEachIndexed { index, child -> every { node.getChild(index) } returns child }
    }

    private fun assertHealthy(expectedLines: Int = 1) {
        val state = store.snapshot()
        assertNull(state.stopReason)
        assertTrue(state.recording)
        assertEquals(0, state.oversizedRecords)
        assertEquals(expectedLines, sink.lines.size)
        assertTrue(sink.lines.all { it.toByteArray(Charsets.UTF_8).size <= NavCaptureStore.MAX_RECORD_BYTES })
    }

    private fun metadata(line: String): JSONObject = JSONObject(line).getJSONObject("metadata")

    @Test
    fun `a 300 node tree is cut by bytes and says so`() {
        val polish = "Zażółć gęślą jaźń ".repeat(5).take(80)
        val children = (0 until 299).map { node(id = "com.waze:id/row_$it", text = polish, className = "android.widget.TextView") }
        val root = node(className = "android.widget.FrameLayout", children = children)

        NavCaptureA11yCallbacks.onRoot(root)

        assertHealthy()
        val record = JSONObject(sink.lines.single())
        assertEquals("true", metadata(sink.lines.single()).getString("truncated"))
        val tree = record.getString("tree")
        assertTrue(tree.startsWith("class=android.widget.FrameLayout\nid=com.waze:id/row_0 text=Zażółć"))
        assertTrue(NavCaptureBudget.serializedBytes(tree) <= NavCaptureBudget.TREE_BYTES)
        assertTrue(NavCaptureBudget.serializedBytes(tree) > NavCaptureBudget.TREE_BYTES - 400)
    }

    @Test
    fun `a 10 node tree that fits loses nothing and carries no truncated key`() {
        val children = (0 until 9).map { node(id = "com.waze:id/row_$it", text = "Zażółć $it", className = "android.widget.TextView") }
        val root = node(className = "android.widget.FrameLayout", children = children)

        NavCaptureA11yCallbacks.onRoot(root)

        assertHealthy()
        assertFalse(metadata(sink.lines.single()).has("truncated"))
        val expected = (listOf("class=android.widget.FrameLayout") +
            (0 until 9).map { "id=com.waze:id/row_$it text=Zażółć $it class=android.widget.TextView" }).joinToString("\n")
        assertEquals(expected, JSONObject(sink.lines.single()).getString("tree"))
    }

    @Test
    fun `one over-budget node line is cut to the remaining bytes and the walk stops`() {
        val huge = "ł".repeat(20_000)
        val later = node(id = "com.waze:id/later", text = "never reached")
        val root = node(text = huge, children = listOf(later))

        NavCaptureA11yCallbacks.onRoot(root)

        assertHealthy()
        assertEquals("true", metadata(sink.lines.single()).getString("truncated"))
        val tree = JSONObject(sink.lines.single()).getString("tree")
        assertTrue(tree.startsWith("text=łłł"))
        assertFalse("later" in tree)
        // "text=" is 5 bytes and every ł is 2, so the 10,240-byte budget fills to 10,239.
        assertEquals(NavCaptureBudget.TREE_BYTES - 1, NavCaptureBudget.serializedBytes(tree))
    }

    @Test
    fun `newline separators are counted when the tree fills the budget exactly`() {
        // Each line is "text=" + 35 x = 40 bytes; with its 2-byte escaped newline the cost is 42.
        val line = "x".repeat(35)
        val children = (0 until 299).map { node(text = line) }
        val root = node(text = line, children = children)

        NavCaptureA11yCallbacks.onRoot(root)

        assertHealthy()
        val tree = JSONObject(sink.lines.single()).getString("tree")
        val rows = tree.split("\n")
        assertEquals(NavCaptureBudget.TREE_BYTES, NavCaptureBudget.serializedBytes(tree))
        assertEquals(244, rows.size)
        assertTrue(rows.dropLast(1).all { it == "text=$line" })
        assertEquals(34, rows.last().length)
        assertEquals("true", metadata(sink.lines.single()).getString("truncated"))
    }

    @Test
    fun `an accessibility event over the text budget is bounded and flagged`() {
        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.packageName } returns "com.waze"
        every { event.className } returns "c".repeat(5_000)
        every { event.contentDescription } returns "d".repeat(5_000)
        every { event.text } returns mutableListOf<CharSequence>("Zażółć".repeat(2_000), "second")

        NavCaptureA11yCallbacks.onEvent(event)

        assertHealthy()
        val record = JSONObject(sink.lines.single())
        assertEquals("true", metadata(sink.lines.single()).getString("truncated"))
        assertEquals(NavCaptureBudget.TEXT_BYTES, NavCaptureBudget.serializedBytes(record.getString("class")))
        assertEquals(NavCaptureBudget.TEXT_BYTES, NavCaptureBudget.serializedBytes(record.getString("contentDescription")))
        assertTrue(NavCaptureBudget.serializedBytes(record.getString("text")) <= NavCaptureBudget.TEXT_BYTES)
        assertTrue(record.getString("text").startsWith("ZażółćZażółć"))
    }

    @Test
    fun `a small accessibility event passes through untouched`() {
        val event = mockk<AccessibilityEvent>(relaxed = true)
        every { event.packageName } returns "com.waze"
        every { event.className } returns "android.widget.TextView"
        every { event.contentDescription } returns null
        every { event.text } returns mutableListOf<CharSequence>("Skręć w prawo")

        NavCaptureA11yCallbacks.onEvent(event)

        assertHealthy()
        assertFalse(metadata(sink.lines.single()).has("truncated"))
        assertEquals("Skręć w prawo", JSONObject(sink.lines.single()).getString("text"))
    }

    private fun bigNotification(): NavCaptureNotification = NavCaptureNotification(
        packageName = "com.waze", id = 7, channelId = "nav", title = "T".repeat(5 * 1024), text = "Skręć w prawo",
        extras = linkedMapOf<String, Any?>().also { extras ->
            (0 until 40).forEach { extras["k%02d".format(it)] = "e".repeat(1024) }
            extras["unsupported"] = Any()
        },
    )

    @Test
    fun `a notification with a 5 KiB title and 40 KiB of extras is bounded and flagged`() {
        NavCaptureNotificationCallbacks.onPosted(bigNotification())

        assertHealthy()
        val record = JSONObject(sink.lines.single())
        assertEquals("true", metadata(sink.lines.single()).getString("truncated"))
        assertEquals("T".repeat(NavCaptureBudget.TEXT_BYTES), record.getString("title"))
        assertEquals("Skręć w prawo", record.getString("text"))
        val extras = record.getJSONObject("extras")
        val keys = extras.keys().asSequence().toList()
        assertTrue(keys.isNotEmpty() && keys.size < 40)
        assertEquals(keys.sorted(), keys.indices.map { "k%02d".format(it) }.sorted())
        assertEquals("e".repeat(1024), extras.getString("k00"))
        assertFalse(extras.has("k39"))
        assertFalse(extras.has("unsupported"))
    }

    @Test
    fun `a small notification carries no truncated key`() {
        NavCaptureNotificationCallbacks.onPosted(NavCaptureNotification(
            packageName = "com.waze", id = 1, channelId = "nav", title = "Aleje", text = "Skręć",
            extras = mapOf("step" to 2),
        ))

        assertHealthy()
        assertFalse(metadata(sink.lines.single()).has("truncated"))
        assertEquals("2", JSONObject(sink.lines.single()).getJSONObject("extras").getString("step"))
    }

    private fun realBackendLines(block: () -> Unit): List<String> {
        val backend = DefaultNavCaptureBackend()
        NavCaptureBridge.replaceForTest(backend)
        assertTrue(backend.start("com.waze", context))
        block()
        backend.flushForTest()
        backend.stop()
        backend.flushForTest()
        return (NavCaptureBridge.privateCaptureFileForTest(context)).readLines().filter { it.isNotBlank() }
    }

    @Test
    fun `the real backend keeps the truncated flag next to the fixed app metadata`() {
        val lines = realBackendLines { NavCaptureNotificationCallbacks.onPosted(bigNotification()) }

        assertEquals(1, lines.size)
        val meta = metadata(lines.single())
        assertEquals("true", meta.getString("truncated"))
        assertTrue(meta.has("appVersion"))
        assertTrue(meta.has("installSource"))
    }

    @Test
    fun `the real backend keeps the truncated flag of a tree record`() {
        val children = (0 until 299).map { node(id = "com.waze:id/row_$it", text = "Zażółć gęślą jaźń ".repeat(5)) }
        val root = node(children = children)

        val lines = realBackendLines { NavCaptureA11yCallbacks.onRoot(root) }

        assertEquals(1, lines.size)
        val meta = metadata(lines.single())
        assertEquals("true", meta.getString("truncated"))
        assertTrue(meta.has("appVersion"))
        assertTrue(meta.has("installSource"))
    }

    @Test
    fun `the real backend fixed metadata fields win over caller keys`() {
        val backend = DefaultNavCaptureBackend()
        NavCaptureBridge.replaceForTest(backend)
        assertTrue(backend.start("com.waze", context))
        backend.notification(NavCaptureRecord("notification", "com.waze", 1L, metadata = mapOf("appVersion" to "spoofed", "x" to "y")))
        backend.flushForTest()
        backend.stop()
        backend.flushForTest()

        val meta = metadata(NavCaptureBridge.privateCaptureFileForTest(context).readLines().single())
        assertEquals("y", meta.getString("x"))
        assertTrue(meta.getString("appVersion") != "spoofed")
    }
}
