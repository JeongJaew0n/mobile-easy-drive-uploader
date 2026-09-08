package com.jjw.easygallery.core.data.remote.s3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
class S3StorageTest {

    private lateinit var server: MockWebServer
    private lateinit var storage: S3Storage

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context: Context = ApplicationProvider.getApplicationContext()
        val account = RemoteAccount(
            id = "acc",
            kind = RemoteAccountKind.S3,
            displayName = "test",
            endpoint = server.url("/").toString().trimEnd('/'),
            region = "kr-standard",
            bucketOrRoot = "photos",
            username = "AKIA",
        )
        storage = S3Storage(context, account, "secret", OkHttpClient(), UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `listChildren maps prefixes to folders and objects to files, folders first`() = runTest {
        server.enqueue(
            xml(
                """<?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult>
                  <Name>photos</Name><Prefix>2026/</Prefix><IsTruncated>true</IsTruncated>
                  <NextContinuationToken>tok1</NextContinuationToken>
                  <Contents><Key>2026/</Key><Size>0</Size></Contents>
                  <Contents><Key>2026/b.jpg</Key><Size>1234</Size><LastModified>2026-09-01T00:00:00.000Z</LastModified></Contents>
                  <Contents><Key>2026/a.mp4</Key><Size>99</Size></Contents>
                  <CommonPrefixes><Prefix>2026/09/</Prefix></CommonPrefixes>
                </ListBucketResult>""",
            ),
        )

        val page = storage.listChildren("2026/")

        assertEquals(listOf("09", "a.mp4", "b.jpg"), page.entries.map { it.name })
        assertTrue(page.entries[0].isFolder)
        assertEquals("2026/09/", page.entries[0].id)
        assertEquals("image/jpeg", page.entries[2].mimeType)
        assertEquals(1_234L, page.entries[2].sizeBytes)
        assertEquals("tok1", page.nextPageToken)

        val request = server.takeRequest()
        assertEquals("/photos", request.url.encodedPath)
        assertEquals("2", request.url.queryParameter("list-type"))
        assertEquals("2026/", request.url.queryParameter("prefix"))
        assertEquals("/", request.url.queryParameter("delimiter"))
        val auth = request.headers["Authorization"]!!
        assertTrue(auth, auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIA/"))
        assertTrue(auth, auth.contains("/kr-standard/s3/aws4_request"))
        assertEquals("UNSIGNED-PAYLOAD", request.headers["x-amz-content-sha256"])
    }

    @Test
    fun `createFolder puts an empty marker object`() = runTest {
        server.enqueue(MockResponse(code = 200))

        val folder = storage.createFolder(" 여행 ", "2026/")

        assertEquals("2026/여행/", folder.id)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/photos/2026/%EC%97%AC%ED%96%89/", request.url.encodedPath)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `rename copies then deletes the object`() = runTest {
        server.enqueue(MockResponse(code = 200))
        server.enqueue(MockResponse(code = 204))

        val entry = storage.rename("2026/a.jpg", "z.jpg")

        assertEquals("2026/z.jpg", entry.id)
        val copy = server.takeRequest()
        assertEquals("PUT", copy.method)
        assertEquals("/photos/2026/z.jpg", copy.url.encodedPath)
        assertEquals("/photos/2026/a.jpg", copy.headers["x-amz-copy-source"])
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/photos/2026/a.jpg", delete.url.encodedPath)
    }

    @Test
    fun `move keeps the file name under the target prefix`() = runTest {
        server.enqueue(MockResponse(code = 200))
        server.enqueue(MockResponse(code = 204))

        val entry = storage.move("2026/a.jpg", fromParentId = "2026/", toParentId = "archive/")

        assertEquals("archive/a.jpg", entry.id)
        assertEquals("/photos/archive/a.jpg", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `deleting a folder removes every object under the prefix and the marker`() = runTest {
        server.enqueue(
            xml(
                """<ListBucketResult>
                  <Contents><Key>old/1.jpg</Key></Contents><Contents><Key>old/2.jpg</Key></Contents>
                </ListBucketResult>""",
            ),
        )
        repeat(3) { server.enqueue(MockResponse(code = 204)) }

        storage.delete("old/")

        server.takeRequest() // list
        val deleted = List(3) { server.takeRequest().url.encodedPath }
        assertEquals(listOf("/photos/old/1.jpg", "/photos/old/2.jpg", "/photos/old/"), deleted)
    }

    @Test
    fun `http errors surface the status code`() = runTest {
        server.enqueue(MockResponse(code = 403, body = "<Error><Code>AccessDenied</Code></Error>"))

        val error = runCatching { storage.listChildren("") }.exceptionOrNull()

        assertTrue(error is com.jjw.easygallery.core.data.remote.RemoteStorageException)
        assertEquals(403, (error as com.jjw.easygallery.core.data.remote.RemoteStorageException).httpCode)
    }

    private fun xml(body: String) =
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/xml").body(body.trimIndent()).build()
}
