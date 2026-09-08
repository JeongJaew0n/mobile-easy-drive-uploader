package com.jjw.easygallery.core.data.remote

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.net.ssl.SSLException

class PinnedTlsTest {

    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate

    @Before
    fun setUp() {
        // 자체 서명 인증서로 TLS 서버를 띄운다(NAS 흉내)
        certificate = HeldCertificate.Builder().commonName("nas.local").addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory())
        server.start()
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `default client rejects the self signed certificate and fingerprint can still be read`() {
        server.enqueue(MockResponse(code = 200))
        val error = runCatching {
            OkHttpClient().newCall(Request.Builder().url(server.url("/")).head().build()).execute()
        }.exceptionOrNull()
        assertTrue("TLS 오류여야 함: $error", error != null && error.isTlsFailure())

        server.enqueue(MockResponse(code = 200))
        val fingerprint = fetchServerCertificateSha256(OkHttpClient(), server.url("/").toString())
        assertEquals(certificate.certificate.sha256Hex(), fingerprint)
    }

    @Test
    fun `pinned client connects when the fingerprint matches and fails otherwise`() {
        server.enqueue(MockResponse(code = 204))
        val pinned = OkHttpClient.Builder().pinCertificate(certificate.certificate.sha256Hex()).build()
        pinned.newCall(Request.Builder().url(server.url("/")).head().build()).execute().use {
            assertEquals(204, it.code)
        }

        val wrong = OkHttpClient.Builder().pinCertificate("00".repeat(32)).build()
        val error = runCatching {
            wrong.newCall(Request.Builder().url(server.url("/")).head().build()).execute()
        }.exceptionOrNull()
        assertTrue("지문 불일치는 SSL 오류여야 함: $error", error is SSLException || error?.isTlsFailure() == true)
    }

    @Test
    fun `fingerprint display groups bytes with colons`() {
        assertEquals("AB:CD:EF", "abcdef".toFingerprintDisplay())
    }
}
