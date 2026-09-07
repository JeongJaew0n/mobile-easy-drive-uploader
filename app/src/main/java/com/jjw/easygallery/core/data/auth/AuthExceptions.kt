package com.jjw.easygallery.core.data.auth

import java.io.IOException

/** OkHttp 인터셉터에서 던질 수 있어야 하므로 IOException 을 상속한다. */
sealed class AuthException(message: String) : IOException(message)

class NotSignedInException : AuthException("Google 계정에 로그인되어 있지 않습니다")

/** 이전 동의가 철회되었거나 새 scope 가 필요해 사용자 상호작용이 필요한 상태. */
class AuthorizationRequiredException : AuthException("Google Drive 접근 권한을 다시 허용해야 합니다")

class SignInCancelledException : AuthException("로그인이 취소되었습니다")
