package com.jjw.easygallery.core.data.auth

import android.app.PendingIntent
import com.jjw.easygallery.R
import com.jjw.easygallery.core.common.text.LocalizedError
import com.jjw.easygallery.core.common.text.UiText
import java.io.IOException

/** OkHttp 인터셉터에서 던질 수 있어야 하므로 IOException 을 상속한다. */
sealed class AuthException(message: String, override val uiText: UiText) : IOException(message), LocalizedError

class NotSignedInException : AuthException("not signed in", UiText(R.string.error_not_signed_in))

/**
 * 이전 동의가 철회되었거나 새 scope 가 필요해 사용자 상호작용이 필요한 상태.
 *
 * [pendingIntent] 를 실으면 화면이 그대로 재동의를 띄울 수 있다. 이게 없으면 사용자는
 * 설정에서 연결을 끊었다 다시 잇는 것 말고는 빠져나갈 길이 없다(SS-11).
 * 게시 상태가 "테스트" 인 동안에는 토큰이 주기적으로 만료되므로 실제로 겪는 상황이다.
 */
class AuthorizationRequiredException(val pendingIntent: PendingIntent? = null) :
    AuthException("authorization required", UiText(R.string.error_authorization_required))

class SignInCancelledException : AuthException("sign-in cancelled", UiText(R.string.error_sign_in_cancelled))

/**
 * Play 서비스 인증 API 가 상태 코드로 실패. 10 DEVELOPER_ERROR(패키지명·SHA-1 미등록), 7 NETWORK_ERROR,
 * 8 INTERNAL_ERROR, 17 API_NOT_CONNECTED. `docs/GOOGLE_SIGN_IN_TROUBLESHOOTING.md` §3
 */
class AuthFailedException(val statusCode: Int, detail: String?, cause: Throwable? = null) :
    AuthException(
        "auth failed ($statusCode)${detail?.let { ": $it" } ?: ""}",
        if (detail == null) {
            UiText(R.string.error_auth_failed, statusCode)
        } else {
            UiText(R.string.error_auth_failed_detail, statusCode, detail)
        },
    ) {
    init {
        if (cause != null) initCause(cause)
    }
}
