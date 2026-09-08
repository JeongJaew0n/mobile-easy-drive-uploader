package com.jjw.easygallery.core.data.remote.webdav

import android.content.Context
import com.jjw.easygallery.core.data.remote.RemoteEntry
import com.jjw.easygallery.core.data.remote.RemoteFolder
import com.jjw.easygallery.core.data.remote.RemoteNames
import com.jjw.easygallery.core.data.remote.RemotePage
import com.jjw.easygallery.core.data.remote.RemoteStorage
import com.jjw.easygallery.core.data.remote.RemoteStorageException
import com.jjw.easygallery.core.data.remote.RemoteUploader
import com.jjw.easygallery.core.data.remote.UnsupportedOperationException
import com.jjw.easygallery.core.data.remote.awaitResponse
import com.jjw.easygallery.core.data.remote.pinCertificate
import com.jjw.easygallery.core.data.remote.requireSuccess
import com.jjw.easygallery.core.data.upload.ContentUriRequestBody
import com.jjw.easygallery.core.data.upload.SessionStatus
import com.jjw.easygallery.core.data.upload.UploadEvent
import com.jjw.easygallery.core.data.upload.UploadSource
import com.jjw.easygallery.core.domain.model.Capability
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.net.URLConnection

/**
 * WebDAV(NAS: Synology·QNAP·Nextcloud·일반 서버). `docs/NAS_STORAGE.md` §2.
 * - `entryId` 는 endpoint 기준 상대 경로. 폴더는 `/` 로 끝난다. 루트는 `"/"`
 * - 목록 PROPFIND Depth 1, 폴더 생성 MKCOL, 이름 변경·이동 MOVE, 삭제 DELETE(휴지통 없음), 업로드 PUT(재개 없음)
 */
class WebDavStorage(
    private val context: Context,
    override val account: RemoteAccount,
    password: String,
    baseClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
) : RemoteStorage {

    private val baseUrl: HttpUrl = account.endpoint.trimEnd('/').toHttpUrl()
    private val auth = DigestAuth(account.username.orEmpty(), password)
    private val client: OkHttpClient = baseClient.newBuilder()
        // 자체 서명 인증서: 저장된 지문과 정확히 같은 인증서만 신뢰(전체 신뢰는 하지 않는다)
        .apply { account.certSha256?.let { pinCertificate(it) } }
        // Basic 기본, 서버가 Digest 를 요구하면 챌린지에 답하고 이후 요청에 선제 적용
        .addInterceptor(auth.interceptor)
        .authenticator(auth.authenticator)
        .build()

    override val capabilities: Set<Capability> =
        setOf(Capability.RENAME, Capability.MOVE, Capability.FOLDER_MUTATION, Capability.QUOTA)

    override val rootId: String get() = "/"

    override suspend fun about(): RemoteAccountInfo {
        val quota = runCatching { propfind("/", depth = 0).firstOrNull() }.getOrNull()
        return RemoteAccountInfo(
            displayName = account.displayName,
            detail = account.endpoint,
            storageUsedBytes = quota?.quotaUsedBytes,
            storageLimitBytes = quota?.let { q -> q.quotaUsedBytes?.plus(q.quotaAvailableBytes ?: return@let null) },
        )
    }

    override suspend fun listChildren(parentId: String, pageToken: String?, foldersOnly: Boolean): RemotePage {
        val parent = normalizeFolder(parentId)
        val entries = propfind(parent, depth = 1)
            .filter { it.path != parent && it.path.trimEnd('/') != parent.trimEnd('/') }
            .filter { !foldersOnly || it.isCollection }
            .map { it.toEntry() }
            .sortedWith(compareBy<RemoteEntry> { !it.isFolder }.thenBy { it.name.lowercase() })
        return RemotePage(entries, nextPageToken = null)
    }

    override suspend fun createFolder(name: String, parentId: String): RemoteFolder {
        val path = normalizeFolder(parentId) + name.trim().trim('/') + "/"
        execute(Request.Builder().url(url(path)).method("MKCOL", null).build(), "폴더 생성")
        return RemoteFolder(path, name.trim())
    }

    override suspend fun rename(entryId: String, name: String): RemoteEntry {
        val isFolder = entryId.endsWith("/")
        val parent = entryId.trimEnd('/').substringBeforeLast('/') + "/"
        val target = parent + name.trim() + (if (isFolder) "/" else "")
        move(entryId, target)
        return entry(target, isFolder)
    }

    override suspend fun move(entryId: String, fromParentId: String, toParentId: String): RemoteEntry {
        val isFolder = entryId.endsWith("/")
        val baseName = entryId.trimEnd('/').substringAfterLast('/')
        val target = normalizeFolder(toParentId) + baseName + (if (isFolder) "/" else "")
        move(entryId, target)
        return entry(target, isFolder)
    }

    override suspend fun delete(entryId: String) {
        execute(Request.Builder().url(url(entryId)).delete().build(), "삭제")
    }

    override suspend fun restore(entryId: String) = throw UnsupportedOperationException("WebDAV 에는 휴지통이 없습니다")

    override fun uploader(): RemoteUploader = WebDavUploader()

    private suspend fun move(from: String, to: String) {
        val request = Request.Builder()
            .url(url(from))
            .method("MOVE", null)
            .header("Destination", url(to).toString())
            .header("Overwrite", "F")
            .build()
        execute(request, "이동")
    }

    private suspend fun propfind(path: String, depth: Int): List<DavResource> = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(url(path))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .header("Depth", depth.toString())
            .build()
        client.newCall(request).awaitResponse().use { response ->
            if (response.code != HTTP_MULTI_STATUS && !response.isSuccessful) {
                throw RemoteStorageException("목록 조회 실패 (${response.code})", response.code)
            }
            WebDavXml.parseMultiStatus(response.body.string(), baseUrl)
        }
    }

    private suspend fun execute(request: Request, what: String) = withContext(ioDispatcher) {
        client.newCall(request).awaitResponse().requireSuccess(what).close()
    }

    /** 상대 경로 → 절대 URL. 세그먼트별로 인코딩(한글·공백) */
    private fun url(path: String): HttpUrl = baseUrl.newBuilder().apply {
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
        if (path.endsWith("/") && path.trim('/').isNotEmpty()) addPathSegment("")
    }.build()

    private fun normalizeFolder(id: String): String = if (id.isEmpty() || id == "/") "/" else "/${id.trim('/')}/"

    private fun entry(path: String, isFolder: Boolean): RemoteEntry {
        val name = path.trimEnd('/').substringAfterLast('/')
        return RemoteEntry(
            id = path,
            name = name,
            mimeType = if (isFolder) RemoteEntry.FOLDER_MIME_TYPE else guessMimeType(name),
            sizeBytes = null,
            modifiedTimeMillis = System.currentTimeMillis(),
            webViewLink = null,
        )
    }

    private fun DavResource.toEntry(): RemoteEntry {
        val name = displayName?.takeIf { it.isNotBlank() } ?: path.trimEnd('/').substringAfterLast('/')
        return RemoteEntry(
            id = if (isCollection && !path.endsWith("/")) "$path/" else path,
            name = name,
            mimeType = if (isCollection) RemoteEntry.FOLDER_MIME_TYPE else contentType ?: guessMimeType(name),
            sizeBytes = contentLength,
            modifiedTimeMillis = lastModifiedMillis,
            webViewLink = null,
        )
    }

    /** 단일 PUT. 재개 없음 → 상태 조회는 원격 파일이 같은 크기면 완료, 아니면 처음부터 */
    private inner class WebDavUploader : RemoteUploader {
        override suspend fun resolveLength(source: UploadSource): Long = withContext(ioDispatcher) {
            runCatching { context.contentResolver.openAssetFileDescriptor(source.uri, "r")?.use { it.length } }
                .getOrNull()?.takeIf { it >= 0 } ?: source.sizeBytes
        }

        /** 같은 이름이 있으면 ` (n)` 을 붙인다 — PUT 은 덮어쓰기라서 */
        override suspend fun startSession(source: UploadSource, folderId: String, length: Long): String {
            val folder = normalizeFolder(folderId)
            val name = RemoteNames.unique(source.displayName) { candidate -> exists(folder + candidate) }
            return folder + name
        }

        private suspend fun exists(path: String): Boolean = withContext(ioDispatcher) {
            client.newCall(Request.Builder().url(url(path)).head().build()).awaitResponse().use { it.isSuccessful }
        }

        override suspend fun queryStatus(sessionUri: String, length: Long): SessionStatus = withContext(ioDispatcher) {
            client.newCall(Request.Builder().url(url(sessionUri)).head().build()).awaitResponse().use { response ->
                val remote = response.header("Content-Length")?.toLongOrNull()
                if (response.isSuccessful && remote == length) {
                    SessionStatus.Complete(sessionUri)
                } else {
                    SessionStatus.Expired
                }
            }
        }

        override fun upload(source: UploadSource, sessionUri: String, offset: Long, length: Long): Flow<UploadEvent> =
            channelFlow {
                send(UploadEvent.Progress(0, length))
                val body = ContentUriRequestBody(
                    resolver = context.contentResolver,
                    uri = source.uri,
                    mediaType = source.mimeType.toMediaTypeOrNull() ?: DEFAULT_MEDIA_TYPE,
                    offset = 0,
                    totalLength = length,
                ) { sent -> trySend(UploadEvent.Progress(sent, length)) }
                client.newCall(Request.Builder().url(url(sessionUri)).put(body).build())
                    .awaitResponse()
                    .requireSuccess("업로드")
                    .close()
                Timber.d("uploaded %s -> dav:%s", source.displayName, sessionUri)
                send(UploadEvent.Completed(sessionUri))
            }.flowOn(ioDispatcher)
    }

    private companion object {
        const val HTTP_MULTI_STATUS = 207
        val XML = "application/xml; charset=utf-8".toMediaType()
        val DEFAULT_MEDIA_TYPE = "application/octet-stream".toMediaTypeOrNull()!!
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:displayname/><d:getcontentlength/><d:getlastmodified/><d:getcontenttype/><d:resourcetype/>
<d:quota-available-bytes/><d:quota-used-bytes/>
</d:prop></d:propfind>"""

        fun guessMimeType(name: String): String =
            URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    }
}
