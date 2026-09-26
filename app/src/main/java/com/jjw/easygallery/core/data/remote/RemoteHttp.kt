package com.jjw.easygallery.core.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 제공자 공통 HTTP 오류. [httpCode] 4xx 는 영구 실패, 그 외는 재시도 대상(워커 규칙).
 *
 * [reason] 은 서버가 준 기계용 코드(`storageQuotaExceeded` 등)다. 화면은 이걸로 제대로 된
 * 한국어 문장을 고르고, 워커는 "다시 해도 소용없는 오류" 를 가려낸다.
 */
open class RemoteStorageException(
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
    val reason: String? = null,
) : IOException(message, cause) {

    /**
     * 요청 한도에 걸린 것인가. 4xx 지만 **다시 하면 되는** 오류라 영구 실패로 버리면 안 된다.
     * Drive 는 한도 초과를 403(`userRateLimitExceeded`)으로도, 429 로도 돌려준다.
     *
     * 코드를 받았으면 그걸 믿는다 — 본문 문자열을 뒤지는 것은 코드가 없을 때의 대비책이다.
     */
    val isRateLimited: Boolean
        get() = when {
            httpCode == TOO_MANY_REQUESTS -> true
            reason != null -> reason in RATE_LIMIT_REASONS
            else -> httpCode == FORBIDDEN && RATE_LIMIT_HINTS.any { message.orEmpty().contains(it, true) }
        }

    /**
     * **다시 해도 소용없는** 오류인가. 대량 업로드에서 이게 나오면 남은 것을 계속 시도할 이유가 없다.
     *
     * 실제로 겪었다 — Drive 용량이 찬 채로 1553건이 하나씩 403 을 받고 모두 실패했다.
     * 사용자는 "1553개 실패" 만 보고 이유를 몰랐고, 서버에는 쓸데없는 요청이 1553번 갔다.
     */
    val isHopeless: Boolean get() = reason in HOPELESS_REASONS

    /**
     * 4xx 지만 다시 하면 될 수 있는 것들. 여기 해당하면 영구 실패로 버리지 않는다.
     *
     * 401 은 토큰이 만료된 것이다. 대량 업로드가 토큰 수명을 넘기면 실제로 만나고,
     * 새 토큰을 받아 다시 하면 된다 — 버리면 사용자는 "몇 장이 빠졌다" 를 겪는다.
     */
    val isRetryable: Boolean
        get() = isRateLimited || httpCode == UNAUTHORIZED

    private companion object {
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val TOO_MANY_REQUESTS = 429
        val RATE_LIMIT_HINTS = listOf("rateLimitExceeded", "userRateLimitExceeded", "rate limit")
        val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded")

        /** 저장 공간이 찬 것은 사용자가 Drive 에서 비우기 전에는 무엇을 해도 안 된다 */
        val HOPELESS_REASONS = setOf("storageQuotaExceeded")
    }
}

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
    val raw = runCatching { body.string().take(MAX_ERROR_BODY) }.getOrNull()
    close()
    // 본문을 통째로 붙이지 않는다 — 목록 한 칸에 JSON 이 들어가면 잘려서 아무것도 못 읽는다
    val parsed = parseRemoteErrorBody(raw)
    throw RemoteStorageException(
        message = "$what 실패 ($code)${parsed.message?.let { ": $it" }.orEmpty()}",
        httpCode = code,
        reason = parsed.reason,
    )
}

private const val MAX_ERROR_BODY = 2000
