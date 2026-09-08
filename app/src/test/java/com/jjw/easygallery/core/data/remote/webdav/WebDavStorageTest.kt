package com.jjw.easygallery.core.data.remote.webdav

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jjw.easygallery.core.data.remote.RemoteStorageException
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WebDavStorageTest {

    private lateinit var server: MockWebServer
    private lateinit var storage: WebDavStorage

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context: Context = ApplicationProvider.getApplicationContext()
        val account = RemoteAccount(
            id = "nas",
            kind = RemoteAccountKind.WEBDAV,
            displayName = "NAS",
            endpoint = server.url("/photos").toString(),
            username = "user",
        )
        storage = WebDavStorage(context, account, "pw", OkHttpClient(), UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `listChildren parses multistatus, skips self, decodes korean paths`() = runTest {
        server.enqueue(
            multistatus(
                """<?xml version="1.0"?>
                <D:multistatus xmlns:D="DAV:">
                  <D:response><D:href>/photos/2026/</D:href><D:propstat><D:prop>
                    <D:displayname>2026</D:displayname><D:resourcetype><D:collection/></D:resourcetype>
                  </D:prop></D:propstat></D:response>
                  <D:response><D:href>/photos/2026/%EC%97%AC%ED%96%89/</D:href><D:propstat><D:prop>
                    <D:displayname>여행</D:displayname><D:resourcetype><D:collection/></D:resourcetype>
                  </D:prop></D:propstat></D:response>
                  <D:response><D:href>/photos/2026/b.jpg</D:href><D:propstat><D:prop>
                    <D:displayname>b.jpg</D:displayname><D:getcontentlength>1234</D:getcontentlength>
                    <D:getcontenttype>image/jpeg</D:getcontenttype>
                    <D:getlastmodified>Mon, 01 Sep 2026 00:00:00 GMT</D:getlastmodified><D:resourcetype/>
                  </D:prop></D:propstat></D:response>
                </D:multistatus>""",
            ),
        )

        val page = storage.listChildren("/2026/")

        assertEquals(listOf("여행", "b.jpg"), page.entries.map { it.name })
        assertEquals("/2026/여행/", page.entries[0].id)
        assertTrue(page.entries[0].isFolder)
        assertEquals(1_234L, page.entries[1].sizeBytes)
        assertEquals("image/jpeg", page.entries[1].mimeType)
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("/photos/2026/", request.url.encodedPath)
        assertEquals("1", request.headers["Depth"])
        assertTrue(request.headers["Authorization"]!!.startsWith("Basic "))
    }

    @Test
    fun `createFolder sends MKCOL and rename sends MOVE with absolute destination`() = runTest {
        server.enqueue(MockResponse(code = 201))
        val folder = storage.createFolder("여행", "/2026/")
        assertEquals("/2026/여행/", folder.id)
        val mkcol = server.takeRequest()
        assertEquals("MKCOL", mkcol.method)
        assertEquals("/photos/2026/%EC%97%AC%ED%96%89/", mkcol.url.encodedPath)

        server.enqueue(MockResponse(code = 201))
        val renamed = storage.rename("/2026/b.jpg", "c.jpg")
        assertEquals("/2026/c.jpg", renamed.id)
        val move = server.takeRequest()
        assertEquals("MOVE", move.method)
        assertEquals("/photos/2026/b.jpg", move.url.encodedPath)
        assertEquals(server.url("/photos/2026/c.jpg").toString(), move.headers["Destination"])
        assertEquals("F", move.headers["Overwrite"])
    }

    @Test
    fun `move keeps the name under the target folder and delete sends DELETE`() = runTest {
        server.enqueue(MockResponse(code = 201))
        val moved = storage.move("/2026/b.jpg", "/2026/", "/archive/")
        assertEquals("/archive/b.jpg", moved.id)
        assertEquals(server.url("/photos/archive/b.jpg").toString(), server.takeRequest().headers["Destination"])

        server.enqueue(MockResponse(code = 204))
        storage.delete("/archive/b.jpg")
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `401 surfaces as a storage exception with the status code`() = runTest {
        server.enqueue(MockResponse(code = 401))

        val error = runCatching { storage.listChildren("/") }.exceptionOrNull()

        assertTrue(error is RemoteStorageException)
        assertEquals(401, (error as RemoteStorageException).httpCode)
    }

    @Test
    fun `relativePath strips the endpoint prefix from absolute and relative hrefs`() {
        val base = server.url("/photos")
        assertEquals("/2026/a b.jpg", WebDavXml.relativePath("/photos/2026/a%20b.jpg", base))
        assertEquals("/2026/", WebDavXml.relativePath(server.url("/photos/2026/").toString(), base))
        assertEquals("/", WebDavXml.relativePath("/photos/", base))
    }

    private fun multistatus(body: String) =
        MockResponse.Builder().code(207).setHeader("Content-Type", "application/xml").body(body.trimIndent()).build()
}
