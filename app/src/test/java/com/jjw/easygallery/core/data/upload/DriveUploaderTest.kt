package com.jjw.easygallery.core.data.upload

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.jjw.easygallery.core.data.drive.DriveApi
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun `upload starts resumable session then streams body with progress`() = runTest {
        val sessionUrl = server.url("/upload/session/abc").toString()
        server.enqueue(MockResponse.Builder().code(200).setHeader("Location", sessionUrl).build())
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                .body("""{"id":"file-1","name":"IMG_42.jpg"}""").build(),
        )

        val events = mutableListOf<UploadEvent>()
        uploader.upload(sampleItem(), folderId = "folder-9").test {
            while (true) {
                val event = awaitItem()
                events += event
                if (event is UploadEvent.Completed) break
            }
            awaitComplete()
        }

        // 진행률: 시작(0) → 중간 보고(256KB 단위) → 완료(전체)
        val progress = events.filterIsInstance<UploadEvent.Progress>()
        assertEquals(0L, progress.first().bytesSent)
        assertEquals(payload.size.toLong(), progress.last().bytesSent)
        assertTrue(progress.size >= 3)
        assertEquals("file-1", (events.last() as UploadEvent.Completed).driveFileId)

        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("/upload/drive/v3/files", start.url.encodedPath)
        assertEquals("resumable", start.url.queryParameter("uploadType"))
        assertEquals("image/jpeg", start.headers["X-Upload-Content-Type"])
        assertEquals(payload.size.toString(), start.headers["X-Upload-Content-Length"])
        val startBody = start.body!!.utf8()
        assertTrue(startBody.contains("\"parents\":[\"folder-9\"]"))
        assertTrue(startBody.contains("\"mediaStoreId\":\"42\""))

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/upload/session/abc", put.url.encodedPath)
        assertEquals(payload.size, put.body!!.size)
        assertEquals(payload.toList(), put.body!!.toByteArray().toList())
    }

    @Test
    fun `non-2xx session response throws DriveUploadException`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":"forbidden"}""").build())

        uploader.upload(sampleItem(), folderId = "f").test {
            val error = awaitError()
            assertTrue(error is DriveUploadException)
            assertEquals(403, (error as DriveUploadException).httpCode)
        }
    }

    private fun sampleItem() = MediaItem(
        id = 42,
        uri = uri,
        displayName = "IMG_42.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = payload.size.toLong(),
        dateTakenMillis = 0,
        bucketId = 1,
        bucketName = "Camera",
    )
}
