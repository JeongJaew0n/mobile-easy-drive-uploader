package com.jjw.easygallery.core.data.upload

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.jjw.easygallery.core.data.drive.DriveApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class DriveUploaderTest {

    private lateinit var server: MockWebServer
    private lateinit var uploader: DriveUploader
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val payload = ByteArray(700 * 1024) { (it % 251).toByte() }

    // Robolectric 은 content://media 를 FakeMediaProvider 로 가로채므로 테스트 전용 authority 사용
    private val uri: Uri = Uri.parse("content://com.jjw.easygallery.test/images/42")
    private val source = UploadSource(
        mediaId = 42,
        uri = uri,
        displayName = "IMG_42.jpg",
        mimeType = "image/jpeg",
        sizeBytes = payload.size.toLong(),
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }
        val client = OkHttpClient()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DriveApi::class.java)
        uploader = DriveUploader(context, api, client, json, UnconfinedTestDispatcher())
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(payload))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `startSession posts metadata and returns Location`() = runTest {
        val sessionUrl = server.url("/upload/session/abc").toString()
        server.enqueue(MockResponse.Builder().code(200).setHeader("Location", sessionUrl).build())

        val result = uploader.startSession(source, folderId = "folder-9", length = payload.size.toLong())

        assertEquals(sessionUrl, result)
        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("/upload/drive/v3/files", start.url.encodedPath)
        assertEquals("resumable", start.url.queryParameter("uploadType"))
        assertEquals("image/jpeg", start.headers["X-Upload-Content-Type"])
        assertEquals(payload.size.toString(), start.headers["X-Upload-Content-Length"])
        val body = start.body!!.utf8()
        assertTrue(body.contains("\"parents\":[\"folder-9\"]"))
        assertTrue(body.contains("\"mediaStoreId\":\"42\""))
    }

    @Test
    fun `startSession non-2xx throws DriveUploadException with code`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":"forbidden"}""").build())

        val error = runCatching { uploader.startSession(source, "f", payload.size.toLong()) }.exceptionOrNull()

        assertTrue(error is DriveUploadException)
        assertEquals(403, (error as DriveUploadException).httpCode)
    }

    @Test
    fun `upload from zero streams whole body with progress`() = runTest {
        val sessionUrl = server.url("/upload/session/abc").toString()
        server.enqueue(jsonResponse("""{"id":"file-1","name":"IMG_42.jpg"}"""))

        val events = collectUntilComplete(
            uploader.upload(source, sessionUrl, offset = 0, length = payload.size.toLong()),
        )

        val progress = events.filterIsInstance<UploadEvent.Progress>()
        assertEquals(0L, progress.first().bytesSent)
        assertEquals(payload.size.toLong(), progress.last().bytesSent)
        assertTrue(progress.size >= 3) // 시작 + 256KB 단위 보고 + 완료
        assertEquals("file-1", (events.last() as UploadEvent.Completed).driveFileId)

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertNull(put.headers["Content-Range"])
        assertEquals(payload.toList(), put.body!!.toByteArray().toList())
    }

    @Test
    fun `upload from offset sends only the remainder with Content-Range`() = runTest {
        val sessionUrl = server.url("/upload/session/abc").toString()
        server.enqueue(jsonResponse("""{"id":"file-1","name":"IMG_42.jpg"}"""))
        val offset = 300_000L

        val events = collectUntilComplete(uploader.upload(source, sessionUrl, offset, payload.size.toLong()))

        val progress = events.filterIsInstance<UploadEvent.Progress>()
        assertEquals(offset, progress.first().bytesSent)
        assertEquals(payload.size.toLong(), progress.last().bytesSent)

        val put = server.takeRequest()
        assertEquals("bytes $offset-${payload.size - 1}/${payload.size}", put.headers["Content-Range"])
        assertEquals(payload.copyOfRange(offset.toInt(), payload.size).toList(), put.body!!.toByteArray().toList())
    }

    @Test
    fun `upload 404 throws SessionExpiredException`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())

        uploader.upload(source, server.url("/s").toString(), 0, payload.size.toLong()).test {
            // 본문 전송 중 진행률 이벤트가 몇 개 오고 나서 오류로 끝난다
            while (true) {
                val event = awaitEvent()
                if (event is app.cash.turbine.Event.Error) {
                    assertTrue(event.throwable is SessionExpiredException)
                    break
                }
            }
        }
    }

    @Test
    fun `queryStatus 308 with Range reports next byte`() = runTest {
        server.enqueue(MockResponse.Builder().code(308).setHeader("Range", "bytes=0-262143").build())

        val status = uploader.queryStatus(server.url("/s").toString(), payload.size.toLong())

        assertEquals(SessionStatus.Incomplete(262_144L), status)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("bytes */${payload.size}", request.headers["Content-Range"])
        assertEquals(0L, request.body!!.size.toLong())
    }

    @Test
    fun `queryStatus 308 without Range means nothing received`() = runTest {
        server.enqueue(MockResponse.Builder().code(308).build())

        assertEquals(SessionStatus.Incomplete(0L), uploader.queryStatus(server.url("/s").toString(), 10))
    }

    @Test
    fun `queryStatus 200 means already complete`() = runTest {
        server.enqueue(jsonResponse("""{"id":"done-1","name":"x"}"""))

        assertEquals(SessionStatus.Complete("done-1"), uploader.queryStatus(server.url("/s").toString(), 10))
    }

    @Test
    fun `queryStatus 404 or 410 means expired`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(410).build())

        assertEquals(SessionStatus.Expired, uploader.queryStatus(server.url("/s").toString(), 10))
        assertEquals(SessionStatus.Expired, uploader.queryStatus(server.url("/s").toString(), 10))
    }

    private suspend fun collectUntilComplete(flow: kotlinx.coroutines.flow.Flow<UploadEvent>): List<UploadEvent> {
        val events = mutableListOf<UploadEvent>()
        flow.test {
            while (true) {
                val event = awaitItem()
                events += event
                if (event is UploadEvent.Completed) break
            }
            awaitComplete()
        }
        return events
    }

    private fun jsonResponse(body: String) =
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()
}
