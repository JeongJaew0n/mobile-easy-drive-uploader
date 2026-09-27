package com.jjw.easygallery.core.data.drive

import com.jjw.easygallery.core.data.auth.TokenProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject

/**
 * Google 요청에 Bearer 토큰을 붙인다. OkHttp 스레드에서 실행되므로 runBlocking 이 허용된다.
 *
 * **Google 호스트에만** 붙인다. 이 클라이언트는 API(`googleapis.com`)뿐 아니라 이미지 로더로 썸네일
 * (`googleusercontent.com`)도 부른다(`docs/DRIVE_PHOTO_GRID.md`). 주소가 서버에서 오는 이상, 혹시라도
 * 다른 호스트를 가리키면 토큰이 그리로 새는 것을 막는다.
 */
class AuthInterceptor @Inject constructor(
    private val tokenProvider: TokenProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.isGoogleHost()) return chain.proceed(request)
        val token = runBlocking { tokenProvider.getAccessToken() }
        return chain.proceed(request.withBearer(token))
    }
}

/** 401 이면 토큰 캐시를 버리고 한 번만 재시도한다. */
class TokenAuthenticator @Inject constructor(
    private val tokenProvider: TokenProvider,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null
        if (!response.request.url.host.isGoogleHost()) return null
        tokenProvider.invalidateToken()
        val token = runBlocking { tokenProvider.getAccessToken() }
        return response.request.withBearer(token)
    }
}

/** 토큰을 붙여도 되는 곳 — API 와 썸네일·파일 내용을 주는 Google 호스트 */
internal fun String.isGoogleHost(): Boolean =
    GOOGLE_HOST_SUFFIXES.any { suffix -> this == suffix.removePrefix(".") || endsWith(suffix) }

private val GOOGLE_HOST_SUFFIXES = listOf(".googleapis.com", ".googleusercontent.com")

private fun Request.withBearer(token: String): Request =
    newBuilder().header("Authorization", "Bearer $token").build()
