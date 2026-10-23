package com.bydmate.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
private class TestTls {
    val serverContext: SSLContext
    private val clientContext: SSLContext
    private val trustManager: X509TrustManager

    init {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=bobs-mac-mini-openclaw.tail3549c7.ts.net")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now), Date(now - 60_000), Date(now + 3_600_000), name, pair.public,
        )
        builder.addExtension(
            Extension.subjectAlternativeName, false,
            GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, "bobs-mac-mini-openclaw.tail3549c7.ts.net"), GeneralName(GeneralName.iPAddress, "127.0.0.1"))),
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)))

        val serverKeys = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", pair.private, CharArray(0), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(serverKeys, CharArray(0)) }
        serverContext = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }

        val trusted = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("server", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
        trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
        clientContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    }

    /** The production client, plus trust for the test certificate. */
    fun trustingClient(): OkHttpClient =
        OkHttpClient().newBuilder().sslSocketFactory(clientContext.socketFactory, trustManager).build()
}

class DiagnosticUploadClientTest {
    @Test fun malformedAcknowledgementIsTerminal() {
        val client = DiagnosticUploadClient()
        for (response in listOf("{}", "not json", "{\"batchId\":\"id\",\"digest\":\"wrong\"}", "{\"batchId\":\"id\",\"digest\":\"digest\",\"extra\":1}"))
            assertEquals(UploadOutcome.TERMINAL, client.acknowledge("id", "digest", response))
        assertEquals(UploadOutcome.SENT, client.acknowledge("id", "digest", "{\"batchId\":\"id\",\"digest\":\"digest\"}"))
    }
    @Test fun pinnedTlsSameBytesRetryAndRedirectsHaveNoFollowups() = runBlocking {
        val tls = TestTls(); val server = MockWebServer()
        server.useHttps(tls.serverContext.socketFactory, false); server.start(9443)
        try {
            val client = DiagnosticUploadClient(tls.trustingClient().newBuilder().dns(object : okhttp3.Dns { override fun lookup(hostname: String) = listOf(java.net.InetAddress.getByName("127.0.0.1")) }).build())
            val record = DiagnosticBody("12345678-1234-4234-8234-123456789abc", 1, "original bytes".toByteArray())
            val credentials = DiagnosticCredentials("a".repeat(43))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(JSONObject().put("batchId", record.batchId).put("digest", record.digest).toString()))
            assertEquals(UploadOutcome.RETRY, client.upload(record, credentials))
            assertEquals(UploadOutcome.SENT, client.upload(record, credentials))
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("original bytes", request.body.readUtf8())
                assertEquals(record.digest, request.getHeader("X-BYDMate-Digest"))
                assertEquals(record.batchId, request.getHeader("X-BYDMate-Batch"))
                assertEquals("Bearer " + "a".repeat(43), request.getHeader("Authorization"))
                assertEquals("bobs-mac-mini-openclaw.tail3549c7.ts.net:9443", request.getHeader("Host") ?: request.getHeader(":authority"))
            }
            for (scheme in listOf("http", "https")) {
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "$scheme://bobs-mac-mini-openclaw.tail3549c7.ts.net:9443/steal"))
                assertEquals(UploadOutcome.TERMINAL, client.upload(record, credentials))
                assertNotNull(server.takeRequest())
                assertNull(server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            }
            for ((status, outcome) in listOf(401 to UploadOutcome.BLOCKED, 403 to UploadOutcome.BLOCKED, 409 to UploadOutcome.TERMINAL, 400 to UploadOutcome.TERMINAL, 500 to UploadOutcome.RETRY)) {
                server.enqueue(MockResponse().setResponseCode(status))
                assertEquals(outcome, client.upload(record, credentials)); server.takeRequest()
            }
            assertFalse(client.transport.followRedirects); assertFalse(client.transport.followSslRedirects)
        } finally { server.shutdown() }
    }
    @Test fun configMatchesReceiverVectorsAndNeverPrintsBearer() {
        val vectors = JSONObject(javaClass.getResource("/diagnostics/receiver-vectors.json")!!.readText())
        val credentials = DiagnosticUploadConfig.parse(vectors.getString("acceptedImportJson"))!!
        assertFalse(credentials.toString().contains(credentials.bearerToken))
        val rejected = vectors.getJSONArray("rejectedImportJson")
        for (i in 0 until rejected.length()) assertNull("rejected import $i", DiagnosticUploadConfig.parse(rejected.getString(i)))
    }
}
