package com.jjw.easygallery.core.data.drive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 토큰은 Google 호스트에만 — 썸네일 주소가 서버에서 오니 다른 곳을 가리켜도 새지 않아야 한다 */
class GoogleHostTest {

    @Test
    fun `drive api and thumbnail hosts are google`() {
        assertTrue("www.googleapis.com".isGoogleHost())
        assertTrue("lh3.googleusercontent.com".isGoogleHost())
        assertTrue("drive-thirdparty.googleusercontent.com".isGoogleHost())
    }

    @Test
    fun `look-alike hosts are not google`() {
        assertFalse("googleapis.com.evil.example".isGoogleHost())
        assertFalse("evilgoogleusercontent.com".isGoogleHost())
        assertFalse("example.com".isGoogleHost())
    }
}
