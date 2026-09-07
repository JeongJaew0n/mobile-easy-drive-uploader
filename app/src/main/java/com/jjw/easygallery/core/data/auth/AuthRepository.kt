package com.jjw.easygallery.core.data.auth

import android.app.PendingIntent
import android.content.Intent

sealed interface SignInStep {
    /** 이미 동의가 있어 추가 UI 없이 완료됨 */
    data object Completed : SignInStep

    /** 사용자 동의 화면을 띄워야 함. UI 가 [pendingIntent] 를 실행한 뒤 [AuthRepository.completeSignIn] 을 호출 */
    data class NeedsConsent(val pendingIntent: PendingIntent) : SignInStep
}

/** OkHttp 계층이 필요로 하는 최소 인터페이스. Drive 계층과의 순환 의존을 막기 위해 분리. */
interface TokenProvider {
    /** 유효한 액세스 토큰. 없으면 [NotSignedInException] 또는 [AuthorizationRequiredException]. */
    suspend fun getAccessToken(): String

    /** 401 등으로 토큰이 무효해졌을 때 캐시를 버린다. */
    fun invalidateToken()
}

interface AuthRepository : TokenProvider {
    suspend fun beginSignIn(): SignInStep

    /** 동의 화면 결과 인텐트를 넘긴다. 취소면 [SignInCancelledException]. */
    suspend fun completeSignIn(data: Intent?)

    suspend fun signOut()
}
