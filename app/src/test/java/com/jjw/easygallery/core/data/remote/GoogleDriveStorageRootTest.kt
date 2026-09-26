package com.jjw.easygallery.core.data.remote

import com.jjw.easygallery.core.data.drive.DriveRepository
import com.jjw.easygallery.core.data.prefs.UserPreferences
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.PickedFolder
import com.jjw.easygallery.core.domain.model.ViewFolder
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drive 루트 자리에 무엇이 어떤 순서로 놓이는가(`docs/DRIVE_FILE_SCOPE.md` §2·§10).
 * 순서와 [DriveEntry.readOnly] 표시가 화면의 메뉴·버튼을 모두 좌우한다.
 */
class GoogleDriveStorageRootTest {

    private val drive: DriveRepository = mockk {
        coEvery { ensureAppRootFolder() } returns DriveFolder("app", "Easy Gallery")
    }

    private fun storage(prefs: UserPreferences): GoogleDriveStorage {
        val repo: UserPreferencesRepository = mockk { coEvery { current() } returns prefs }
        return GoogleDriveStorage(drive, mockk(relaxed = true), repo)
    }

    @Test
    fun `upload targets come first, view-only folders last`() = runTest {
        val storage = storage(
            UserPreferences(
                pickedFolders = listOf(PickedFolder("p1", "여행 사진")),
                viewFolders = listOf(ViewFolder("v1", "2019 앨범")),
            ),
        )
        val names = storage.listChildren("root").entries.map { it.name }
        assertEquals(listOf("여행 사진", "Easy Gallery", "2019 앨범"), names)
    }

    @Test
    fun `only view folders are marked read-only`() = runTest {
        val storage = storage(
            UserPreferences(
                pickedFolders = listOf(PickedFolder("p1", "여행 사진")),
                viewFolders = listOf(ViewFolder("v1", "2019 앨범")),
            ),
        )
        val entries = storage.listChildren("root").entries.associateBy { it.name }
        assertTrue(entries.getValue("2019 앨범").readOnly)
        assertFalse(entries.getValue("여행 사진").readOnly)
        assertFalse(entries.getValue("Easy Gallery").readOnly)
    }

    @Test
    fun `the app folder still shows when nothing was added`() = runTest {
        val names = storage(UserPreferences()).listChildren("root").entries.map { it.name }
        assertEquals(listOf("Easy Gallery"), names)
    }

    @Test
    fun `a real folder is not the root list`() = runTest {
        coEvery { drive.listChildren("v1", null, false) } returns
            com.jjw.easygallery.core.domain.model.DrivePage(emptyList(), null)
        val page = storage(UserPreferences()).listChildren("v1")
        assertTrue(page.entries.isEmpty())
    }
}
