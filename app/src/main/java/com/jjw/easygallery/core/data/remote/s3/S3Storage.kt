package com.jjw.easygallery.core.data.remote.s3

import android.content.Context
import com.jjw.easygallery.core.data.remote.MutationProgress
import com.jjw.easygallery.core.data.remote.RemoteEntry
import com.jjw.easygallery.core.data.remote.RemoteFolder
import com.jjw.easygallery.core.data.remote.RemoteNames
import com.jjw.easygallery.core.data.remote.RemotePage
import com.jjw.easygallery.core.data.remote.RemoteStorage
import com.jjw.easygallery.core.data.remote.RemoteStorageException
import com.jjw.easygallery.core.data.remote.RemoteUploader
import com.jjw.easygallery.core.data.remote.ReportsMutationProgress
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.io.InputStream
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
    /** 이 크기 이상이면 멀티파트(파트 크기도 같음). 테스트에서 작게 준다 */
    private val partSize: Long = DEFAULT_PART_SIZE,
) : RemoteStorage, ReportsMutationProgress {

    private val _mutationProgress = MutableStateFlow<MutationProgress?>(null)
    override val mutationProgress: StateFlow<MutationProgress?> = _mutationProgress.asStateFlow()

    private val bucket = requireNotNull(account.bucketOrRoot) { "S3 계정에 버킷이 없습니다" }
    private val signer = S3Signer(requireNotNull(account.username), secretKey, account.region ?: DEFAULT_REGION)
    private val client: OkHttpClient = baseClient.newBuilder()
        .addInterceptor { chain -> chain.proceed(signer.sign(chain.request())) }
        .build()
    private val bucketUrl: HttpUrl =
        account.endpoint.trimEnd('/').toHttpUrl().newBuilder().addPathSegment(bucket).build()

    override val capabilities: Set<Capability> =
        setOf(
            Capability.DOWNLOAD,
            Capability.RENAME,
            Capability.MOVE,
            Capability.FOLDER_MUTATION,
            Capability.RESUMABLE_UPLOAD,
        )

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
        val parent = entryId.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        return if (entryId.endsWith("/")) {
            movePrefix(entryId, "$parent${name.trim()}/")
        } else {
            copyThenDelete(entryId, parent + name)
        }
    }

    override suspend fun move(entryId: String, fromParentId: String, toParentId: String): RemoteEntry {
        val baseName = entryId.trimEnd('/').substringAfterLast('/')
        return if (entryId.endsWith("/")) {
            movePrefix(entryId, "$toParentId$baseName/")
        } else {
            copyThenDelete(entryId, toParentId + baseName)
        }
    }

    /**
     * 폴더(접두어) 이동 = 아래 오브젝트 전부 복사 후 삭제. S3 에 이동이 없어 오브젝트 수에 비례해 느리다 —
     * 진행 표시는 후속(`docs/MULTI_CLOUD.md` §4).
     */
    private suspend fun movePrefix(fromPrefix: String, toPrefix: String): RemoteEntry {
        if (toPrefix.startsWith(fromPrefix)) throw UnsupportedOperationException("폴더를 자기 자신 아래로 옮길 수 없습니다")
        // 대상이 이미 있으면 조용히 합쳐지고 같은 이름 오브젝트는 덮어써진다 — WebDAV 처럼 먼저 막는다
        if (prefixExists(toPrefix)) throw RemoteStorageException("이미 같은 이름의 폴더가 있습니다")
        forEachKeyUnder(fromPrefix) { key -> copyThenDelete(key, toPrefix + key.removePrefix(fromPrefix)) }
        // 마커 오브젝트(있으면) 정리, 새 마커 생성
        runCatching { deleteKey(fromPrefix) }
        execute(Request.Builder().url(keyUrl(toPrefix)).put(ByteArray(0).toRequestBody(null)).build(), "폴더 생성") { }
        val name = toPrefix.removeSuffix("/").substringAfterLast('/')
        return RemoteEntry(toPrefix, name, RemoteEntry.FOLDER_MIME_TYPE, null, System.currentTimeMillis(), null)
    }

    /**
     * 접두어 아래 모든 키(마커 제외)를 먼저 모은 뒤 하나씩 [action] — 전체 개수를 알아야 진행을 보일 수 있다.
     * 키 목록만 메모리에 두므로 수천 개 폴더도 문제없다.
     */
    private suspend fun forEachKeyUnder(prefix: String, action: suspend (String) -> Unit) {
        val keys = keysUnder(prefix)
        _mutationProgress.value = MutationProgress(0, keys.size)
        try {
            keys.forEachIndexed { index, key ->
                action(key)
                _mutationProgress.value = MutationProgress(index + 1, keys.size)
            }
        } finally {
            _mutationProgress.value = null
        }
    }

    /** 접두어 아래에 오브젝트나 마커가 하나라도 있는지 */
    private suspend fun prefixExists(prefix: String): Boolean {
        val url = bucketUrl.newBuilder()
            .addQueryParameter("list-type", "2")
            .addQueryParameter("prefix", prefix)
            .addQueryParameter("max-keys", "1")
            .build()
        val result = execute(Request.Builder().url(url).get().build(), "목록 조회") { S3Xml.parseListResult(it) }
        return result.objects.isNotEmpty() || result.commonPrefixes.isNotEmpty()
    }

    private suspend fun keysUnder(prefix: String): List<String> {
        val keys = ArrayList<String>()
        var token: String? = null
        do {
            val url = bucketUrl.newBuilder()
                .addQueryParameter("list-type", "2")
                .addQueryParameter("prefix", prefix)
                .addQueryParameter("max-keys", PAGE_SIZE.toString())
                .apply { if (token != null) addQueryParameter("continuation-token", token) }
                .build()
            val result = execute(Request.Builder().url(url).get().build(), "목록 조회") { S3Xml.parseListResult(it) }
            result.objects.filter { it.key != prefix }.forEach { keys += it.key }
            val next = result.nextContinuationToken
            // 같은 토큰을 계속 돌려주는 서버를 만나면 리스트가 무한히 자란다
            if (next != null && next == token) break
            token = next
        } while (token != null)
        return keys
    }

    override suspend fun delete(entryId: String) {
        if (!entryId.endsWith("/")) {
            deleteKey(entryId)
            return
        }
        // 폴더: 접두어 아래 오브젝트를 전부 지운다(구분자 없이 나열), 마지막에 마커
        forEachKeyUnder(entryId) { deleteKey(it) }
        deleteKey(entryId)
    }

    override suspend fun restore(entryId: String) = throw UnsupportedOperationException("S3 에는 휴지통이 없습니다")

    override suspend fun openDownload(entryId: String): InputStream = withContext(ioDispatcher) {
        client.newCall(Request.Builder().url(keyUrl(entryId)).get().build())
            .awaitResponse().requireSuccess("다운로드").body.byteStream()
    }

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

    /**
     * [partSize] 미만은 단일 PUT(재개 없음). 그 이상은 멀티파트 — 세션 = `mpu|uploadId|key`,
     * 상태 조회는 ListParts 로 받은 파트를 세어 다음 바이트를 돌려주고, 이어 올리기는 그 파트부터 PUT 한 뒤 Complete.
     */
    private inner class S3Uploader : RemoteUploader {
        override suspend fun resolveLength(source: UploadSource): Long = withContext(ioDispatcher) {
            runCatching { context.contentResolver.openAssetFileDescriptor(source.uri, "r")?.use { it.length } }
                .getOrNull()?.takeIf { it >= 0 } ?: source.sizeBytes
        }

        override suspend fun startSession(source: UploadSource, folderId: String, length: Long): String {
            // 같은 키가 있으면 덮어쓰지 않고 ` (n)` 을 붙인다
            val key = folderId + RemoteNames.unique(source.displayName) { candidate -> exists(folderId + candidate) }
            if (length < partSize) return key
            val request = Request.Builder()
                .url(keyUrl(key).newBuilder().addQueryParameter("uploads", "").build())
                .header("Content-Type", source.mimeType.ifBlank { DEFAULT_MEDIA_TYPE.toString() })
                .post(ByteArray(0).toRequestBody(null))
                .build()
            val uploadId = execute(request, "멀티파트 시작") { S3Xml.parseUploadId(it) }
                ?: throw RemoteStorageException("멀티파트 UploadId 가 없습니다")
            return "$MPU_PREFIX$uploadId|$key"
        }

        override suspend fun queryStatus(sessionUri: String, length: Long): SessionStatus = withContext(ioDispatcher) {
            val mpu = parseMpu(sessionUri) ?: return@withContext singleStatus(sessionUri, length)
            val response = client.newCall(Request.Builder().url(mpu.listPartsUrl()).get().build()).awaitResponse()
            response.use {
                when {
                    it.code == HTTP_NOT_FOUND -> SessionStatus.Expired
                    !it.isSuccessful -> throw RemoteStorageException("파트 조회 실패 (${it.code})", it.code)
                    else -> SessionStatus.Incomplete(contiguousBytes(S3Xml.parseListParts(it.body.string())))
                }
            }
        }

        private suspend fun exists(key: String): Boolean = withContext(ioDispatcher) {
            client.newCall(Request.Builder().url(keyUrl(key)).head().build()).awaitResponse().use { it.isSuccessful }
        }

        private suspend fun singleStatus(key: String, length: Long): SessionStatus {
            client.newCall(Request.Builder().url(keyUrl(key)).head().build()).awaitResponse().use { response ->
                val remoteLength = response.header("Content-Length")?.toLongOrNull()
                return if (response.isSuccessful && remoteLength == length) {
                    SessionStatus.Complete(key)
                } else {
                    SessionStatus.Expired
                }
            }
        }

        override fun upload(source: UploadSource, sessionUri: String, offset: Long, length: Long): Flow<UploadEvent> =
            channelFlow {
                val mpu = parseMpu(sessionUri)
                if (mpu == null) {
                    putWhole(source, sessionUri, length) { trySend(it) }
                    send(UploadEvent.Completed(sessionUri))
                } else {
                    uploadParts(mpu, source, offset, length) { trySend(it) }
                    send(UploadEvent.Completed(mpu.key))
                }
            }.flowOn(ioDispatcher)

        private suspend fun putWhole(source: UploadSource, key: String, length: Long, emit: (UploadEvent) -> Unit) {
            emit(UploadEvent.Progress(0, length))
            val body = requestBody(source, 0, length, length, emit)
            client.newCall(Request.Builder().url(keyUrl(key)).put(body).build()).awaitResponse()
                .requireSuccess("업로드").close()
            Timber.d("uploaded %s -> s3://%s/%s", source.displayName, bucket, key)
        }

        private suspend fun uploadParts(
            mpu: Mpu,
            source: UploadSource,
            offset: Long,
            length: Long,
            emit: (UploadEvent) -> Unit,
        ) {
            // 앞서 올라간 파트의 ETag 는 Complete 에 필요하다
            val etags = HashMap<Int, String>()
            if (offset > 0) {
                val listed = client.newCall(Request.Builder().url(mpu.listPartsUrl()).get().build()).awaitResponse()
                    .requireSuccess("파트 조회").use { S3Xml.parseListParts(it.body.string()) }
                listed.forEach { etags[it.partNumber] = it.eTag }
            }
            emit(UploadEvent.Progress(offset, length))
            var start = offset
            while (start < length) {
                val end = minOf(start + partSize, length)
                val partNumber = (start / partSize).toInt() + 1
                val url = keyUrl(mpu.key).newBuilder()
                    .addQueryParameter("partNumber", partNumber.toString())
                    .addQueryParameter("uploadId", mpu.uploadId)
                    .build()
                val body = requestBody(source, start, end, length, emit)
                val etag = client.newCall(Request.Builder().url(url).put(body).build()).awaitResponse()
                    .requireSuccess("파트 업로드").use { it.header("ETag") }
                    ?: throw RemoteStorageException("파트 $partNumber 의 ETag 가 없습니다")
                etags[partNumber] = etag
                start = end
            }
            val completeXml = buildString {
                append("<CompleteMultipartUpload>")
                etags.toSortedMap().forEach { (n, tag) ->
                    append("<Part><PartNumber>$n</PartNumber><ETag>$tag</ETag></Part>")
                }
                append("</CompleteMultipartUpload>")
            }
            val completeUrl = keyUrl(mpu.key).newBuilder().addQueryParameter("uploadId", mpu.uploadId).build()
            val result = client.newCall(
                Request.Builder().url(completeUrl).post(completeXml.toRequestBody(XML_MEDIA_TYPE)).build(),
            ).awaitResponse().requireSuccess("멀티파트 완료").use { it.body.string() }
            S3Xml.errorCode(result)?.let { throw RemoteStorageException("멀티파트 완료 실패: $it") }
            Timber.d("uploaded %s -> s3://%s/%s (%d parts)", source.displayName, bucket, mpu.key, etags.size)
        }

        private fun requestBody(
            source: UploadSource,
            start: Long,
            end: Long,
            total: Long,
            emit: (UploadEvent) -> Unit,
        ) = ContentUriRequestBody(
            context = context,
            uri = source.uri,
            mediaType = source.mimeType.toMediaTypeOrNull() ?: DEFAULT_MEDIA_TYPE,
            offset = start,
            totalLength = end,
        ) { sent -> emit(UploadEvent.Progress(sent, total)) }

        /** 1번부터 이어지는 파트의 바이트 합 = 다음에 올릴 오프셋 */
        private fun contiguousBytes(parts: List<S3Part>): Long {
            var expected = 1
            var bytes = 0L
            for (part in parts) {
                if (part.partNumber != expected) break
                bytes += part.size
                expected++
            }
            return bytes
        }

        /** 미완료 멀티파트는 스토리지 요금이 계속 붙으므로 영구 실패 시 지운다 */
        override suspend fun abort(sessionUri: String) {
            val mpu = parseMpu(sessionUri) ?: return
            withContext(ioDispatcher) {
                client.newCall(Request.Builder().url(mpu.listPartsUrl()).delete().build()).awaitResponse().close()
            }
        }

        private fun Mpu.listPartsUrl(): HttpUrl =
            keyUrl(key).newBuilder().addQueryParameter("uploadId", uploadId).build()
    }

    private data class Mpu(val uploadId: String, val key: String)

    private fun parseMpu(sessionUri: String): Mpu? {
        if (!sessionUri.startsWith(MPU_PREFIX)) return null
        val rest = sessionUri.removePrefix(MPU_PREFIX)
        return Mpu(uploadId = rest.substringBefore('|'), key = rest.substringAfter('|'))
    }

    private companion object {
        const val DEFAULT_REGION = "us-east-1"
        const val PAGE_SIZE = 1000
        const val DEFAULT_PART_SIZE = 8L * 1024 * 1024
        const val MPU_PREFIX = "mpu|"
        const val HTTP_NOT_FOUND = 404
        val DEFAULT_MEDIA_TYPE = "application/octet-stream".toMediaTypeOrNull()!!
        val XML_MEDIA_TYPE = "application/xml".toMediaTypeOrNull()!!

        fun guessMimeType(name: String): String =
            URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    }
}
