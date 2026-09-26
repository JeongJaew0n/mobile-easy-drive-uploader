package com.jjw.easygallery.core.data.upload

import android.content.Context
import android.net.Uri
import com.jjw.easygallery.core.common.di.AppDispatcher
import com.jjw.easygallery.core.common.di.Dispatcher
import com.jjw.easygallery.core.data.drive.DriveApi
import com.jjw.easygallery.core.data.drive.DriveFileDto
import com.jjw.easygallery.core.data.drive.DriveFileMetadata
import com.jjw.easygallery.core.data.drive.DriveHttpClient
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.remote.RemoteStorageException
import com.jjw.easygallery.core.data.remote.RemoteUploader
import com.jjw.easygallery.core.data.remote.parseRemoteErrorBody
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 업로드할 로컬 파일 정보. MediaItem/UploadTask 양쪽에서 만들 수 있다. */
data class UploadSource(
    val mediaId: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
)

sealed interface UploadEvent {
    /** [bytesSent] 는 파일 시작점 기준 절대값 */
    data class Progress(val bytesSent: Long, val totalBytes: Long) : UploadEvent {
        val fraction: Float get() = if (totalBytes > 0) (bytesSent.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    data class Completed(val driveFileId: String) : UploadEvent
}

sealed interface SessionStatus {
    data class Complete(val driveFileId: String) : SessionStatus
    data class Incomplete(val nextByte: Long) : SessionStatus
    data object Expired : SessionStatus
}

open class DriveUploadException(message: String, httpCode: Int? = null, reason: String? = null) :
    RemoteStorageException(message, httpCode, reason = reason)

/** 세션 URI 가 만료/삭제됨(404·410). 새 세션을 만들어 처음부터 올려야 한다. */
class SessionExpiredException(httpCode: Int) : DriveUploadException("업로드 세션이 만료되었습니다", httpCode)

/**
 * Drive resumable upload 프로토콜.
 * 1) [startSession] 으로 세션 URI 발급 → 2) [upload] 로 본문 PUT.
 * 중단됐다면 [queryStatus] 로 서버가 받은 바이트를 확인하고 그 지점부터 [upload] 를 다시 호출한다.
 */
@Singleton
class DriveUploader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: DriveApi,
    @param:DriveHttpClient private val client: OkHttpClient,
    private val json: Json,
    private val prefs: UserPreferencesRepository,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : RemoteUploader {

    /** 일부 프로바이더는 descriptor 를 열 수 없거나 길이를 모른다(-1) → MediaStore SIZE 로 대체 */
    override suspend fun resolveLength(source: UploadSource): Long = withContext(ioDispatcher) {
        runCatching { context.contentResolver.openAssetFileDescriptor(source.uri, "r")?.use { it.length } }
            .getOrNull()
            ?.takeIf { it >= 0 }
            ?: source.sizeBytes
    }

    override suspend fun startSession(source: UploadSource, folderId: String, length: Long): String {
        val response = api.startResumableUpload(
            metadata = DriveFileMetadata(
                name = source.displayName,
                parents = listOf(folderId),
                appProperties = uploadProperties(source, folderId),
            ),
            contentType = source.mimeType.ifBlank { DEFAULT_MIME_TYPE },
            contentLength = length,
        )
        if (!response.isSuccessful) {
            // 예전에는 코드만 던져서 294건이 "업로드 세션 생성 실패 (403)" 로만 남았다 — 이유를 알 길이 없었다
            val parsed = parseRemoteErrorBody(runCatching { response.errorBody()?.string() }.getOrNull())
            Timber.w("start session failed %d reason=%s", response.code(), parsed.reason)
            throw DriveUploadException(
                message = "업로드 세션 생성 실패 (${response.code()})${parsed.message?.let { ": $it" }.orEmpty()}",
                httpCode = response.code(),
                reason = parsed.reason,
            )
        }
        return response.headers()["Location"] ?: throw DriveUploadException("업로드 세션 URI 가 없습니다")
    }

    /**
     * 올린 파일에 남기는 표식.
     *
     * [PROP_APP] 은 값이 고정이라 `appProperties has { key=... and value='1' }` 로 **전부 찾을 수 있다**.
     * 앱을 지웠다 깔면 지정 폴더 목록이 사라지는데, 이 표식과 파일의 `parents` 로 되살린다
     * (`docs/DRIVE_FILE_SCOPE.md` §6). 지정 폴더가 아니면 [PROP_TARGET_ALIAS] 는 붙지 않는다.
     */
    private suspend fun uploadProperties(source: UploadSource, folderId: String): Map<String, String> {
        val alias = prefs.current().pickedFolders.firstOrNull { it.id == folderId }?.alias
        return buildMap {
            put(PROP_MEDIA_STORE_ID, source.mediaId.toString())
            put(PROP_APP, "1")
            if (alias != null) put(PROP_TARGET_ALIAS, alias)
        }
    }

    /** `Content-Range: bytes *\/total` 로 서버가 받은 범위를 묻는다. */
    override suspend fun queryStatus(sessionUri: String, length: Long): SessionStatus = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(sessionUri)
            .put(ByteArray(0).toRequestBody(null))
            .header("Content-Range", "bytes */$length")
            .build()
        client.newCall(request).await().use { response ->
            when (response.code) {
                HTTP_RESUME_INCOMPLETE -> {
                    // Range: bytes=0-12345 → 다음 바이트는 12346. 헤더가 없으면 아무것도 못 받은 상태.
                    val range = response.header("Range")
                    val lastByte = range?.substringAfter("-", "")?.toLongOrNull()
                    SessionStatus.Incomplete(if (lastByte != null) lastByte + 1 else 0L)
                }
                HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED ->
                    SessionStatus.Complete(parseFile(response).id)
                HttpURLConnection.HTTP_NOT_FOUND, HTTP_GONE -> SessionStatus.Expired
                else -> throw failureOf("세션 상태 조회", response)
            }
        }
    }

    /**
     * 메타데이터와 본문을 한 번의 POST 로 보낸다(`uploadType=multipart`).
     * 세션 생성 왕복이 빠져 작은 파일에서 장당 1초 넘게 줄어든다 — `docs/UPLOAD_PERFORMANCE.md` §2-1.
     *
     * 끊기면 이어올릴 수 없으므로 호출자가 크기로 걸러서 부른다.
     */
    override suspend fun uploadWhole(source: UploadSource, folderId: String, length: Long): String? {
        val mimeType = source.mimeType.ifBlank { DEFAULT_MIME_TYPE }
        val metadata = json.encodeToString(
            DriveFileMetadata.serializer(),
            DriveFileMetadata(
                name = source.displayName,
                parents = listOf(folderId),
                appProperties = uploadProperties(source, folderId),
            ),
        )
        val body = MultipartBody.Builder()
            .setType(MULTIPART_RELATED)
            .addPart(metadata.toRequestBody(JSON_MEDIA_TYPE))
            .addPart(
                ContentUriRequestBody(
                    context = context,
                    uri = source.uri,
                    mediaType = mimeType.toMediaTypeOrNull() ?: DEFAULT_MEDIA_TYPE,
                    offset = 0,
                    totalLength = length,
                ) { },
            )
            .build()
        val request = Request.Builder()
            .url(MULTIPART_UPLOAD_URL)
            .post(body)
            .build()
        return client.newCall(request).await().use { response ->
            when (response.code) {
                HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED -> parseFile(response).id
                else -> throw failureOf("업로드", response)
            }
        }
    }

    /**
     * 같은 `mediaStoreId` 표식을 가진 파일이 그 폴더에 이미 있는지 묻는다.
     * 올리기 직전 한 번만 부르므로 평소 업로드에는 비용이 없다.
     */
    override suspend fun findUploaded(mediaId: Long, folderId: String): String? = runCatching {
        val query = "appProperties has { key = '$PROP_MEDIA_STORE_ID' and value = '$mediaId' }" +
            " and '$folderId' in parents and trashed = false"
        api.listFiles(query).files.firstOrNull()?.id
    }.onFailure { Timber.w(it, "findUploaded failed: %d", mediaId) }.getOrNull()

    /** [offset] 부터 끝까지 스트리밍 PUT. 진행률은 절대 바이트로 보고한다. */
    override fun upload(source: UploadSource, sessionUri: String, offset: Long, length: Long): Flow<UploadEvent> =
        channelFlow {
            val mimeType = source.mimeType.ifBlank { DEFAULT_MIME_TYPE }
            send(UploadEvent.Progress(offset, length))
            val body = ContentUriRequestBody(
                context = context,
                uri = source.uri,
                mediaType = mimeType.toMediaTypeOrNull() ?: DEFAULT_MEDIA_TYPE,
                offset = offset,
                totalLength = length,
            ) { sent -> trySend(UploadEvent.Progress(sent, length)) }
            val request = Request.Builder()
                .url(sessionUri)
                .put(body)
                .apply { if (offset > 0) header("Content-Range", "bytes $offset-${length - 1}/$length") }
                .build()
            val file = client.newCall(request).await().use { response ->
                when (response.code) {
                    HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED -> parseFile(response)
                    HttpURLConnection.HTTP_NOT_FOUND, HTTP_GONE -> throw SessionExpiredException(response.code)
                    else -> throw failureOf("업로드", response)
                }
            }
            Timber.d("uploaded %s -> %s", source.displayName, file.id)
            send(UploadEvent.Completed(file.id))
        }.flowOn(ioDispatcher)

    /**
     * 서버가 준 설명을 오류로 바꾼다. 코드만 있으면 "502" 가 우리 잘못인지 중간 경로의 문제인지
     * 알 길이 없다. [Response.peekBody] 라 본문을 소비하지 않는다.
     *
     * 본문을 **그대로 붙이지 않는다.** 예전에는 JSON 통째로 붙여서 업로드 목록의 두 줄짜리
     * 자리에 `업로드 실패 (403): {  "error": {…` 로 잘렸다. 기계용 코드는 [RemoteStorageException.reason]
     * 으로 넘겨 화면이 제대로 된 문장을 고르게 하고, 원본은 로그에만 남긴다.
     */
    private fun failureOf(what: String, response: Response): DriveUploadException {
        val raw = runCatching { response.peekBody(ERROR_BODY_LIMIT).string() }.getOrNull()
        val parsed = parseRemoteErrorBody(raw)
        Timber.w("%s failed %d reason=%s body=%s", what, response.code, parsed.reason, raw)
        return DriveUploadException(
            message = "$what 실패 (${response.code})${parsed.message?.let { ": $it" }.orEmpty()}",
            httpCode = response.code,
            reason = parsed.reason,
        )
    }

    private fun parseFile(response: Response): DriveFileDto =
        json.decodeFromString(DriveFileDto.serializer(), response.body.string())

    private companion object {
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        val DEFAULT_MEDIA_TYPE = DEFAULT_MIME_TYPE.toMediaTypeOrNull()!!
        const val ERROR_BODY_LIMIT = 2000L
        const val MULTIPART_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart" +
            "&fields=id,name,mimeType,parents"
        val MULTIPART_RELATED = "multipart/related".toMediaTypeOrNull()!!
        val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaTypeOrNull()!!
        const val PROP_MEDIA_STORE_ID = "mediaStoreId"

        /** 이 앱이 올렸다는 고정 표식 — 값이 고정이라야 Drive 쿼리로 찾을 수 있다 */
        const val PROP_APP = "easyGallery"

        /** 지정 폴더에 올린 경우 그 별칭. 폴더 이름을 읽을 수 없어 이것이 유일한 단서다 */
        const val PROP_TARGET_ALIAS = "egTarget"
        const val HTTP_RESUME_INCOMPLETE = 308
        const val HTTP_GONE = 410
    }
}

/** 코루틴 취소 시 OkHttp 호출도 취소되도록 연결한다. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        },
    )
    cont.invokeOnCancellation { cancel() }
}
