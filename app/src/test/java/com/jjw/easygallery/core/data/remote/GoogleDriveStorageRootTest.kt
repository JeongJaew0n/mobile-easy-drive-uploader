package com.jjw.easygallery.core.data.remote

import com.jjw.easygallery.core.data.drive.DriveRepository
import com.jjw.easygallery.core.data.prefs.UserPreferences
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.PickedFolder
import com.jjw.easygallery.core.domain.model.ViewFolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private val repo: UserPreferencesRepository = mockk(relaxed = true)

    private fun storage(prefs: UserPreferences): GoogleDriveStorage {
        coEvery { repo.current() } returns prefs
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
    fun `a folder that is both an upload target and a view folder appears once`() = runTest {
        // 같은 id 가 두 번 들어가면 LazyColumn 이 죽는다(2026-09-26 기기에서 겪음)
        val storage = storage(
            UserPreferences(
                pickedFolders = listOf(PickedFolder("p1", "여행 사진")),
                viewFolders = listOf(ViewFolder("p1", "여행 사진"), ViewFolder("app", "Easy Gallery")),
            ),
        )
        val ids = storage.listChildren("root").entries.map { it.id }
        assertEquals(listOf("p1", "app"), ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the upload target wins when a folder is in both lists`() = runTest {
        val storage = storage(
            UserPreferences(
                pickedFolders = listOf(PickedFolder("p1", "여행 사진")),
                viewFolders = listOf(ViewFolder("p1", "여행 사진")),
            ),
        )
        assertFalse(storage.listChildren("root").entries.first { it.id == "p1" }.readOnly)
    }

    @Test
    fun `the app folder still shows when nothing was added`() = runTest {
        val names = storage(UserPreferences()).listChildren("root").entries.map { it.name }
        assertEquals(listOf("Easy Gallery"), names)
    }

    // ---- 누구의 폴더인가(docs/plans/guest-account-upload/spec.md §6) ----

    @Test
    fun `the app folder belongs to the connected account`() = runTest {
        val root = storage(UserPreferences(accountEmail = "a@x.com")).listChildren("root").entries
        assertEquals("a@x.com", root.single().ownerEmail)
    }

    @Test
    fun `a stored owner is shown without asking Drive`() = runTest {
        val storage = storage(
            UserPreferences(viewFolders = listOf(ViewFolder("v1", "Easy Gallery", ownerEmail = "b@x.com"))),
        )
        val entry = storage.listChildren("root").entries.first { it.id == "v1" }
        assertEquals("b@x.com", entry.ownerEmail)
        coVerify(exactly = 0) { drive.ownerOf(any()) }
    }

    @Test
    fun `an old view folder gets its owner once and loses the email tail`() = runTest {
        coEvery { drive.ownerOf("v1") } returns "b@x.com"
        val storage = storage(
            UserPreferences(
                driveViewScopeGranted = true,
                viewFolders = listOf(ViewFolder("v1", "Easy Gallery · b@x.com")),
            ),
        )

        val entry = storage.listChildren("root").entries.first { it.id == "v1" }

        // 소유자를 따로 보이니 이름 뒤의 이메일은 겹친다
        assertEquals("Easy Gallery", entry.name)
        assertEquals("b@x.com", entry.ownerEmail)
        coVerify { repo.setViewFolderOwner("v1", "b@x.com") }
    }

    @Test
    fun `without read access the owner stays unknown`() = runTest {
        val storage = storage(
            UserPreferences(driveViewScopeGranted = false, viewFolders = listOf(ViewFolder("v1", "사진"))),
        )
        val entry = storage.listChildren("root").entries.first { it.id == "v1" }
        assertNull(entry.ownerEmail)
        coVerify(exactly = 0) { drive.ownerOf(any()) }
    }

    @Test
    fun `a real folder is not the root list`() = runTest {
        coEvery { drive.listChildren("v1", null, false) } returns
            com.jjw.easygallery.core.domain.model.DrivePage(emptyList(), null)
        val page = storage(UserPreferences()).listChildren("v1")
        assertTrue(page.entries.isEmpty())
    }
}
