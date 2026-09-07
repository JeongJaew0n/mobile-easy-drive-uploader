package com.jjw.easygallery.core.data.upload

import android.content.Context
import com.jjw.easygallery.core.common.di.AppDispatcher
import com.jjw.easygallery.core.common.di.Dispatcher
import com.jjw.easygallery.core.data.drive.DriveApi
import com.jjw.easygallery.core.data.drive.DriveFileDto
import com.jjw.easygallery.core.data.drive.DriveFileMetadata
import com.jjw.easygallery.core.data.drive.DriveHttpClient
import com.jjw.easygallery.core.domain.model.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface UploadEvent {
    data class Progress(val bytesSent: Long, val totalBytes: Long) : UploadEvent {
        val fraction: Float get() = if (totalBytes > 0) (bytesSent.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    data class Completed(val driveFileId: String) : UploadEvent
}

class DriveUploadException(message: String, val httpCode: Int? = null) : IOException(message)

/**
 * Drive 재개 가능 업로드(resumable) 로 한 항목을 올린다.
 * 1) 세션 URI 발급 → 2) 본문 PUT (스트리밍, 진행률). 중단 후 재개는 다음 단계(WorkManager) 에서 세션 URI 저장으로 확장.
 */
@Singleton
class DriveUploader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: DriveApi,
    @param:DriveHttpClient private val client: OkHttpClient,
    private val json: Json,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    fun upload(item: MediaItem, folderId: String): Flow<UploadEvent> = channelFlow {
        val resolver = context.contentResolver
        // 일부 프로바이더는 descriptor 를 열 수 없거나 길이를 모른다(-1) → MediaStore SIZE 로 대체
        val length = runCatching { resolver.openAssetFileDescriptor(item.uri, "r")?.use { it.length } }
            .getOrNull()
            ?.takeIf { it >= 0 }
            ?: item.sizeBytes
        val mimeType = item.mimeType.ifBlank { DEFAULT_MIME_TYPE }

        val session = api.startResumableUpload(
            metadata = DriveFileMetadata(
                name = item.displayName,
                parents = listOf(folderId),
                appProperties = mapOf(PROP_MEDIA_STORE_ID to item.id.toString()),
            ),
            contentType = mimeType,
            contentLength = length,
        )
        if (!session.isSuccessful) {
            throw DriveUploadException("업로드 세션 생성 실패 (${session.code()})", session.code())
        }
        val sessionUri = session.headers()["Location"]
            ?: throw DriveUploadException("업로드 세션 URI 가 없습니다")
        send(UploadEvent.Progress(0, length))

        val body = ContentUriRequestBody(
            resolver = resolver,
            uri = item.uri,
            mediaType = mimeType.toMediaTypeOrNull() ?: DEFAULT_MEDIA_TYPE,
            length = length,
        ) { sent -> trySend(UploadEvent.Progress(sent, length)) }
        val request = Request.Builder().url(sessionUri).put(body).build()
        val file = client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw DriveUploadException("업로드 실패 (${response.code})", response.code)
            }
            val text = response.body.string()
            json.decodeFromString(DriveFileDto.serializer(), text)
        }
        Timber.d("uploaded %s -> %s", item.displayName, file.id)
        send(UploadEvent.Completed(file.id))
    }.flowOn(ioDispatcher)

    private companion object {
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        val DEFAULT_MEDIA_TYPE = DEFAULT_MIME_TYPE.toMediaTypeOrNull()!!
        const val PROP_MEDIA_STORE_ID = "mediaStoreId"
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
