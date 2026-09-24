package com.jjw.easygallery.core.data.auth

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Play Services AuthorizationClient 로 Google 계정 선택 + Drive scope 동의를 한 번에 처리한다.
 * 서버가 없으므로 Credential Manager(ID 토큰) 는 쓰지 않고, 계정 정보는 Drive `about` API 로 얻는다.
 * Android OAuth 클라이언트(패키지명 + SHA-1) 만 등록하면 되고 클라이언트 ID 를 앱에 심지 않는다.
 */
@Singleton
class GoogleAuthRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
) : AuthRepository {

    private val client get() = Identity.getAuthorizationClient(context)
    private val mutex = Mutex()

    @Volatile
    private var cached: CachedToken? = null

    override suspend fun beginSignIn(): SignInStep {
        val result = authorize(account = null)
        return if (result.hasResolution()) {
            SignInStep.NeedsConsent(requireNotNull(result.pendingIntent))
        } else {
            cache(result)
            SignInStep.Completed
        }
    }

    override suspend fun completeSignIn(data: Intent?) {
        if (data == null) throw SignInCancelledException()
        val result = try {
            client.getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            // RESULT_CANCELED 는 onConsentResult 가 이미 걸렀으므로 여기 오는 ApiException 은 진짜 오류다
            Timber.w(e, "authorization result parse failed: status=%d", e.statusCode)
            throw AuthFailedException(e.statusCode, e.statusMessage, e)
        }
        cache(result)
    }

    override suspend fun beginFolderPick(): SignInStep {
        val email = prefs.current().accountEmail ?: throw NotSignedInException()
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
            // 이전에 동의한 넓은 scope 가 토큰에 딸려 오는 것을 막는다 (SS-10)
            .setOptOutIncludingGrantedScopes(true)
            .setPrompt(AuthorizationRequest.Prompt.CONSENT)
            // 폴더 선택(PICKER_ALLOW_FOLDER_SELECTION)은 줘도 동작하지 않는다(DRV-P3).
            // 그래서 폴더 안의 파일을 하나 고르게 하고 그 parents 를 쓴다 — docs/DRIVE_FILE_SCOPE.md §4
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
            .build()
        val result = try {
            client.authorize(request).await()
        } catch (e: ApiException) {
            Timber.w(e, "folder picker failed: status=%d", e.statusCode)
            throw AuthFailedException(e.statusCode, e.statusMessage, e)
        }
        val pending = result.pendingIntent
        return if (pending != null) SignInStep.NeedsConsent(pending) else SignInStep.Completed
    }

    override suspend fun completeFolderPick(data: Intent?): List<String> {
        if (data == null) return emptyList()
        val result = try {
            client.getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            Timber.w(e, "picker result parse failed: status=%d", e.statusCode)
            throw AuthFailedException(e.statusCode, e.statusMessage, e)
        }
        result.accessToken?.let { cache(result) }
        return parsePicked(result.tokenResponseParams)
    }

    override suspend fun getAccessToken(): String {
        cached?.takeIf { it.isFresh() }?.let { return it.token }
        return mutex.withLock {
            cached?.takeIf { it.isFresh() }?.let { return it.token }
            val email = prefs.current().accountEmail ?: throw NotSignedInException()
            val result = authorize(Account(email, GOOGLE_ACCOUNT_TYPE))
            if (result.hasResolution()) throw AuthorizationRequiredException()
            cache(result).token
        }
    }

    override fun invalidateToken() {
        cached = null
    }

    override suspend fun signOut() {
        val token = cached?.token
        cached = null
        prefs.clearAccount()
        if (token != null) {
            runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
                .onFailure { Timber.w(it, "clearToken failed") }
        }
    }

    private suspend fun authorize(account: Account?): AuthorizationResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            // 계정에 남은 이전 동의(`drive` 전체)가 토큰에 딸려 오는 것을 막는다 — SS-10
            .setOptOutIncludingGrantedScopes(true)
            .apply { if (account != null) setAccount(account) }
            .build()
        Timber.i("authorize scope=%s account=%s", DRIVE_SCOPE, account?.name?.let { "set" } ?: "picker")
        return try {
            val result = client.authorize(request).await()
            Timber.i(
                "authorize result: hasResolution=%s grantedScopes=%s",
                result.hasResolution(),
                result.grantedScopes,
            )
            result
        } catch (e: ApiException) {
            Timber.w(e, "authorize failed: status=%d", e.statusCode)
            throw AuthFailedException(e.statusCode, e.statusMessage, e)
        }
    }

    private fun cache(result: AuthorizationResult): CachedToken {
        val token = result.accessToken ?: throw AuthorizationRequiredException()
        return CachedToken(token, SystemClock.elapsedRealtime()).also { cached = it }
    }

    /** 피커는 고른 항목의 ID 를 [PICKED_IDS_KEY] 에 콤마로 이어 담아 준다(2026-09-24 기기에서 확인). */
    private fun parsePicked(params: Bundle?): List<String> {
        if (params == null) {
            Timber.w("picker returned no params")
            return emptyList()
        }
        val raw = params.getString(PICKED_IDS_KEY).orEmpty()
        Timber.i("picker picked=%s keys=%s", raw, params.keySet())
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private class CachedToken(val token: String, private val obtainedAt: Long) {
        fun isFresh(): Boolean = SystemClock.elapsedRealtime() - obtainedAt < TOKEN_TTL_MILLIS
    }

    companion object {
        /**
         * 앱이 만든 파일과 사용자가 피커로 고른 항목만 접근한다.
         * restricted 가 아닌 scope 라 스토어 공개 시 Google 보안 심사(CASA)를 받지 않는다.
         * 대신 사용자의 기존 파일·폴더는 보이지 않는다 — 어디에 올릴지는 피커로 고르게 한다.
         */
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val PICKED_IDS_KEY = "picked_file_ids"

        // 실제 만료는 1시간. 여유를 두고 갱신하고, 그래도 401 이 오면 Authenticator 가 재시도.
        private const val TOKEN_TTL_MILLIS = 45L * 60 * 1_000
    }
}
