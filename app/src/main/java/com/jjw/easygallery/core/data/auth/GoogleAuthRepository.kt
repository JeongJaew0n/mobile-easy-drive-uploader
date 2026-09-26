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
import com.google.android.gms.common.api.CommonStatusCodes
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

    /** 방금 401 을 받아 버린 토큰. 같은 것이 또 오면 Play 서비스 캐시까지 비운다 */
    @Volatile
    private var rejectedToken: String? = null

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

    override suspend fun beginViewScopeConsent(): SignInStep {
        val email = prefs.current().accountEmail ?: throw NotSignedInException()
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE), Scope(DRIVE_READONLY_SCOPE)))
            .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
            .setOptOutIncludingGrantedScopes(true)
            .build()
        val result = try {
            client.authorize(request).await()
        } catch (e: ApiException) {
            Timber.w(e, "view scope consent failed: status=%d", e.statusCode)
            throw AuthFailedException(e.statusCode, e.statusMessage, e)
        }
        val pending = result.pendingIntent
        if (pending != null) return SignInStep.NeedsConsent(pending)
        // 동의 UI 없이 돌아왔다면 이미 허락돼 있다는 뜻 — 그래도 실제로 들어왔는지 확인한다
        if (!result.hasReadonly()) throw AuthorizationRequiredException()
        grantViewScope(result)
        return SignInStep.Completed
    }

    override suspend fun completeViewScopeConsent(data: Intent?): Boolean {
        if (data == null) return false
        val result = try {
            client.getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            Timber.w(e, "view scope result parse failed: status=%d", e.statusCode)
            throw AuthFailedException(e.statusCode, e.statusMessage, e)
        }
        Timber.i("view scope grantedScopes=%s", result.grantedScopes)
        // 동의 화면에서 읽기 권한만 체크를 풀 수 있다. 그때는 켜면 안 된다
        if (!result.hasReadonly()) return false
        grantViewScope(result)
        return true
    }

    private suspend fun grantViewScope(result: AuthorizationResult) {
        prefs.setDriveViewScopeGranted(true)
        // 예전 토큰에는 읽기 권한이 없다. 새로 받은 것이 있으면 그걸 쓰고, 없으면 캐시를 버린다
        if (result.accessToken != null) cache(result) else cached = null
    }

    private fun AuthorizationResult.hasReadonly(): Boolean =
        grantedScopes.any { it == DRIVE_READONLY_SCOPE }

    override suspend fun beginGuestPick(): GuestPick {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            .setOptOutIncludingGrantedScopes(true)
            .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
            .build()
        val result = try {
            client.authorize(request).await()
        } catch (e: ApiException) {
            Timber.w(e, "guest pick failed: status=%d", e.statusCode)
            throw AuthFailedException(e.statusCode, e.statusMessage, e)
        }
        result.pendingIntent?.let { return GuestPick.NeedsChooser(it) }
        // 주 계정 캐시(cached)에 넣지 않는다 — B 의 토큰이 A 의 자리를 차지하면 A 의 업로드가 B 로 간다
        return GuestPick.Picked(result.accessToken ?: throw AuthorizationRequiredException())
    }

    override suspend fun completeGuestPick(data: Intent?): String {
        if (data == null) throw SignInCancelledException()
        val result = try {
            client.getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            // 선택 창을 닫으면 결과 인텐트가 null 이 아니라 status 16 을 담아 온다(기기에서 확인).
            // 취소는 실패가 아니다 — "Google 인증 실패 (16)" 을 띄우면 사용자는 뭔가 고장 난 줄 안다
            if (e.statusCode == CommonStatusCodes.CANCELED) throw SignInCancelledException()
            Timber.w(e, "guest pick result parse failed: status=%d", e.statusCode)
            throw AuthFailedException(e.statusCode, e.statusMessage, e)
        }
        // 여기서도 cache() 를 부르지 않는다. 위와 같은 이유
        return result.accessToken ?: throw AuthorizationRequiredException(result.pendingIntent)
    }

    override suspend fun getAccessToken(): String {
        cached?.takeIf { it.isFresh() }?.let { return it.token }
        return mutex.withLock {
            cached?.takeIf { it.isFresh() }?.let { return it.token }
            val email = prefs.current().accountEmail ?: throw NotSignedInException()
            val account = Account(email, GOOGLE_ACCOUNT_TYPE)
            var result = authorize(account)
            if (result.hasResolution()) throw AuthorizationRequiredException(result.pendingIntent)

            // 401 을 받아 버린 토큰을 Play 서비스가 그대로 다시 주는 경우가 있다. 우리 캐시만
            // 비워서는 소용없고 GMS 쪽도 비워야 진짜 새 토큰이 나온다 — 그러지 않으면 대량
            // 업로드 중 토큰이 만료됐을 때 401 이 반복된다.
            val rejected = rejectedToken
            if (rejected != null && result.accessToken == rejected) {
                Timber.i("token unchanged after invalidate; clearing Play services cache")
                runCatching { client.clearToken(ClearTokenRequest.builder().setToken(rejected).build()).await() }
                    .onFailure { Timber.w(it, "clearToken failed") }
                result = authorize(account)
                if (result.hasResolution()) throw AuthorizationRequiredException(result.pendingIntent)
            }
            rejectedToken = null
            cache(result).token
        }
    }

    override fun invalidateToken() {
        rejectedToken = cached?.token
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

    /**
     * 요청할 scope. 기본은 `drive.file` 하나고, 보기 전용 폴더를 옵트인한 사용자만
     * `drive.readonly` 가 붙는다(`docs/DRIVE_FILE_SCOPE.md` §10).
     *
     * `setOptOutIncludingGrantedScopes(true)` 를 쓰기 때문에 **여기 넣지 않은 scope 는
     * 토큰에 실리지 않는다.** 읽기 권한을 허락받고도 빼먹으면 보기 폴더가 조용히 404 가 된다.
     */
    private suspend fun requestedScopes(): List<Scope> =
        if (prefs.current().driveViewScopeGranted) {
            listOf(Scope(DRIVE_SCOPE), Scope(DRIVE_READONLY_SCOPE))
        } else {
            listOf(Scope(DRIVE_SCOPE))
        }

    private suspend fun authorize(account: Account?): AuthorizationResult {
        val scopes = requestedScopes()
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            // 계정에 남은 이전 동의(`drive` 전체)가 토큰에 딸려 오는 것을 막는다 — SS-10
            .setOptOutIncludingGrantedScopes(true)
            .apply { if (account != null) setAccount(account) }
            .build()
        Timber.i("authorize scopes=%s account=%s", scopes, account?.name?.let { "set" } ?: "picker")
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

        /**
         * 보기 전용 폴더에만 쓰는 **옵트인** scope. 내 드라이브 전체를 읽을 수 있다.
         *
         * restricted scope 라 Play 프로덕션 공개 시 CASA 심사를 부른다. 그래서 기본으로
         * 요청하지 않고, 사용자가 "볼 수 있는 폴더 추가" 를 누른 순간에만 받는다.
         * 이 기능을 접으면 이 상수를 쓰는 자리만 지우면 된다 — `docs/DRIVE_FILE_SCOPE.md` §10.
         */
        const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val PICKED_IDS_KEY = "picked_file_ids"

        // 실제 만료는 1시간. 여유를 두고 갱신하고, 그래도 401 이 오면 Authenticator 가 재시도.
        private const val TOKEN_TTL_MILLIS = 45L * 60 * 1_000
    }
}
