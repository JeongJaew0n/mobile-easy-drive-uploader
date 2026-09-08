package com.jjw.easygallery.core.data.remote.s3

import okhttp3.HttpUrl
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 (`docs/MULTI_CLOUD.md` §4). AWS SDK 대신 직접 구현 — 필요한 건 서명 하나뿐이고
 * SDK 는 APK 크기·의존성이 크다. 본문은 `UNSIGNED-PAYLOAD` 로 서명해 스트리밍 PUT 을 허용한다.
 * 검증: AWS 문서의 공개 테스트 벡터(`S3SignerTest`).
 */
class S3Signer(
    private val accessKey: String,
    private val secretKey: String,
    private val region: String,
    private val service: String = "s3",
) {
    /** [request] 에 `x-amz-date`, `x-amz-content-sha256`, `Authorization` 을 붙인 새 요청 */
    fun sign(request: Request, now: Instant = Instant.now(), payloadHash: String = UNSIGNED_PAYLOAD): Request {
        val amzDate = AMZ_DATE.format(now.atOffset(ZoneOffset.UTC))
        val dateStamp = amzDate.substring(0, DATE_STAMP_LENGTH)
        val headers = request.newBuilder()
            .header("Host", request.url.hostHeader())
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
            .build()
        val signedHeaderNames = headers.headers.names().map { it.lowercase() }.sorted()
        val canonicalHeaders = signedHeaderNames.joinToString("") { name ->
            "$name:${headers.headers.values(name).joinToString(",") { it.trim().replace(Regex("\\s+"), " ") }}\n"
        }
        val signedHeaders = signedHeaderNames.joinToString(";")
        val canonicalRequest = listOf(
            request.method,
            canonicalUri(request.url),
            canonicalQuery(request.url),
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")
        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf(ALGORITHM, amzDate, scope, sha256Hex(canonicalRequest.toByteArray()))
            .joinToString("\n")
        val signature = hex(hmac(signingKey(dateStamp), stringToSign.toByteArray()))
        val authorization =
            "$ALGORITHM Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
        return headers.newBuilder().header("Authorization", authorization).build()
    }

    private fun signingKey(dateStamp: String): ByteArray {
        val kDate = hmac("AWS4$secretKey".toByteArray(), dateStamp.toByteArray())
        val kRegion = hmac(kDate, region.toByteArray())
        val kService = hmac(kRegion, service.toByteArray())
        return hmac(kService, "aws4_request".toByteArray())
    }

    companion object {
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
        const val EMPTY_PAYLOAD_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        private const val DATE_STAMP_LENGTH = 8
        private val AMZ_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        /** 경로 세그먼트를 RFC 3986 으로 인코딩(이미 인코딩된 URL 을 한 번 디코딩한 뒤). `/` 는 유지 */
        fun canonicalUri(url: HttpUrl): String =
            url.pathSegments.joinToString("/", prefix = "/") { uriEncode(it, encodeSlash = true) }

        fun canonicalQuery(url: HttpUrl): String =
            url.queryParameterNames
                .flatMap { name -> url.queryParameterValues(name).map { name to (it ?: "") } }
                .map { (n, v) -> uriEncode(n, true) to uriEncode(v, true) }
                .sortedWith(compareBy({ it.first }, { it.second }))
                .joinToString("&") { (n, v) -> "$n=$v" }

        /** AWS 규격: 영숫자와 `-_.~` 만 남기고 나머지는 %XX(대문자). 공백은 `%20` */
        fun uriEncode(value: String, encodeSlash: Boolean): String {
            val encoded = URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~")
            return if (encodeSlash) encoded else encoded.replace("%2F", "/")
        }

        fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        private fun HttpUrl.hostHeader(): String =
            if (port == HttpUrl.defaultPort(scheme)) host else "$host:$port"
    }
}
