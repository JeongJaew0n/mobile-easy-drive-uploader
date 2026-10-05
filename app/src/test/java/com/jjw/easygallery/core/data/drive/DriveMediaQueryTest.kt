package com.jjw.easygallery.core.data.drive

import com.jjw.easygallery.core.domain.model.DriveMediaScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class DriveMediaQueryTest {

    @Test
    fun `앱이 올린 것 - drive file 만 있으면 표식 없이 내 것만`() {
        val q = DriveMediaQuery.of(DriveMediaScope.AppUploads, videosOnly = false, viewScopeGranted = false)
        assertEquals(
            "(mimeType contains 'image/' or mimeType contains 'video/') and trashed = false and 'me' in owners",
            q,
        )
    }

    @Test
    fun `앱이 올린 것 - 읽기 권한이 있으면 업로드 표식으로 좁힌다`() {
        val q = DriveMediaQuery.of(DriveMediaScope.AppUploads, videosOnly = false, viewScopeGranted = true)
        assertTrue(q.endsWith("and appProperties has { key = 'easyGallery' and value = '1' }"))
        assertTrue("'me' in owners" in q)
    }

    @Test
    fun `폴더 하나 - 그 폴더 바로 아래, 따옴표는 이스케이프`() {
        val q = DriveMediaQuery.of(
            DriveMediaScope.Folder(id = "it's", name = "x", readOnly = false),
            videosOnly = false,
            viewScopeGranted = true,
        )
        assertTrue(q.startsWith("'it\\'s' in parents and "))
        assertFalse("appProperties" in q)
    }

    @Test
    fun `영상만 - 사진 조건이 빠진다`() {
        val q = DriveMediaQuery.of(DriveMediaScope.WholeDrive, videosOnly = true, viewScopeGranted = true)
        assertEquals("mimeType contains 'video/' and trashed = false", q)
    }

    @Test
    fun `EXIF 시각은 주어진 시간대의 현지 시각으로 읽는다`() {
        val millis = parseExifTime("2026:09:12 18:15:32", ZoneOffset.ofHours(9))
        assertEquals(LocalDateTime.of(2026, 9, 12, 18, 15, 32).toInstant(ZoneOffset.ofHours(9)).toEpochMilli(), millis)
    }

    @Test
    fun `EXIF 시각이 비었거나 형식이 다르면 null`() {
        assertEquals(null, parseExifTime("0000:00:00 00:00:00", ZoneOffset.UTC))
        assertEquals(null, parseExifTime("2026-09-12T18:15:32Z", ZoneOffset.UTC))
    }
}
