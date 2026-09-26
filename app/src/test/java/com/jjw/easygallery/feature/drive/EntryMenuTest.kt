package com.jjw.easygallery.feature.drive

import com.jjw.easygallery.core.domain.model.Capability
import com.jjw.easygallery.core.domain.model.DriveEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 보기 전용 폴더에서 바꾸는 동작이 새지 않는지. 여기서 한 줄이라도 새면 사용자는
 * 눌렀다가 403 을 본다 — 메뉴에 있으면 된다고 믿는다.
 */
class EntryMenuTest {

    private val file = DriveEntry("f1", "a.jpg", "image/jpeg", 1, 1, "https://drive/x")
    private val folder = DriveEntry("d1", "사진", DriveEntry.FOLDER_MIME_TYPE, null, null, null)
    private val all = setOf(
        Capability.RENAME,
        Capability.MOVE,
        Capability.TRASH,
        Capability.FOLDER_MUTATION,
        Capability.DOWNLOAD,
        Capability.WEB_LINK,
    )

    @Test
    fun `read-only folder hides every mutation`() {
        val menu = entryMenu(file, all, isReadOnly = true)
        assertFalse(menu.rename)
        assertFalse(menu.move)
        assertFalse(menu.delete)
    }

    @Test
    fun `read-only folder keeps download and open`() {
        val menu = entryMenu(file, all, isReadOnly = true)
        assertTrue(menu.download)
        assertTrue(menu.open)
        assertTrue(menu.hasAny)
    }

    @Test
    fun `view folder at root can be removed from the list`() {
        val menu = entryMenu(folder.copy(readOnly = true), all, isPickedRoot = true)
        assertTrue(menu.removeFromList)
        assertFalse(menu.delete)
    }

    @Test
    fun `picked folder at root has no remove action - it is an upload target`() {
        val menu = entryMenu(folder, all, isPickedRoot = true)
        assertFalse(menu.removeFromList)
        assertFalse(menu.hasAny)
    }

    @Test
    fun `an ordinary folder keeps its menu`() {
        val menu = entryMenu(folder, all)
        assertTrue(menu.rename)
        assertTrue(menu.move)
        assertTrue(menu.delete)
        assertTrue(menu.deleteIsTrash)
    }
}
