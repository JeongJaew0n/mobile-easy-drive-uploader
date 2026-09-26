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

    /**
     * Drive 폴더 피커를 띄운다. `drive.file` 에서 앱이 남의 폴더에 접근할 수 있는 유일한 경로다.
     * 항상 사용자 UI 가 필요하므로 [SignInStep.NeedsConsent] 를 기대한다.
     */
    suspend fun beginFolderPick(): SignInStep

    /** 피커 결과 인텐트에서 고른 항목의 Drive 파일 ID 를 꺼낸다. 아무것도 고르지 않았으면 빈 목록. */
    suspend fun completeFolderPick(data: Intent?): List<String>

    /**
     * 보기 전용 폴더를 위해 `drive.readonly` 를 **추가로** 받는다(`docs/DRIVE_FILE_SCOPE.md` §10).
     *
     * 기본 설치는 `drive.file` 뿐이고, 사용자가 이 기능을 쓰겠다고 할 때만 한 번 더 묻는다.
     * 이미 허락돼 있으면 [SignInStep.Completed] 가 온다.
     */
    suspend fun beginViewScopeConsent(): SignInStep

    /**
     * 동의 결과를 확인한다. 사용자가 읽기 권한 체크를 풀었을 수 있으므로 `grantedScopes` 를 본다.
     *
     * @return 실제로 `drive.readonly` 를 받았으면 true.
     */
    suspend fun completeViewScopeConsent(data: Intent?): Boolean

    suspend fun signOut()
}
