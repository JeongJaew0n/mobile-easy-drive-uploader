package com.jjw.easygallery.core.data.upload

import android.content.Context
import android.net.Uri
import com.jjw.easygallery.core.common.di.AppDispatcher
import com.jjw.easygallery.core.common.di.Dispatcher
import com.jjw.easygallery.core.data.drive.DriveApi
import com.jjw.easygallery.core.data.drive.DriveFileDto
import com.jjw.easygallery.core.data.drive.DriveFileMetadata
import com.jjw.easygallery.core.data.drive.DriveHttpClient
import com.jjw.easygallery.core.data.remote.RemoteUploader
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

open class DriveUploadException(message: String, val httpCode: Int? = null) : IOException(message)

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
                appProperties = mapOf(PROP_MEDIA_STORE_ID to source.mediaId.toString()),
            ),
            contentType = source.mimeType.ifBlank { DEFAULT_MIME_TYPE },
            contentLength = length,
        )
        if (!response.isSuccessful) {
            throw DriveUploadException("업로드 세션 생성 실패 (${response.code()})", response.code())
        }
        return response.headers()["Location"] ?: throw DriveUploadException("업로드 세션 URI 가 없습니다")
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
                else -> throw DriveUploadException("세션 상태 조회 실패 (${response.code})", response.code)
            }
        }
    }

    /** [offset] 부터 끝까지 스트리밍 PUT. 진행률은 절대 바이트로 보고한다. */
    override fun upload(source: UploadSource, sessionUri: String, offset: Long, length: Long): Flow<UploadEvent> =
        channelFlow {
            val mimeType = source.mimeType.ifBlank { DEFAULT_MIME_TYPE }
            send(UploadEvent.Progress(offset, length))
            val body = ContentUriRequestBody(
                resolver = context.contentResolver,
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
                    else -> throw DriveUploadException("업로드 실패 (${response.code})", response.code)
                }
            }
            Timber.d("uploaded %s -> %s", source.displayName, file.id)
            send(UploadEvent.Completed(file.id))
        }.flowOn(ioDispatcher)

    private fun parseFile(response: Response): DriveFileDto =
        json.decodeFromString(DriveFileDto.serializer(), response.body.string())

    private companion object {
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        val DEFAULT_MEDIA_TYPE = DEFAULT_MIME_TYPE.toMediaTypeOrNull()!!
        const val PROP_MEDIA_STORE_ID = "mediaStoreId"
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
