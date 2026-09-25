package com.jjw.easygallery.core.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 한도 초과를 영구 실패로 버리면 사용자는 "일부만 올라갔다" 를 겪는다.
 * 병렬 업로드에서 실제로 났던 오류라 분류를 테스트로 고정한다.
 */
class RemoteStorageExceptionTest {

    @Test
    fun `403 에 한도 초과 문구가 있으면 재시도 대상`() {
        val e = RemoteStorageException("업로드 실패 (403): {\"error\":{\"message\":\"User rate limit exceeded.\"}}", 403)
        assertTrue(e.isRateLimited)
    }

    @Test
    fun `403 reason 이 userRateLimitExceeded 여도 잡는다`() {
        val e = RemoteStorageException("업로드 실패 (403): {\"reason\":\"userRateLimitExceeded\"}", 403)
        assertTrue(e.isRateLimited)
    }

    @Test
    fun `429 는 문구와 무관하게 재시도 대상`() {
        assertTrue(RemoteStorageException("too many", 429).isRateLimited)
    }

    @Test
    fun `권한 없음 403 은 재시도해도 소용없다`() {
        val e = RemoteStorageException("업로드 실패 (403): {\"reason\":\"insufficientFilePermissions\"}", 403)
        assertFalse(e.isRateLimited)
    }

    @Test
    fun `404 는 한도와 무관`() {
        assertFalse(RemoteStorageException("없음", 404).isRateLimited)
    }

    @Test
    fun `401 은 토큰 만료라 다시 하면 된다`() {
        // 대량 업로드가 토큰 수명을 넘기면 실제로 만난다. 버리면 그 파일만 조용히 빠진다
        assertTrue(RemoteStorageException("업로드 실패 (401): invalid authentication credentials", 401).isRetryable)
    }

    @Test
    fun `404 는 다시 해도 소용없다`() {
        assertFalse(RemoteStorageException("없음", 404).isRetryable)
    }

    @Test
    fun `한도 초과도 재시도 대상에 포함된다`() {
        assertTrue(RemoteStorageException("업로드 실패 (429)", 429).isRetryable)
    }
}
