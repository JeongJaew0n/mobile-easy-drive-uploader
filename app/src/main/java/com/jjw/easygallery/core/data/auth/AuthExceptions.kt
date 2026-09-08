package com.jjw.easygallery.core.data.auth

import java.io.IOException

/** OkHttp 인터셉터에서 던질 수 있어야 하므로 IOException 을 상속한다. */
sealed class AuthException(message: String) : IOException(message)

class NotSignedInException : AuthException("Google 계정에 로그인되어 있지 않습니다")

/** 이전 동의가 철회되었거나 새 scope 가 필요해 사용자 상호작용이 필요한 상태. */
class AuthorizationRequiredException : AuthException("Google Drive 접근 권한을 다시 허용해야 합니다")

class SignInCancelledException : AuthException("로그인이 취소되었습니다")

/**
 * Play 서비스 인증 API 가 상태 코드로 실패. 10 DEVELOPER_ERROR(패키지명·SHA-1 미등록), 7 NETWORK_ERROR,
 * 8 INTERNAL_ERROR, 17 API_NOT_CONNECTED. `docs/GOOGLE_SIGN_IN_TROUBLESHOOTING.md` §3
 */
class AuthFailedException(val statusCode: Int, detail: String?, cause: Throwable? = null) :
    AuthException("Google 인증 실패 ($statusCode)${detail?.let { ": $it" } ?: ""}") {
    init {
        if (cause != null) initCause(cause)
    }
}
