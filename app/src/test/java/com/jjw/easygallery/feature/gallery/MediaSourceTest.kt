package com.jjw.easygallery.feature.gallery

import android.net.Uri
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 경로 분류 규칙(`docs/plans/gallery-source-tabs/spec.md`). 실기기 경로를 그대로 표본으로 쓴다 */
class MediaSourceTest {

    @Test
    fun `DCIM 아래는 카메라다`() {
        assertEquals(MediaSource.CAMERA, MediaSource.of(item("DCIM/Camera/")))
        // 사용자가 직접 만든 앨범도 카메라 탭이다
        assertEquals(MediaSource.CAMERA, MediaSource.of(item("DCIM/중국 연태 2025 02 14 ~ 02 16/")))
        assertEquals(MediaSource.CAMERA, MediaSource.of(item("DCIM/CandyCam/")))
        assertEquals(MediaSource.CAMERA, MediaSource.of(item("DCIM/")))
    }

    @Test
    fun `스크린샷은 DCIM 아래여도 따로 나간다`() {
        assertEquals(MediaSource.SCREENSHOT, MediaSource.of(item("DCIM/Screenshots/")))
        assertEquals(MediaSource.SCREENSHOT, MediaSource.of(item("Pictures/Screenshots/")))
    }

    @Test
    fun `DCIM 밖은 다른 앱이다`() {
        assertEquals(MediaSource.OTHER, MediaSource.of(item("Pictures/KakaoTalk/")))
        assertEquals(MediaSource.OTHER, MediaSource.of(item("Download/")))
        assertEquals(MediaSource.OTHER, MediaSource.of(item("Documents/obsidian/pictures/")))
        // 최상위에 쓰는 카메라류 앱 — 아쉽지만 규칙대로 '다른 앱'
        assertEquals(MediaSource.OTHER, MediaSource.of(item("SilentCamera/")))
    }

    @Test
    fun `경로가 없는 레거시 행은 다른 앱이다`() {
        assertEquals(MediaSource.OTHER, MediaSource.of(item("")))
    }

    @Test
    fun `대소문자가 달라도 같게 본다`() {
        assertEquals(MediaSource.CAMERA, MediaSource.of(item("dcim/camera/")))
        assertEquals(MediaSource.SCREENSHOT, MediaSource.of(item("dcim/screenshots/")))
    }

    @Test
    fun `폴더 이름에 DCIM 이 들어가도 최상위가 아니면 카메라가 아니다`() {
        assertEquals(MediaSource.OTHER, MediaSource.of(item("Pictures/DCIM backup/")))
    }

    @Test
    fun `전체 탭은 모두 통과시킨다`() {
        assertTrue(GalleryTab.ALL.matches(item("Pictures/KakaoTalk/")))
        assertTrue(GalleryTab.ALL.matches(item("DCIM/Camera/")))
    }

    @Test
    fun `탭은 제 출처만 통과시킨다`() {
        assertTrue(GalleryTab.CAMERA.matches(item("DCIM/Camera/")))
        assertFalse(GalleryTab.CAMERA.matches(item("DCIM/Screenshots/")))
        assertFalse(GalleryTab.CAMERA.matches(item("Pictures/KakaoTalk/")))
        assertTrue(GalleryTab.OTHER.matches(item("Pictures/KakaoTalk/")))
    }

    private fun item(relativePath: String) = MediaItem(
        id = 1,
        uri = mockk<Uri>(),
        displayName = "a.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 1,
        dateTakenMillis = 0,
        bucketId = 1,
        bucketName = "",
        relativePath = relativePath,
    )
}
