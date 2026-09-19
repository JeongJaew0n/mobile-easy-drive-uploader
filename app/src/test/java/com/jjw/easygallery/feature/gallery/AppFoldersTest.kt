package com.jjw.easygallery.feature.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 실기기 경로를 그대로 표본으로 쓴다(`docs/GALLERY_SOURCE_TABS.md`) */
class AppFoldersTest {

    @Test
    fun `표준 폴더 바로 아래가 앱 폴더다`() {
        assertEquals("KakaoTalk", AppFolders.folderOf("Pictures/KakaoTalk/"))
        assertEquals("KakaoTalk", AppFolders.folderOf("Movies/KakaoTalk/"))
        assertEquals("페이북", AppFolders.folderOf("Pictures/페이북/"))
        assertEquals("Camera", AppFolders.folderOf("DCIM/Camera/"))
    }

    @Test
    fun `더 깊어도 표준 폴더 바로 아래를 쓴다`() {
        // Documents/obsidian/pictures → 'pictures' 가 아니라 'obsidian'
        assertEquals("obsidian", AppFolders.folderOf("Documents/obsidian/pictures/"))
    }

    @Test
    fun `표준 폴더만 있으면 그 이름을 쓴다`() {
        assertEquals("Download", AppFolders.folderOf("Download/"))
        assertEquals("Pictures", AppFolders.folderOf("Pictures/"))
    }

    @Test
    fun `표준 폴더가 아니면 최상위를 그대로 쓴다`() {
        // 최상위에 직접 쓰는 앱. 두 번째 칸을 집으면 엉뚱한 하위 폴더 이름이 나온다
        assertEquals("SilentCamera", AppFolders.folderOf("SilentCamera/"))
        assertEquals("Binance", AppFolders.folderOf("Binance/Album/"))
    }

    @Test
    fun `경로가 없으면 null`() {
        assertNull(AppFolders.folderOf(""))
        assertNull(AppFolders.folderOf("//"))
    }

    @Test
    fun `대소문자가 달라도 표준 폴더로 본다`() {
        assertEquals("KakaoTalk", AppFolders.folderOf("pictures/KakaoTalk/"))
    }

    @Test
    fun `한국어 이름은 표에 있는 것만`() {
        assertEquals(AppFolders.displayNameRes("KakaoTalk"), AppFolders.displayNameRes("kakaotalk"))
        assertNull(AppFolders.displayNameRes("페이북"))
        assertNull(AppFolders.displayNameRes("SilentCamera"))
    }
}
