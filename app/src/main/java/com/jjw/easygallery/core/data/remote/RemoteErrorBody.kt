package com.jjw.easygallery.core.data.remote

import org.json.JSONObject

/**
 * 서버가 준 오류 본문에서 뽑아낸 것.
 *
 * [reason] 은 Google API 의 기계용 코드(`storageQuotaExceeded` 등). 있으면 화면이 이걸로
 * 제대로 된 한국어 문장을 고른다. [message] 는 사람이 읽을 한 줄로, 코드가 없거나 우리가
 * 모르는 코드일 때 쓰는 대비책이다.
 */
data class RemoteErrorBody(val reason: String?, val message: String?)

/**
 * Google API 오류 JSON 을 사람이 읽을 것과 기계가 읽을 것으로 가른다.
 *
 * ```json
 * {"error":{"code":403,"message":"The user's Drive storage quota has been exceeded.",
 *           "errors":[{"reason":"storageQuotaExceeded", ...}]}}
 * ```
 *
 * **왜 필요한가.** 예전에는 이 본문을 그대로 오류 메시지에 이어 붙였다. 업로드 목록의
 * 두 줄짜리 자리에 JSON 이 들어가니 `업로드 실패 (403): {  "error": {…` 에서 잘려,
 * 사용자는 무엇이 잘못됐는지 알 수 없었다(2026-09-26 기기에서 확인).
 *
 * JSON 이 아니거나 모양이 다르면 [reason] 없이 **첫 줄만** 잘라서 돌려준다 — 그래도
 * 통짜 본문보다는 읽을 만하다.
 */
fun parseRemoteErrorBody(body: String?): RemoteErrorBody {
    val trimmed = body?.trim().orEmpty()
    if (trimmed.isEmpty()) return RemoteErrorBody(null, null)
    val error = runCatching { JSONObject(trimmed).optJSONObject("error") }.getOrNull()
        ?: return RemoteErrorBody(null, firstLineOf(trimmed))
    val reason = error.optJSONArray("errors")
        ?.takeIf { it.length() > 0 }
        ?.optJSONObject(0)
        ?.optString("reason")
        ?.takeIf { it.isNotBlank() }
    val message = error.optString("message").takeIf { it.isNotBlank() }
    return RemoteErrorBody(reason, message?.let { firstLineOf(it) })
}

/** 한 줄로 줄인다. 목록 한 칸에 들어가야 하고, 줄바꿈이 들어가면 그 자체로 잘려 보인다 */
private fun firstLineOf(text: String): String =
    text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_MESSAGE).orEmpty()

private const val MAX_MESSAGE = 160
