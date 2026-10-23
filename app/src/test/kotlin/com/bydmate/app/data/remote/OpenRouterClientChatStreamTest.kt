package com.bydmate.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class OpenRouterClientChatStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenRouterClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenRouterClient(OkHttpClient())
        client.baseUrlForTest = server.url("/api/v1").toString().trimEnd('/')
    }

    @After fun tearDown() { server.shutdown() }

    private fun messages(): JSONArray = JSONArray()
        .put(JSONObject().put("role", "user").put("content", "cześć"))

    private fun sse(vararg events: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("\n\n", postfix = "\n\n"))

    private fun chunk(delta: String): String =
        """data: {"choices":[{"delta":{"content":${JSONObject.quote(delta)}}}]}"""

    @Test
    fun `content deltas are forwarded and accumulated`() = runTest {
        server.enqueue(sse(chunk("Cze"), chunk("ść."), "data: [DONE]"))
        val deltas = mutableListOf<String>()
        val msg = client.chatStream("u", "k", "m", messages(), null) { deltas += it }.getOrThrow()
        assertEquals(listOf("Cze", "ść."), deltas)
        assertEquals("Cześć.", msg.getString("content"))
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(sent.getBoolean("stream"))
    }

    // A reply cut at max_tokens arrives as finish_reason=length on the last chunk; the agent
    // can only see it if it is carried out on the message.
    @Test
    fun `finish reason from the stream reaches the returned message`() = runTest {
        server.enqueue(sse(
            chunk("Trasa"),
            """data: {"choices":[{"delta":{},"finish_reason":"length"}]}""",
            "data: [DONE]",
        ))
        val msg = client.chatStream("u", "k", "m", messages(), null) {}.getOrThrow()
        assertEquals("length", msg.getString(OpenRouterClient.FINISH_REASON))
    }

    @Test
    fun `extras are merged into the streaming payload`() = runTest {
        server.enqueue(sse(chunk("OK."), "data: [DONE]"))
        val extras = JSONObject().put("thinking", JSONObject().put("type", "disabled"))
        client.chatStream("u", "k", "m", messages(), null, extras) {}.getOrThrow()
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("disabled", sent.getJSONObject("thinking").getString("type"))
        assertTrue(sent.getBoolean("stream"))
    }

    @Test
    fun `without extras the streaming payload has no provider fields`() = runTest {
        server.enqueue(sse(chunk("OK."), "data: [DONE]"))
        client.chatStream("u", "k", "m", messages(), null) {}.getOrThrow()
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(!sent.has("reasoning"))
        assertTrue(!sent.has("thinking"))
    }

    @Test
    fun `usage chunk does not disturb the assembled message`() = runTest {
        server.enqueue(sse(
            chunk("OK."),
            """data: {"choices":[],"usage":{"prompt_tokens":900,"prompt_tokens_details":{"cached_tokens":850}}}""",
            "data: [DONE]",
        ))
        val deltas = mutableListOf<String>()
        val msg = client.chatStream("u", "k", "m", messages(), null) { deltas += it }.getOrThrow()
        assertEquals(listOf("OK."), deltas)
        assertEquals("OK.", msg.getString("content"))
    }

    @Test
    fun `comment keepalive lines are ignored`() = runTest {
        server.enqueue(sse(": OPENROUTER PROCESSING", chunk("OK."), "data: [DONE]"))
        val deltas = mutableListOf<String>()
        val msg = client.chatStream("u", "k", "m", messages(), null) { deltas += it }.getOrThrow()
        assertEquals(listOf("OK."), deltas)
        assertEquals("OK.", msg.getString("content"))
    }

    @Test
    fun `tool call fragments merge by index and mute later content`() = runTest {
        server.enqueue(sse(
            chunk("Już sprawdzam. "),
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"get_vehicle_state","arguments":"{\"fi"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"elds\":1}"}}]}}]}""",
            chunk("tekst po narzędziu"),
            "data: [DONE]",
        ))
        val deltas = mutableListOf<String>()
        val msg = client.chatStream("u", "k", "m", messages(), null) { deltas += it }.getOrThrow()
        assertEquals(listOf("Już sprawdzam. "), deltas)
        val tc = msg.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("c1", tc.getString("id"))
        assertEquals("get_vehicle_state", tc.getJSONObject("function").getString("name"))
        assertEquals("""{"fields":1}""", tc.getJSONObject("function").getString("arguments"))
    }

    @Test
    fun `mid stream error event fails the result`() = runTest {
        server.enqueue(sse(chunk("Pocz"), """data: {"error":{"code":"server_error","message":"boom"},"choices":[{"delta":{"content":""},"finish_reason":"error"}]}"""))
        val result = client.chatStream("u", "k", "m", messages(), null) {}
        assertTrue(result.isFailure)
    }

    @Test
    fun `non sse json body falls back to whole completion`() = runTest {
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"role":"assistant","content":"W całości."}}]}"""))
        val deltas = mutableListOf<String>()
        val msg = client.chatStream("u", "k", "m", messages(), null) { deltas += it }.getOrThrow()
        assertEquals(listOf("W całości."), deltas)
        assertEquals("W całości.", msg.getString("content"))
    }

    @Test
    fun `http error maps to LlmHttpException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        val result = client.chatStream("u", "k", "m", messages(), null) {}
        assertEquals(429, (result.exceptionOrNull() as LlmHttpException).code)
    }

    @Test
    fun `coroutine cancellation aborts a stalled stream promptly`() = runBlocking {
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n")
            // Short period: after the client cancels, MockWebServer's write thread must wake from
            // its throttle sleep and notice the broken socket within its own shutdown() timeout,
            // or tearDown()'s server.shutdown() fails with "gave up waiting for queue to shut down".
            .throttleBody(1, 2, TimeUnit.SECONDS))
        val job = launch(Dispatchers.IO) {
            client.chatStream("u", "k", "m", messages(), null) {}
        }
        delay(500)
        val elapsedMs = measureTimeMillis { job.cancelAndJoin() }
        assertTrue("cancellation took ${elapsedMs}ms, expected well under the 75s read timeout", elapsedMs < 5000)
    }
}
