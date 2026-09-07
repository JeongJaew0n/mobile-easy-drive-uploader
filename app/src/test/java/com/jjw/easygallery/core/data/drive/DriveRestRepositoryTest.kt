package com.jjw.easygallery.core.data.drive

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class DriveRestRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: DriveRestRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DriveApi::class.java)
        repository = DriveRestRepository(api)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `getAccount maps user and quota`() = runTest {
        server.enqueue(
            json(
                """{"user":{"displayName":"홍길동","emailAddress":"a@b.com"},
                   "storageQuota":{"limit":"16106127360","usage":"3200"}}""",
            ),
        )

        val account = repository.getAccount()

        assertEquals("a@b.com", account.email)
        assertEquals("홍길동", account.displayName)
        assertEquals(3_200L, account.storageUsedBytes)
        assertEquals(16_106_127_360L, account.storageLimitBytes)
        val request = server.takeRequest()
        assertEquals("/drive/v3/about", request.url.encodedPath)
        assertEquals("user,storageQuota", request.url.queryParameter("fields"))
    }

    @Test
    fun `listFolders follows nextPageToken and filters by parent`() = runTest {
        server.enqueue(json("""{"files":[{"id":"1","name":"A"}],"nextPageToken":"tok"}"""))
        server.enqueue(json("""{"files":[{"id":"2","name":"B"}]}"""))

        val folders = repository.listFolders("parent'1")

        assertEquals(listOf("A", "B"), folders.map { it.name })
        val first = server.takeRequest()
        val q = first.url.queryParameter("q")!!
        assertTrue(q.contains("mimeType = '${DriveApi.FOLDER_MIME_TYPE}'"))
        assertTrue("작은따옴표 이스케이프: $q", q.contains("'parent\\'1' in parents"))
        assertEquals(null, first.url.queryParameter("pageToken"))
        assertEquals("tok", server.takeRequest().url.queryParameter("pageToken"))
    }

    @Test
    fun `ensureAppRootFolder returns existing folder without creating`() = runTest {
        server.enqueue(json("""{"files":[{"id":"root1","name":"Easy Gallery"}]}"""))

        val folder = repository.ensureAppRootFolder()

        assertEquals("root1", folder.id)
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().url.queryParameter("q")!!.contains(DriveRestRepository.APP_ROOT_PROPERTY))
    }

    @Test
    fun `ensureAppRootFolder creates folder with app property when missing`() = runTest {
        server.enqueue(json("""{"files":[]}"""))
        server.enqueue(json("""{"id":"new1","name":"Easy Gallery","mimeType":"${DriveApi.FOLDER_MIME_TYPE}"}"""))

        val folder = repository.ensureAppRootFolder()

        assertEquals("new1", folder.id)
        server.takeRequest() // list
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/drive/v3/files", create.url.encodedPath)
        val body = create.body!!.utf8()
        assertTrue(body.contains("\"name\":\"Easy Gallery\""))
        assertTrue(body.contains("\"parents\":[\"root\"]"))
        assertTrue(body.contains("\"${DriveRestRepository.APP_ROOT_PROPERTY}\":\"true\""))
    }

    @Test
    fun `createFolder posts metadata under parent`() = runTest {
        server.enqueue(json("""{"id":"c1","name":"여행"}"""))

        val folder = repository.createFolder("여행", "p1")

        assertEquals("c1", folder.id)
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("\"mimeType\":\"${DriveApi.FOLDER_MIME_TYPE}\""))
        assertTrue(body.contains("\"parents\":[\"p1\"]"))
    }

    private fun json(body: String): MockResponse =
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body.trimIndent()).build()
}
