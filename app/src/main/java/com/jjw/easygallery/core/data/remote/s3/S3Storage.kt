package com.jjw.easygallery.core.data.remote.s3

import android.content.Context
import com.jjw.easygallery.core.data.remote.RemoteEntry
import com.jjw.easygallery.core.data.remote.RemoteFolder
import com.jjw.easygallery.core.data.remote.RemotePage
import com.jjw.easygallery.core.data.remote.RemoteStorage
import com.jjw.easygallery.core.data.remote.RemoteUploader
import com.jjw.easygallery.core.data.remote.UnsupportedOperationException
import com.jjw.easygallery.core.data.remote.awaitResponse
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.net.URLConnection

/**
 * S3 호환 오브젝트 스토리지(Naver Cloud·KT Cloud·AWS·R2·MinIO). `docs/MULTI_CLOUD.md` §4.
 * - path-style 주소(`endpoint/bucket/key`), SigV4 서명은 인터셉터가 붙인다
 * - "폴더"는 `prefix/` 접두어. 폴더 생성은 길이 0 오브젝트, 목록은 `delimiter=/`
 * - 휴지통 없음 → 삭제는 영구. 폴더 이름 변경·이동은 1단계에서 지원하지 않는다(오브젝트 전부 복사 필요)
 */
class S3Storage(
    private val context: Context,
    override val account: RemoteAccount,
    secretKey: String,
    baseClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
) : RemoteStorage {

    private val bucket = requireNotNull(account.bucketOrRoot) { "S3 계정에 버킷이 없습니다" }
    private val signer = S3Signer(requireNotNull(account.username), secretKey, account.region ?: DEFAULT_REGION)
    private val client: OkHttpClient = baseClient.newBuilder()
        .addInterceptor { chain -> chain.proceed(signer.sign(chain.request())) }
        .build()
    private val bucketUrl: HttpUrl =
        account.endpoint.trimEnd('/').toHttpUrl().newBuilder().addPathSegment(bucket).build()

    override val capabilities: Set<Capability> = setOf(Capability.RENAME, Capability.MOVE)

    /** 루트는 빈 접두어 */
    override val rootId: String get() = ""

    override suspend fun about(): RemoteAccountInfo =
        RemoteAccountInfo(account.displayName, "${account.endpoint} / $bucket")

    override suspend fun listChildren(parentId: String, pageToken: String?, foldersOnly: Boolean): RemotePage {
        val url = bucketUrl.newBuilder()
            .addQueryParameter("list-type", "2")
            .addQueryParameter("delimiter", "/")
            .addQueryParameter("max-keys", PAGE_SIZE.toString())
            .apply {
                if (parentId.isNotEmpty()) addQueryParameter("prefix", parentId)
                if (pageToken != null) addQueryParameter("continuation-token", pageToken)
            }
            .build()
        val result = execute(Request.Builder().url(url).get().build(), "목록 조회") { S3Xml.parseListResult(it) }
        val folders = result.commonPrefixes.map { prefix ->
            RemoteEntry(
                id = prefix,
                name = prefix.removeSuffix("/").substringAfterLast('/'),
                mimeType = RemoteEntry.FOLDER_MIME_TYPE,
                sizeBytes = null,
                modifiedTimeMillis = null,
                webViewLink = null,
            )
        }
        val files = if (foldersOnly) {
            emptyList()
        } else {
            result.objects.filter { it.key != parentId && !it.key.endsWith("/") }.map { obj ->
                val name = obj.key.substringAfterLast('/')
                RemoteEntry(
                    id = obj.key,
                    name = name,
                    mimeType = guessMimeType(name),
                    sizeBytes = obj.size,
                    modifiedTimeMillis = obj.lastModifiedMillis,
                    webViewLink = null,
                )
            }
        }
        val entries = (folders + files)
            .sortedWith(compareBy<RemoteEntry> { !it.isFolder }.thenBy { it.name.lowercase() })
        return RemotePage(entries, result.nextContinuationToken)
    }

    override suspend fun createFolder(name: String, parentId: String): RemoteFolder {
        val key = "$parentId${name.trim().trim('/')}/"
        execute(Request.Builder().url(keyUrl(key)).put(ByteArray(0).toRequestBody(null)).build(), "폴더 생성") { }
        return RemoteFolder(key, name.trim())
    }

    override suspend fun rename(entryId: String, name: String): RemoteEntry {
        if (entryId.endsWith("/")) throw UnsupportedOperationException("S3 폴더 이름 변경은 지원하지 않습니다")
        val newKey = entryId.substringBeforeLast('/', "").let { if (it.isEmpty()) name else "$it/$name" }
        return copyThenDelete(entryId, newKey)
    }

    override suspend fun move(entryId: String, fromParentId: String, toParentId: String): RemoteEntry {
        if (entryId.endsWith("/")) throw UnsupportedOperationException("S3 폴더 이동은 지원하지 않습니다")
        return copyThenDelete(entryId, toParentId + entryId.substringAfterLast('/'))
    }

    override suspend fun delete(entryId: String) {
        if (!entryId.endsWith("/")) {
            deleteKey(entryId)
            return
        }
        // 폴더: 접두어 아래 오브젝트를 전부 지운다(구분자 없이 나열)
        var token: String? = null
        do {
            val url = bucketUrl.newBuilder()
                .addQueryParameter("list-type", "2")
                .addQueryParameter("prefix", entryId)
                .addQueryParameter("max-keys", PAGE_SIZE.toString())
                .apply { if (token != null) addQueryParameter("continuation-token", token) }
                .build()
            val result = execute(Request.Builder().url(url).get().build(), "목록 조회") { S3Xml.parseListResult(it) }
            result.objects.forEach { deleteKey(it.key) }
            token = result.nextContinuationToken
        } while (token != null)
        deleteKey(entryId)
    }

    override suspend fun restore(entryId: String) = throw UnsupportedOperationException("S3 에는 휴지통이 없습니다")

    override fun uploader(): RemoteUploader = S3Uploader()

    private suspend fun copyThenDelete(fromKey: String, toKey: String): RemoteEntry {
        val copy = Request.Builder()
            .url(keyUrl(toKey))
            .header("x-amz-copy-source", "/$bucket/${S3Signer.uriEncode(fromKey, encodeSlash = false)}")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        execute(copy, "복사") { }
        deleteKey(fromKey)
        val name = toKey.substringAfterLast('/')
        return RemoteEntry(toKey, name, guessMimeType(name), null, System.currentTimeMillis(), null)
    }

    private suspend fun deleteKey(key: String) {
        execute(Request.Builder().url(keyUrl(key)).delete().build(), "삭제") { }
    }

    private fun keyUrl(key: String): HttpUrl =
        bucketUrl.newBuilder().apply { key.split('/').forEach { addPathSegment(it) } }.build()

    private suspend fun <T> execute(request: Request, what: String, parse: (String) -> T): T =
        withContext(ioDispatcher) {
            client.newCall(request).awaitResponse().requireSuccess(what).use { response ->
                parse(response.body.string())
            }
        }

    /** 단일 PUT. 재개 없음 — 상태 조회는 대상 오브젝트가 같은 크기로 존재하면 완료, 아니면 처음부터 */
    private inner class S3Uploader : RemoteUploader {
        override suspend fun resolveLength(source: UploadSource): Long = withContext(ioDispatcher) {
            runCatching { context.contentResolver.openAssetFileDescriptor(source.uri, "r")?.use { it.length } }
                .getOrNull()?.takeIf { it >= 0 } ?: source.sizeBytes
        }

        override suspend fun startSession(source: UploadSource, folderId: String, length: Long): String =
            folderId + source.displayName

        override suspend fun queryStatus(sessionUri: String, length: Long): SessionStatus = withContext(ioDispatcher) {
            client.newCall(Request.Builder().url(keyUrl(sessionUri)).head().build()).awaitResponse().use { response ->
                val remoteLength = response.header("Content-Length")?.toLongOrNull()
                if (response.isSuccessful && remoteLength == length) {
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
                val request = Request.Builder().url(keyUrl(sessionUri)).put(body).build()
                client.newCall(request).awaitResponse().requireSuccess("업로드").close()
                Timber.d("uploaded %s -> s3://%s/%s", source.displayName, bucket, sessionUri)
                send(UploadEvent.Completed(sessionUri))
            }.flowOn(ioDispatcher)
    }

    private companion object {
        const val DEFAULT_REGION = "us-east-1"
        const val PAGE_SIZE = 200
        val DEFAULT_MEDIA_TYPE = "application/octet-stream".toMediaTypeOrNull()!!

        fun guessMimeType(name: String): String =
            URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    }
}
