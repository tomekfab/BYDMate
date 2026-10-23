package com.bydmate.app.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDiagnosticEventParserTest {
    @Test fun receiverCompatibilityVectors() {
        val vectors = JSONObject(javaClass.getResource("/diagnostics/receiver-vectors.json")!!.readText())
        assertTrue(RemoteDiagnosticEventParser.validBatch(vectors.getString("acceptedBatchJson").toByteArray()))
        val rejected = vectors.getJSONArray("rejectedBatchJson")
        for (i in 0 until rejected.length()) assertFalse("rejected $i", RemoteDiagnosticEventParser.validBatch(rejected.getString(i).toByteArray()))
    }
    @Test fun strictJsonSyntaxAndEventBoundaries() {
        val valid = """{"type":"voice.lifecycle","atMs":1,"state":"done","durationMs":0}"""
        assertNotNull(RemoteDiagnosticEventParser.parse(valid))
        for (invalid in listOf(valid + " trailing", valid.replace("\"type\"", "type"), valid.replace('"', '\''),
            valid.replace("\"durationMs\":0", "\"durationMs\":0.5"), valid.replace("\"durationMs\":0", "\"durationMs\":300001"),
            valid.replace("\"durationMs\":0", "\"durationMs\":0,\"text\":\"secret\""), valid.replace("\"atMs\":1", "\"atMs\":true"))) {
            assertNull(invalid, RemoteDiagnosticEventParser.parse(invalid))
        }
    }
    @Test fun emitterTemplatesRunOnIoAndNeverIncludeErrorMessages() {
        val received = java.util.concurrent.LinkedBlockingQueue<RemoteDiagnosticEvent>()
        val callerThread = Thread.currentThread().id
        val emitterThreads = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        DiagnosticEmitters.install { event -> emitterThreads.add(Thread.currentThread().id); received.offer(event) }
        try {
            DiagnosticEmitters.lifecycle("listening")
            for (backend in listOf("local_asr", "local_tts", "openrouter", "openclaw")) DiagnosticEmitters.request(backend, "load", 50)
            DiagnosticEmitters.failure("openclaw", IllegalArgumentException("SECRET"))
            DiagnosticEmitters.voiceFailure("speaking", object : java.io.IOException("SECRET") {})
            val events = (1..7).map { received.poll(5, java.util.concurrent.TimeUnit.SECONDS)!! }
            for (event in events) {
                org.junit.Assert.assertNotNull(RemoteDiagnosticEventParser.parse(event.json().toString()))
                assertFalse(event.json().toString().contains("SECRET"))
            }
            org.junit.Assert.assertEquals("Other", events.last().json().getString("exceptionClass"))
            assertTrue(emitterThreads.all { it != callerThread })
        } finally { DiagnosticEmitters.install(null) }
    }
}

class OpenRouterCallerDiagnosticTest {
    @Test fun callerEmitsMeasuredTimingAndSafeTransportFailure() = kotlinx.coroutines.runBlocking {
        val received = java.util.concurrent.LinkedBlockingQueue<RemoteDiagnosticEvent>()
        var fail = false
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            if (fail) throw java.io.IOException("SECRET exception")
            Thread.sleep(30)
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body("""{"choices":[{"message":{"content":"SECRET provider body"}}]}"""
                    .let { okhttp3.ResponseBody.create(null, it) }).build()
        }.build()
        val client = com.bydmate.app.data.remote.OpenRouterClient(http)
        DiagnosticEmitters.install { received.offer(it) }
        try {
            val messages = org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "SECRET transcript"))
            for (stream in listOf(false, true)) {
                if (stream) client.chatStream("https://example.invalid", "SECRET token", "SECRET model", messages, null) {}
                else client.chatRaw("https://example.invalid", "SECRET token", "SECRET model", messages, null)
                val event = received.poll(2, java.util.concurrent.TimeUnit.SECONDS)!!
                assertTrue(event.json().getLong("durationMs") >= 20)
                assertFalse(event.json().toString().contains("SECRET"))
            }
            fail = true
            assertTrue(client.chatRaw("https://example.invalid", "SECRET token", "SECRET model", messages, null).isFailure)
            val failure = received.poll(2, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(failure)
            org.junit.Assert.assertEquals("connection.failure", failure!!.json().getString("type"))
            assertFalse(failure.json().toString().contains("SECRET"))
        } finally { DiagnosticEmitters.install(null) }
    }
}
