package com.jjw.easygallery.core.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 오류 본문이 사람이 읽을 것으로 바뀌는지. 여기가 새면 업로드 목록에 JSON 이 통째로 들어가
 * 두 줄에서 잘린다 — 2026-09-26 에 기기에서 1553건이 그랬다.
 *
 * `org.json` 은 안드로이드 구현이 필요해 Robolectric 으로 돈다.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteErrorBodyTest {

    private val quotaBody = """
        {
          "error": {
            "code": 403,
            "message": "The user's Drive storage quota has been exceeded.",
            "errors": [
              {
                "message": "The user's Drive storage quota has been exceeded.",
                "domain": "usageLimits",
                "reason": "storageQuotaExceeded"
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `pulls the machine reason out of a Drive error`() {
        assertEquals("storageQuotaExceeded", parseRemoteErrorBody(quotaBody).reason)
    }

    @Test
    fun `message is one line with no braces`() {
        val message = parseRemoteErrorBody(quotaBody).message.orEmpty()
        assertEquals("The user's Drive storage quota has been exceeded.", message)
        assertFalse(message.contains("{"))
        assertFalse(message.contains("\n"))
    }

    @Test
    fun `a non-JSON body keeps its first line`() {
        val parsed = parseRemoteErrorBody("Gateway timeout\n<html>...</html>")
        assertNull(parsed.reason)
        assertEquals("Gateway timeout", parsed.message)
    }

    @Test
    fun `an empty body gives nothing rather than blank noise`() {
        assertEquals(RemoteErrorBody(null, null), parseRemoteErrorBody("   "))
        assertEquals(RemoteErrorBody(null, null), parseRemoteErrorBody(null))
    }

    @Test
    fun `storage full is hopeless but not rate limiting`() {
        val e = RemoteStorageException("업로드 실패 (403)", httpCode = 403, reason = "storageQuotaExceeded")
        assertTrue(e.isHopeless)
        assertFalse(e.isRateLimited)
        assertFalse(e.isRetryable)
    }

    @Test
    fun `rate limiting is retryable and not hopeless`() {
        val e = RemoteStorageException("업로드 실패 (403)", httpCode = 403, reason = "userRateLimitExceeded")
        assertTrue(e.isRateLimited)
        assertTrue(e.isRetryable)
        assertFalse(e.isHopeless)
    }

    @Test
    fun `without a reason the old text matching still works`() {
        val e = RemoteStorageException("업로드 실패 (403): rateLimitExceeded", httpCode = 403)
        assertTrue(e.isRateLimited)
        assertFalse(e.isHopeless)
    }

    @Test
    fun `a plain 403 with no reason stays a permanent failure`() {
        val e = RemoteStorageException("업로드 실패 (403)", httpCode = 403)
        assertFalse(e.isRateLimited)
        assertFalse(e.isRetryable)
        assertFalse(e.isHopeless)
    }
}
