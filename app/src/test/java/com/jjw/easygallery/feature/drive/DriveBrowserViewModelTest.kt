package com.jjw.easygallery.feature.drive

import app.cash.turbine.test
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.remote.RemoteStorage
import com.jjw.easygallery.core.data.remote.StorageRegistry
import com.jjw.easygallery.core.domain.model.Capability
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.DrivePage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    }
    private val storages: StorageRegistry = mockk { coEvery { storage(null) } returns drive }
    private val prefs: UserPreferencesRepository = mockk()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun loadedViewModel(): DriveBrowserViewModel {
        val viewModel = DriveBrowserViewModel(storages, prefs)
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
