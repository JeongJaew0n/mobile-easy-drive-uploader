package com.jjw.easygallery.feature.drive

import app.cash.turbine.test
import com.jjw.easygallery.core.data.download.DownloadScheduler
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.remote.RemoteStorage
import com.jjw.easygallery.core.data.remote.StorageRegistry
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.domain.model.Capability
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.DrivePage
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DriveBrowserViewModelTest {

    private val folderA = entry("d1", "Album", DriveEntry.FOLDER_MIME_TYPE)
    private val fileA = entry("f1", "a.jpg", "image/jpeg")
    private val fileB = entry("f2", "b.jpg", "image/jpeg")
    private val drive: RemoteStorage = mockk {
        coEvery { listChildren("root", null, false) } returns DrivePage(listOf(folderA, fileA, fileB), null)
        every { rootId } returns "root"
        every { capabilities } returns setOf(Capability.TRASH, Capability.RENAME, Capability.MOVE)
        every { account } returns
            RemoteAccount(RemoteAccount.GOOGLE_DRIVE_ID, RemoteAccountKind.GOOGLE_DRIVE, "Google Drive")
    }
    private val storages: StorageRegistry = mockk { coEvery { storage(null) } returns drive }
    private val prefs: UserPreferencesRepository = mockk()
    private val downloads: DownloadScheduler = mockk(relaxed = true)
    private val ledger: UploadLedgerRepository = mockk { every { observeRemoteIds(null) } returns flowOf(setOf("f2")) }
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun loadedViewModel(): DriveBrowserViewModel {
        val viewModel = DriveBrowserViewModel(storages, prefs, downloads, ledger)
        viewModel.load(accountId = null, folderId = "root", folderName = "내 드라이브", rootName = "내 드라이브")
        testDispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `rename updates the list optimistically and keeps sort order`() = runTest(testDispatcher) {
        coEvery { drive.rename("f2", "0.jpg") } returns fileB.copy(name = "0.jpg")
        val viewModel = loadedViewModel()

        viewModel.rename(fileB, "0.jpg")
        assertEquals(listOf("Album", "0.jpg", "a.jpg"), viewModel.uiState.value.entries.map { it.name })
        assertTrue(viewModel.uiState.value.isMutating)

        advanceUntilIdle()
        assertTrue(!viewModel.uiState.value.isMutating)
        viewModel.eventFlow.test { assertEquals(DriveBrowserEvent.Renamed("0.jpg"), awaitItem()) }
    }

    @Test
    fun `failed mutation restores the previous list and reports an error`() = runTest(testDispatcher) {
        coEvery { drive.delete("f1") } throws IOException("offline")
        val viewModel = loadedViewModel()

        viewModel.trash(fileA)
        assertEquals(listOf("Album", "b.jpg"), viewModel.uiState.value.entries.map { it.name })
        advanceUntilIdle()

        assertEquals(listOf("Album", "a.jpg", "b.jpg"), viewModel.uiState.value.entries.map { it.name })
        viewModel.eventFlow.test { assertEquals(DriveBrowserEvent.Error("offline"), awaitItem()) }
    }

    @Test
    fun `trash then restore re-inserts the entry in sorted position`() = runTest(testDispatcher) {
        coEvery { drive.delete("f1") } returns Unit
        coEvery { drive.restore("f1") } returns Unit
        val viewModel = loadedViewModel()

        viewModel.trash(fileA)
        advanceUntilIdle()
        assertEquals(listOf("Album", "b.jpg"), viewModel.uiState.value.entries.map { it.name })

        viewModel.restore(fileA)
        advanceUntilIdle()
        assertEquals(listOf("Album", "a.jpg", "b.jpg"), viewModel.uiState.value.entries.map { it.name })
    }

    @Test
    fun `batch trash removes selected entries, keeps the failed one and reports both`() = runTest(testDispatcher) {
        coEvery { drive.delete("f1") } returns Unit
        coEvery { drive.delete("f2") } throws IOException("offline")
        val viewModel = loadedViewModel()

        viewModel.toggleSelection(fileA)
        viewModel.toggleSelection(fileB)
        assertTrue(viewModel.uiState.value.isSelecting)
        viewModel.trashSelected()
        assertEquals(listOf("Album"), viewModel.uiState.value.entries.map { it.name }) // 낙관적 제거
        assertTrue(!viewModel.uiState.value.isSelecting)
        advanceUntilIdle()

        assertEquals(listOf("Album", "b.jpg"), viewModel.uiState.value.entries.map { it.name })
        assertTrue(!viewModel.uiState.value.isMutating)
        viewModel.eventFlow.test {
            assertEquals(DriveBrowserEvent.BatchTrashed(listOf(fileA), isTrash = true), awaitItem())
            assertEquals(DriveBrowserEvent.BatchFailed(1), awaitItem())
        }
    }

    @Test
    fun `batch move rejects a selected folder as its own target and select all covers every entry`() =
        runTest(testDispatcher) {
            val viewModel = loadedViewModel()
            viewModel.selectAll()
            assertEquals(3, viewModel.uiState.value.selectedIds.size)

            viewModel.moveSelected(DriveFolder("d1", "Album")) // Album 이 선택돼 있음 → 거부
            assertEquals(3, viewModel.uiState.value.entries.size)
            viewModel.eventFlow.test { assertTrue(awaitItem() is DriveBrowserEvent.Error) }

            viewModel.clearSelection()
            assertTrue(!viewModel.uiState.value.isSelecting)
        }

    @Test
    fun `search debounces, lists results across the drive and exit restores the folder`() = runTest(testDispatcher) {
        coEvery { drive.search("a", null) } returns DrivePage(listOf(fileA), null)
        every { drive.capabilities } returns setOf(Capability.SEARCH, Capability.TRASH, Capability.MOVE)
        val viewModel = loadedViewModel()

        viewModel.startSearch()
        assertTrue(viewModel.uiState.value.isSearching)
        viewModel.search("a")
        assertEquals(3, viewModel.uiState.value.entries.size) // 디바운스 전
        advanceUntilIdle()
        assertEquals(listOf("a.jpg"), viewModel.uiState.value.entries.map { it.name })

        viewModel.search("")
        assertTrue(viewModel.uiState.value.entries.isEmpty())

        viewModel.exitSearch()
        advanceUntilIdle()
        assertTrue(!viewModel.uiState.value.isSearching)
        assertEquals(listOf("Album", "a.jpg", "b.jpg"), viewModel.uiState.value.entries.map { it.name })
    }

    @Test
    fun `storages without SEARCH filter the loaded folder locally and keep it on exit`() = runTest(testDispatcher) {
        every { drive.capabilities } returns setOf(Capability.RENAME, Capability.MOVE)
        val viewModel = loadedViewModel()
        assertEquals(setOf("f2"), viewModel.uiState.value.uploadedFromDeviceIds)

        viewModel.startSearch()
        viewModel.search("B.")
        assertEquals(listOf("b.jpg"), viewModel.uiState.value.entries.map { it.name }) // 즉시, 대소문자 무시
        assertTrue(!viewModel.uiState.value.isRemoteSearchResult)
        coVerify(exactly = 0) { drive.search(any(), any()) }

        viewModel.exitSearch()
        assertEquals(listOf("Album", "a.jpg", "b.jpg"), viewModel.uiState.value.entries.map { it.name })
        coVerify(exactly = 1) { drive.listChildren("root", null, false) } // 다시 읽지 않음
    }

    @Test
    fun `a refresh during a batch is ignored so failed items are not duplicated`() = runTest(testDispatcher) {
        coEvery { drive.delete("f1") } returns Unit
        coEvery { drive.delete("f2") } throws IOException("offline")
        val viewModel = loadedViewModel()
        viewModel.toggleSelection(fileA)
        viewModel.toggleSelection(fileB)

        viewModel.trashSelected()
        viewModel.refresh() // 배치가 도는 동안 당겨서 새로고침
        advanceUntilIdle()

        val names = viewModel.uiState.value.entries.map { it.name }
        assertEquals(listOf("Album", "b.jpg"), names) // 실패한 것만 되살아나고 중복 없음
        assertEquals(names.distinct(), names)
    }

    @Test
    fun `moving a folder into its own subtree is rejected for path based storages`() = runTest(testDispatcher) {
        val folder = entry("photos/", "photos", DriveEntry.FOLDER_MIME_TYPE)
        coEvery { drive.listChildren("root", null, false) } returns DrivePage(listOf(folder), null)
        val viewModel = loadedViewModel()
        viewModel.toggleSelection(folder)

        viewModel.moveSelected(DriveFolder("photos/2026/", "2026"))
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.entries.size)
        coVerify(exactly = 0) { drive.move(any(), any(), any()) }
        viewModel.eventFlow.test { assertTrue(awaitItem() is DriveBrowserEvent.Error) }
    }

    @Test
    fun `deleting while the local filter is on refetches on exit instead of resurrecting`() = runTest(testDispatcher) {
        every { drive.capabilities } returns setOf(Capability.RENAME, Capability.MOVE)
        coEvery { drive.delete("f1") } returns Unit
        val viewModel = loadedViewModel()

        viewModel.startSearch()
        viewModel.search("a.jpg")
        viewModel.trash(fileA)
        advanceUntilIdle()
        viewModel.exitSearch()
        advanceUntilIdle()

        // 보관본을 그대로 되돌리지 않고 서버에서 다시 읽는다
        coVerify(exactly = 2) { drive.listChildren("root", null, false) }
    }

    @Test
    fun `download selected enqueues files only and clears the selection`() = runTest(testDispatcher) {
        val viewModel = loadedViewModel()
        viewModel.selectAll()

        viewModel.downloadSelected()
        advanceUntilIdle()

        verify(exactly = 1) { downloads.enqueue(null, fileA) }
        verify(exactly = 1) { downloads.enqueue(null, fileB) }
        verify(exactly = 0) { downloads.enqueue(null, folderA) }
        assertTrue(!viewModel.uiState.value.isSelecting)
        viewModel.eventFlow.test { assertEquals(DriveBrowserEvent.DownloadStarted(2), awaitItem()) }
    }

    @Test
    fun `move removes the entry and ignores moving into the current folder`() = runTest(testDispatcher) {
        coEvery { drive.move("f1", "root", "d1") } returns fileA
        val viewModel = loadedViewModel()

        viewModel.move(fileA, DriveFolder("root", "내 드라이브")) // 같은 폴더 → 무시
        assertEquals(3, viewModel.uiState.value.entries.size)

        viewModel.move(fileA, DriveFolder("d1", "Album"))
        advanceUntilIdle()
        assertEquals(listOf("Album", "b.jpg"), viewModel.uiState.value.entries.map { it.name })
    }

    @Test
    fun `listFolders follows pagination and returns folders only`() = runTest(testDispatcher) {
        coEvery { drive.listChildren("root", null, true) } returns DrivePage(listOf(folderA), "tok")
        coEvery { drive.listChildren("root", "tok", true) } returns
            DrivePage(listOf(entry("d2", "Zoo", DriveEntry.FOLDER_MIME_TYPE)), null)
        val viewModel = loadedViewModel()

        val folders = viewModel.listFolders("root")

        assertEquals(listOf("Album", "Zoo"), folders.map { it.name })
    }

    private fun entry(id: String, name: String, mime: String) = DriveEntry(id, name, mime, null, null, null)
}
