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
    fun `listChildren returns one page with folders first and escapes parent id`() = runTest {
        server.enqueue(
            json(
                """{"files":[
                    {"id":"f1","name":"앨범","mimeType":"application/vnd.google-apps.folder","modifiedTime":"2026-09-07T12:00:00.000Z"},
                    {"id":"i1","name":"a.jpg","mimeType":"image/jpeg","size":"1234","webViewLink":"https://drive/a"}
                  ],"nextPageToken":"tok"}""",
            ),
        )

        val page = repository.listChildren("parent'1")

        assertEquals(listOf("앨범", "a.jpg"), page.entries.map { it.name })
        assertTrue(page.entries[0].isFolder)
        val expectedModified = java.time.Instant.parse("2026-09-07T12:00:00.000Z").toEpochMilli()
        assertEquals(expectedModified, page.entries[0].modifiedTimeMillis)
        assertEquals(1_234L, page.entries[1].sizeBytes)
        assertEquals("https://drive/a", page.entries[1].webViewLink)
        assertEquals("tok", page.nextPageToken)
        val request = server.takeRequest()
        val q = request.url.queryParameter("q")!!
        assertTrue("작은따옴표 이스케이프: $q", q.contains("'parent\\'1' in parents"))
        assertTrue(q.contains("trashed = false"))
        assertEquals("folder,name_natural", request.url.queryParameter("orderBy"))
        assertEquals(null, request.url.queryParameter("pageToken"))
    }

    @Test
    fun `listChildren passes pageToken for next page`() = runTest {
        server.enqueue(json("""{"files":[]}"""))

        val page = repository.listChildren("root", pageToken = "tok")

        assertTrue(page.entries.isEmpty())
        assertEquals(null, page.nextPageToken)
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

    @Test
    fun `rename patches only the name`() = runTest {
        server.enqueue(json("""{"id":"f1","name":"new.jpg","mimeType":"image/jpeg"}"""))

        val entry = repository.rename("f1", "new.jpg")

        assertEquals("new.jpg", entry.name)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/drive/v3/files/f1", request.url.encodedPath)
        assertEquals("""{"name":"new.jpg"}""", request.body!!.utf8())
    }

    @Test
    fun `move swaps parents through query parameters with empty body`() = runTest {
        server.enqueue(json("""{"id":"f1","name":"a.jpg","parents":["to"]}"""))

        repository.move("f1", fromParentId = "from", toParentId = "to")

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("to", request.url.queryParameter("addParents"))
        assertEquals("from", request.url.queryParameter("removeParents"))
        assertEquals("{}", request.body!!.utf8())
    }

    @Test
    fun `setTrashed patches trashed flag`() = runTest {
        server.enqueue(json("""{"id":"f1","name":"a.jpg","trashed":true}"""))
        repository.setTrashed("f1", trashed = true)
        assertEquals("""{"trashed":true}""", server.takeRequest().body!!.utf8())

        server.enqueue(json("""{"id":"f1","name":"a.jpg","trashed":false}"""))
        repository.setTrashed("f1", trashed = false)
        assertEquals("""{"trashed":false}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun `listChildren foldersOnly adds mimeType clause`() = runTest {
        server.enqueue(json("""{"files":[]}"""))

        repository.listChildren("root", foldersOnly = true)

        val q = server.takeRequest().url.queryParameter("q")!!
        assertTrue(q, q.contains("mimeType = '${DriveApi.FOLDER_MIME_TYPE}'"))
    }

    private fun json(body: String): MockResponse =
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body.trimIndent()).build()
}
