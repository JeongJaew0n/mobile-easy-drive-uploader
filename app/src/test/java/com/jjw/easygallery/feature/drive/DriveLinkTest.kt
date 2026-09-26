package com.jjw.easygallery.feature.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 여러 계정이 로그인된 기기에서 파일을 열 때마다 계정을 고르라고 묻던 문제. */
@RunWith(RobolectricTestRunner::class)
class DriveLinkTest {

    private val link = "https://drive.google.com/file/d/abc123/view?usp=drivesdk"

    @Test
    fun `연결된 계정을 링크에 싣는다`() {
        val result = driveLinkForAccount(link, "me@gmail.com")
        assertTrue(result, result.contains("authuser=me%40gmail.com"))
        // 원래 있던 쿼리는 그대로 둔다
        assertTrue(result, result.contains("usp=drivesdk"))
    }

    @Test
    fun `계정을 모르면 손대지 않는다`() {
        assertEquals(link, driveLinkForAccount(link, null))
        assertEquals(link, driveLinkForAccount(link, ""))
    }

    @Test
    fun `이미 지정돼 있으면 그대로 둔다`() {
        val fixed = "$link&authuser=other@gmail.com"
        assertEquals(fixed, driveLinkForAccount(fixed, "me@gmail.com"))
    }

    @Test
    fun `쿼리가 없는 링크에도 붙는다`() {
        val plain = "https://drive.google.com/file/d/abc123/view"
        assertTrue(driveLinkForAccount(plain, "me@gmail.com").contains("authuser="))
    }
}
