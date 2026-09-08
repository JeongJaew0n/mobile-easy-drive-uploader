package com.jjw.easygallery.core.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 제공자 공통 HTTP 오류. [httpCode] 4xx 는 영구 실패, 그 외는 재시도 대상(워커 규칙) */
open class RemoteStorageException(message: String, val httpCode: Int? = null) : IOException(message)

/** 제공자가 지원하지 않는 동작(폴더 이름 변경 등). UI 는 능력 집합으로 미리 숨기므로 방어용 */
class UnsupportedOperationException(message: String) : RemoteStorageException(message)

/** OkHttp 호출을 코루틴으로. 취소하면 요청도 취소한다 */
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        },
    )
    cont.invokeOnCancellation { cancel() }
}

/** 성공(2xx)이 아니면 본문 앞부분을 담아 던진다 */
fun Response.requireSuccess(what: String): Response {
    if (isSuccessful) return this
    val detail = runCatching { body.string().take(MAX_ERROR_BODY) }.getOrNull()?.takeIf { it.isNotBlank() }
    close()
    throw RemoteStorageException("$what 실패 ($code)${detail?.let { ": $it" } ?: ""}", code)
}

private const val MAX_ERROR_BODY = 300
