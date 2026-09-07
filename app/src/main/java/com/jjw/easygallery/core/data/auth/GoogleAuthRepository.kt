package com.jjw.easygallery.core.data.auth

import android.accounts.Account
import android.content.Context
import android.content.Intent
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
            Timber.w(e, "authorization result parse failed: status=%d", e.statusCode)
            throw SignInCancelledException()
        }
        cache(result)
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
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .apply { if (account != null) setAccount(account) }
            .build()
        return client.authorize(request).await()
    }

    private fun cache(result: AuthorizationResult): CachedToken {
        val token = result.accessToken ?: throw AuthorizationRequiredException()
        return CachedToken(token, SystemClock.elapsedRealtime()).also { cached = it }
    }

    private class CachedToken(val token: String, private val obtainedAt: Long) {
        fun isFresh(): Boolean = SystemClock.elapsedRealtime() - obtainedAt < TOKEN_TTL_MILLIS
    }

    companion object {
        /** 앱이 만든 파일만 접근 — Google 검증 없이 사용 가능한 non-sensitive scope */
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"

        // 실제 만료는 1시간. 여유를 두고 갱신하고, 그래도 401 이 오면 Authenticator 가 재시도.
        private const val TOKEN_TTL_MILLIS = 45L * 60 * 1_000
    }
}
