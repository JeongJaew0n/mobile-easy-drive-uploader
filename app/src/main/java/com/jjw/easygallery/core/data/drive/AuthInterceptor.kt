package com.jjw.easygallery.core.data.drive

import com.jjw.easygallery.core.data.auth.TokenProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject

/** 모든 요청에 Bearer 토큰을 붙인다. OkHttp 스레드에서 실행되므로 runBlocking 이 허용된다. */
class AuthInterceptor @Inject constructor(
    private val tokenProvider: TokenProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { tokenProvider.getAccessToken() }
        return chain.proceed(chain.request().withBearer(token))
    }
}

/** 401 이면 토큰 캐시를 버리고 한 번만 재시도한다. */
class TokenAuthenticator @Inject constructor(
    private val tokenProvider: TokenProvider,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null
        tokenProvider.invalidateToken()
        val token = runBlocking { tokenProvider.getAccessToken() }
        return response.request.withBearer(token)
    }
}

private fun Request.withBearer(token: String): Request =
    newBuilder().header("Authorization", "Bearer $token").build()
