package com.jjw.easygallery.core.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** 칸을 바꿀 때 백스택(`docs/plans/bottom-navigation/spec.md`) — 다른 칸에서 뒤로 가면 사진, 사진에서 뒤로 가면 앱 밖 */
class TopLevelDestinationTest {

    @Test
    fun `다른 칸으로 가면 사진 위에 그 칸 하나`() {
        val stack = mutableListOf<NavKey>(GalleryKey)
        stack.selectTopLevel(TopLevelDestination.BACKUP)
        assertEquals(listOf(GalleryKey, BackupKey), stack)
    }

    @Test
    fun `칸 사이를 옮겨도 쌓이지 않는다`() {
        val stack = mutableListOf<NavKey>(GalleryKey, BackupKey)
        stack.selectTopLevel(TopLevelDestination.MENU)
        assertEquals(listOf(GalleryKey, MenuKey), stack)
    }

    @Test
    fun `사진으로 돌아오면 하위 화면을 걷어 낸다`() {
        val stack = mutableListOf<NavKey>(GalleryKey, AlbumsKey, AlbumKey(relativePath = "DCIM/행복이/"))
        stack.selectTopLevel(TopLevelDestination.PHOTOS)
        assertEquals(listOf<NavKey>(GalleryKey), stack)
    }

    @Test
    fun `같은 칸을 다시 누르면 그대로`() {
        val stack = mutableListOf<NavKey>(GalleryKey, AlbumsKey)
        stack.selectTopLevel(TopLevelDestination.ALBUMS)
        assertEquals(listOf(GalleryKey, AlbumsKey), stack)
    }

    @Test
    fun `맨 아래가 사진이 아니면 사진부터 다시 놓는다`() {
        val stack = mutableListOf<NavKey>(SettingsKey)
        stack.selectTopLevel(TopLevelDestination.ALBUMS)
        assertEquals(listOf(GalleryKey, AlbumsKey), stack)
    }
}
