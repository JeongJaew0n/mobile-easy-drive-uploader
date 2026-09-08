package com.jjw.easygallery.core.data.remote.s3

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.jjw.easygallery.core.data.upload.SessionStatus
import com.jjw.easygallery.core.data.upload.UploadEvent
import com.jjw.easygallery.core.data.upload.UploadSource
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
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
import org.robolectric.Shadows
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class S3StorageTest {

    private companion object {
        const val PART = 1_024L
    }

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
        storage = S3Storage(context, account, "secret", OkHttpClient(), UnconfinedTestDispatcher(), partSize = PART)
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

    @Test
    fun `multipart session starts with uploads query and resumes from listed parts`() = runTest {
        val uploader = storage.uploader()
        val source = UploadSource(1, Uri.parse("content://media/1"), "big.bin", "application/octet-stream", PART * 3)
        server.enqueue(xml("<InitiateMultipartUploadResult><UploadId>u-1</UploadId></InitiateMultipartUploadResult>"))

        val session = uploader.startSession(source, "2026/", PART * 3)

        assertEquals("mpu|u-1|2026/big.bin", session)
        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("", start.url.queryParameter("uploads"))

        // 파트 1·2 가 이미 올라감 → 다음 오프셋은 PART*2
        server.enqueue(
            xml(
                """<ListPartsResult>
                  <Part><PartNumber>1</PartNumber><Size>$PART</Size><ETag>"e1"</ETag></Part>
                  <Part><PartNumber>2</PartNumber><Size>$PART</Size><ETag>"e2"</ETag></Part>
                </ListPartsResult>""",
            ),
        )
        val status = uploader.queryStatus(session, PART * 3)
        assertEquals(SessionStatus.Incomplete(PART * 2), status)
        assertEquals("u-1", server.takeRequest().url.queryParameter("uploadId"))

        // 사라진 업로드는 Expired
        server.enqueue(MockResponse(code = 404))
        assertEquals(SessionStatus.Expired, uploader.queryStatus(session, PART * 3))
        server.takeRequest()
    }

    @Test
    fun `multipart upload puts remaining parts then completes with all etags`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val uri = Uri.parse("content://media/2")
        val bytes = ByteArray((PART * 3).toInt()) { (it % 251).toByte() }
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        val source = UploadSource(2, uri, "big.bin", "application/octet-stream", bytes.size.toLong())
        // ListParts(기존 파트 1) → PUT part2 → PUT part3 → Complete
        server.enqueue(
            xml(
                """<ListPartsResult>
                  <Part><PartNumber>1</PartNumber><Size>$PART</Size><ETag>"e1"</ETag></Part>
                </ListPartsResult>""",
            ),
        )
        server.enqueue(MockResponse.Builder().code(200).setHeader("ETag", "\"e2\"").build())
        server.enqueue(MockResponse.Builder().code(200).setHeader("ETag", "\"e3\"").build())
        server.enqueue(xml("<CompleteMultipartUploadResult><Key>2026/big.bin</Key></CompleteMultipartUploadResult>"))

        val events = storage.uploader().upload(source, "mpu|u-1|2026/big.bin", PART, bytes.size.toLong()).toList()

        assertEquals(UploadEvent.Completed("2026/big.bin"), events.last())
        server.takeRequest() // ListParts
        val part2 = server.takeRequest()
        assertEquals("2", part2.url.queryParameter("partNumber"))
        assertEquals(PART, part2.bodySize)
        val part3 = server.takeRequest()
        assertEquals("3", part3.url.queryParameter("partNumber"))
        val complete = server.takeRequest()
        assertEquals("POST", complete.method)
        assertEquals("u-1", complete.url.queryParameter("uploadId"))
        val body = complete.body!!.utf8()
        assertTrue(body, body.contains("<PartNumber>1</PartNumber><ETag>\"e1\"</ETag>"))
        assertTrue(body, body.contains("<PartNumber>3</PartNumber><ETag>\"e3\"</ETag>"))
    }

    @Test
    fun `renaming a folder copies every object under the prefix then removes the old marker`() = runTest {
        server.enqueue(
            xml(
                """<ListBucketResult>
                  <Contents><Key>old/</Key></Contents><Contents><Key>old/a.jpg</Key></Contents>
                </ListBucketResult>""",
            ),
        )
        server.enqueue(MockResponse(code = 200)) // copy a.jpg
        server.enqueue(MockResponse(code = 204)) // delete old/a.jpg
        server.enqueue(MockResponse(code = 204)) // delete old/ marker
        server.enqueue(MockResponse(code = 200)) // put new/ marker

        val entry = storage.rename("old/", "new")

        assertEquals("new/", entry.id)
        assertTrue(entry.isFolder)
        server.takeRequest() // list
        val copy = server.takeRequest()
        assertEquals("/photos/new/a.jpg", copy.url.encodedPath)
        assertEquals("/photos/old/a.jpg", copy.headers["x-amz-copy-source"])
        assertEquals("/photos/old/a.jpg", server.takeRequest().url.encodedPath)
        assertEquals("/photos/old/", server.takeRequest().url.encodedPath)
        assertEquals("/photos/new/", server.takeRequest().url.encodedPath)
    }

    private fun xml(body: String) =
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/xml").body(body.trimIndent()).build()
}
