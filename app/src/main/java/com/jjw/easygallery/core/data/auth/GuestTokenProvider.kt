package com.jjw.easygallery.core.data.auth

import android.accounts.Account
import android.content.Context
import android.os.SystemClock
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * "다른 계정 업로드" 의 B 토큰(`docs/plans/guest-account-upload/spec.md` §4.1).
 *
 * 주 계정 A 의 [GoogleAuthRepository] 와 **완전히 따로** 돈다 — A 의 연결 정보를 읽지도 쓰지도 않는다.
 * Play 서비스에는 "주 계정" 이 없어서 `setAccount(B)` 로 B 의 토큰만 따로 받을 수 있다.
 *
 * 토큰을 오래 들고 있지 않고 필요할 때마다 받는다. B 가 기기에 있는 동안은 동의 없이 조용히 나오므로,
 * 업로드 도중 앱이 죽어도 워커가 다시 받아 이어 올린다. B 를 기기에서 빼면 여기서 실패한다.
 */
class GuestTokenProvider(
    private val context: Context,
    val email: String,
) : TokenProvider {

    private val client get() = Identity.getAuthorizationClient(context)
    private val mutex = Mutex()

    @Volatile
    private var cached: Pair<String, Long>? = null

    /** 401 을 받아 버린 토큰. 같은 것이 또 오면 Play 서비스 캐시까지 비운다(A 쪽과 같은 이유) */
    @Volatile
    private var rejected: String? = null

    override suspend fun getAccessToken(): String {
        fresh()?.let { return it }
        return mutex.withLock {
            fresh()?.let { return it }
            var result = authorize()
            val bad = rejected
            if (bad != null && result.accessToken == bad) {
                runCatching { client.clearToken(ClearTokenRequest.builder().setToken(bad).build()).await() }
                    .onFailure { Timber.w(it, "guest clearToken failed") }
                result = authorize()
            }
            rejected = null
            val token = result.accessToken ?: throw AuthorizationRequiredException(result.pendingIntent)
            cached = token to SystemClock.elapsedRealtime()
            token
        }
    }

    override fun invalidateToken() {
        rejected = cached?.first
        cached = null
    }

    private fun fresh(): String? =
        cached?.takeIf { SystemClock.elapsedRealtime() - it.second < TOKEN_TTL_MILLIS }?.first

    private suspend fun authorize(): AuthorizationResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GoogleAuthRepository.DRIVE_SCOPE)))
            .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
            .setOptOutIncludingGrantedScopes(true)
            .build()
        val result = try {
            client.authorize(request).await()
        } catch (e: ApiException) {
            // B 가 기기에서 빠졌으면 여기로 온다
            Timber.w(e, "guest authorize failed: status=%d", e.statusCode)
            throw AuthFailedException(e.statusCode, e.statusMessage, e)
        }
        if (result.hasResolution()) throw AuthorizationRequiredException(result.pendingIntent)
        return result
    }

    private companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        const val TOKEN_TTL_MILLIS = 45L * 60 * 1_000
    }
}
