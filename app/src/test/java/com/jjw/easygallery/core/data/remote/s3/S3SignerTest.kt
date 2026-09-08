package com.jjw.easygallery.core.data.remote.s3

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * AWS 문서 "Signature Calculations for the Authorization Header: Transferring Payload in a Single Chunk" 의
 * GET Object 예제. 키·날짜·기대 서명은 AWS 가 공개한 테스트 벡터.
 */
class S3SignerTest {

    private val signer = S3Signer(
        accessKey = "AKIAIOSFODNN7EXAMPLE",
        secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        region = "us-east-1",
    )

    @Test
    fun `get object example matches the AWS published signature`() {
        val request = Request.Builder()
            .url("https://examplebucket.s3.amazonaws.com/test.txt")
            .header("Range", "bytes=0-9")
            .get()
            .build()

        val signed = signer.sign(
            request,
            now = Instant.parse("2013-05-24T00:00:00Z"),
            payloadHash = S3Signer.EMPTY_PAYLOAD_SHA256,
        )

        val expected = "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
            "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
            "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
        assertEquals(expected, signed.header("Authorization"))
        assertEquals("20130524T000000Z", signed.header("x-amz-date"))
    }

    @Test
    fun `get bucket lifecycle example with query string`() {
        val request = Request.Builder()
            .url("https://examplebucket.s3.amazonaws.com/?lifecycle")
            .get()
            .build()

        val signed = signer.sign(
            request,
            now = Instant.parse("2013-05-24T00:00:00Z"),
            payloadHash = S3Signer.EMPTY_PAYLOAD_SHA256,
        )

        val expected = "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
            "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
            "Signature=fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543"
        assertEquals(expected, signed.header("Authorization"))
    }

    @Test
    fun `uri encoding follows the AWS rules`() {
        assertEquals(
            "photos/2026%2009/%ED%95%9C%EA%B8%80.jpg",
            S3Signer.uriEncode("photos/2026 09/한글.jpg", encodeSlash = false),
        )
        assertEquals("a%2Fb", S3Signer.uriEncode("a/b", encodeSlash = true))
        assertEquals("~-_.", S3Signer.uriEncode("~-_.", encodeSlash = true))
    }
}
