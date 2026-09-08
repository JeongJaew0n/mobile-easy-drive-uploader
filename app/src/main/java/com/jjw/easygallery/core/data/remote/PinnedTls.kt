package com.jjw.easygallery.core.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/**
 * 자체 서명 인증서를 쓰는 NAS 를 위한 지문 고정(`docs/NAS_STORAGE.md` §2).
 * - [pinCertificate]: 서버 리프 인증서의 SHA-256 이 저장된 값과 같을 때만 연결. 호스트 이름은 검사하지 않는다(인증서 자체를 고정했으므로)
 * - [fetchServerCertificateSha256]: 사용자가 신뢰 여부를 결정할 수 있게 지문만 읽어 온다. 이 클라이언트는 데이터 요청에 쓰지 않는다
 */
fun OkHttpClient.Builder.pinCertificate(sha256Hex: String): OkHttpClient.Builder {
    val expected = sha256Hex.lowercase().replace(":", "")
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("서버 인증서가 없습니다")
            if (leaf.sha256Hex() != expected) throw CertificateException("인증서 지문이 저장된 값과 다릅니다")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
    return sslSocketFactory(context.socketFactory, trustManager).hostnameVerifier { _, _ -> true }
}

/** TLS 핸드셰이크만 해서 리프 인증서 지문을 돌려준다. 실패(주소 오류 등)는 예외 */
fun fetchServerCertificateSha256(baseClient: OkHttpClient, url: String): String {
    var seen: X509Certificate? = null
    val recorder = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            seen = chain.firstOrNull()
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(recorder), SecureRandom()) }
    val probe = baseClient.newBuilder()
        .sslSocketFactory(context.socketFactory, recorder)
        .hostnameVerifier { _, _ -> true }
        .build()
    probe.newCall(Request.Builder().url(url).head().build()).execute().close()
    return seen?.sha256Hex() ?: throw SSLException("서버 인증서를 읽지 못했습니다")
}

fun X509Certificate.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }

/** 사람이 읽기 좋게 `ab:cd:…` */
fun String.toFingerprintDisplay(): String = chunked(2).joinToString(":").uppercase()

/**
 * 예외 사슬(cause·suppressed)에 TLS 오류가 있는지 — 계정 추가 화면이 "인증서 신뢰" 다이얼로그를 띄울지 결정.
 * OkHttp 는 여러 경로(IPv4/IPv6)를 시도한 뒤 마지막 예외에 앞선 실패를 suppressed 로 붙인다.
 */
fun Throwable.isTlsFailure(): Boolean {
    val seen = HashSet<Throwable>()
    val stack = ArrayDeque<Throwable>().apply { add(this@isTlsFailure) }
    while (stack.isNotEmpty()) {
        val t = stack.removeFirst()
        if (!seen.add(t)) continue
        if (t is SSLException) return true
        t.cause?.let(stack::add)
        t.suppressedExceptions.forEach(stack::add)
    }
    return false
}
