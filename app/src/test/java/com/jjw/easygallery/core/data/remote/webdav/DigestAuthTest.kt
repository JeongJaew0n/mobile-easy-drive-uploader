package com.jjw.easygallery.core.data.remote.webdav

import kotlinx.coroutines.ExperimentalCoroutinesApi
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DigestAuthTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    /** RFC 7616 §3.9.1 벡터 */
    private val rfcChallenge = DigestAuth.Challenge(
        realm = "http-auth@example.org",
        nonce = "7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v",
        opaque = "FQhe/qaU925kfnzjCev0ciny7QMkPqMAFRtzCUYo5tdS",
        algorithm = "MD5",
        qop = "auth",
        stale = false,
    )
    private val rfcCnonce = "f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ"

    @Test
    fun `md5 response matches the rfc 7616 example`() {
        val response = DigestCalculator.response(
            username = "Mufasa",
            password = "Circle of Life",
            method = "GET",
            uri = "/dir/index.html",
            challenge = rfcChallenge,
            nc = 1,
            cnonce = rfcCnonce,
        )
        assertEquals("8ca523f5e9506fed4657c9700eebdbec", response)
    }

    @Test
    fun `sha-256 response matches the rfc 7616 example`() {
        val response = DigestCalculator.response(
            username = "Mufasa",
            password = "Circle of Life",
            method = "GET",
            uri = "/dir/index.html",
            challenge = rfcChallenge.copy(algorithm = "SHA-256"),
            nc = 1,
            cnonce = rfcCnonce,
        )
        assertEquals("753927fa0e85d155564e2e272a28d1802ca10daf4496794697cf8db5856cb6c1", response)
    }

    @Test
    fun `challenge parser handles quoted and bare params, picks qop auth, ignores basic`() {
        val header =
            """Digest realm="nas", qop="auth-int, auth", nonce=abc123, opaque="op", algorithm=SHA-256, stale=TRUE"""
        val challenge = DigestAuth.Challenge.parse(header)
        assertNotNull(challenge)
        assertEquals("nas", challenge!!.realm)
        assertEquals("abc123", challenge.nonce)
        assertEquals("auth", challenge.qop)
        assertEquals("SHA-256", challenge.algorithm)
        assertEquals("op", challenge.opaque)
        assertTrue(challenge.stale)
        assertNull(DigestAuth.Challenge.parse("""Basic realm="nas""""))
    }

    @Test
    fun `basic first, then answers a digest challenge and pre-authenticates later requests`() {
        val auth = DigestAuth("user", "pw")
        val client = OkHttpClient.Builder().addInterceptor(auth.interceptor).authenticator(auth.authenticator).build()
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .addHeader("WWW-Authenticate", """Digest realm="nas", nonce="n1", qop="auth", algorithm=MD5""")
                .build(),
        )
        server.enqueue(MockResponse(code = 207))
        server.enqueue(MockResponse(code = 201))

        client.newCall(Request.Builder().url(server.url("/dav/")).method("PROPFIND", "".toRequestBody()).build())
            .execute().use { assertEquals(207, it.code) }
        client.newCall(Request.Builder().url(server.url("/dav/a.jpg")).put("x".toRequestBody()).build())
            .execute().use { assertEquals(201, it.code) }

        assertTrue(server.takeRequest().headers["Authorization"]!!.startsWith("Basic "))
        val retry = server.takeRequest().headers["Authorization"]!!
        assertTrue(retry.startsWith("Digest username=\"user\", realm=\"nas\", nonce=\"n1\", uri=\"/dav/\""))
        assertTrue(retry.contains("nc=00000001"))
        val cnonce = Regex("cnonce=\"([^\"]+)\"").find(retry)!!.groupValues[1]
        val expected = DigestCalculator.response(
            username = "user",
            password = "pw",
            method = "PROPFIND",
            uri = "/dav/",
            challenge = DigestAuth.Challenge("nas", "n1", null, "MD5", "auth", false),
            nc = 1,
            cnonce = cnonce,
        )
        assertTrue(retry.contains("response=\"$expected\""))
        // 세 번째 요청(PUT)은 401 없이 바로 Digest, nc 증가
        val put = server.takeRequest().headers["Authorization"]!!
        assertTrue(put.startsWith("Digest "))
        assertTrue(put.contains("uri=\"/dav/a.jpg\""))
        assertTrue(put.contains("nc=00000002"))
    }

    @Test
    fun `a new nonce without stale is retried instead of locking the account out`() {
        val auth = DigestAuth("user", "pw")
        val client = OkHttpClient.Builder().addInterceptor(auth.interceptor).authenticator(auth.authenticator).build()
        // 1) Basic → 401(n1)  2) Digest(n1) → nonce 만료, stale 표시 없이 새 nonce  3) Digest(n2) → 성공
        server.enqueue(challenge("n1"))
        server.enqueue(challenge("n2"))
        server.enqueue(MockResponse(code = 207))

        client.newCall(Request.Builder().url(server.url("/dav/")).build())
            .execute().use { assertEquals(207, it.code) }

        assertEquals(3, server.requestCount)
        server.takeRequest()
        assertTrue(server.takeRequest().headers["Authorization"]!!.contains("nonce=\"n1\""))
        assertTrue(server.takeRequest().headers["Authorization"]!!.contains("nonce=\"n2\""))
    }

    @Test
    fun `a combined Basic and Digest header is still parsed`() {
        val challenge = DigestAuth.Challenge.parse("""Basic realm="x", Digest realm="nas", nonce="n1", qop="auth"""")
        assertNotNull(challenge)
        assertEquals("nas", challenge!!.realm)
        assertEquals("n1", challenge.nonce)
    }

    @Test
    fun `an uppercase -sess algorithm still uses the right hash`() {
        val sess = rfcChallenge.copy(algorithm = "SHA-256-SESS")
        val lower = rfcChallenge.copy(algorithm = "sha-256-sess")
        assertEquals(
            DigestCalculator.response("u", "p", "GET", "/x", lower, nc = 1, cnonce = "c"),
            DigestCalculator.response("u", "p", "GET", "/x", sess, nc = 1, cnonce = "c"),
        )
    }

    private fun challenge(nonce: String) = MockResponse.Builder()
        .code(401)
        .addHeader("WWW-Authenticate", """Digest realm="nas", nonce="$nonce", qop="auth", algorithm=MD5""")
        .build()

    @Test
    fun `a second 401 with the same nonce gives up instead of looping`() {
        val auth = DigestAuth("user", "wrong")
        val client = OkHttpClient.Builder().addInterceptor(auth.interceptor).authenticator(auth.authenticator).build()
        repeat(3) {
            server.enqueue(
                MockResponse.Builder()
                    .code(401)
                    .addHeader("WWW-Authenticate", """Digest realm="nas", nonce="n1", qop="auth"""")
                    .build(),
            )
        }

        client.newCall(Request.Builder().url(server.url("/dav/")).build()).execute().use { assertEquals(401, it.code) }

        assertEquals(2, server.requestCount)
    }
}
