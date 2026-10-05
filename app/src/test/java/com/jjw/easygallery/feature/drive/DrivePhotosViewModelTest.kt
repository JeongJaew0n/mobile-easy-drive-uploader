package com.jjw.easygallery.feature.drive

import android.net.Uri
import coil3.ImageLoader
import com.jjw.easygallery.core.data.auth.AuthRepository
import com.jjw.easygallery.core.data.download.DownloadScheduler
import com.jjw.easygallery.core.data.drive.DriveRepository
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.prefs.UserPreferences
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.remote.RemoteStorage
import com.jjw.easygallery.core.data.remote.StorageRegistry
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveMediaOrder
import com.jjw.easygallery.core.domain.model.DriveMediaScope
import com.jjw.easygallery.core.domain.model.DrivePage
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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

@OptIn(ExperimentalCoroutinesApi::class)
class DrivePhotosViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val uri = mockk<Uri>(relaxed = true)
    private val page1 = (1..3).map { entry("p$it") }
    private val page2 = (4..5).map { entry("p$it") }
    private val drive: DriveRepository = mockk(relaxed = true) {
        coEvery { listMedia(DriveMediaScope.AppUploads, false, false, null, any()) } returns DrivePage(page1, "t2")
        coEvery { listMedia(DriveMediaScope.AppUploads, false, false, "t2", any()) } returns DrivePage(page2, null)
    }
    private val root: RemoteStorage = mockk {
        every { rootId } returns "root"
        coEvery { listChildren("root", null, false) } returns DrivePage(
            listOf(entry("folder", mime = DriveEntry.FOLDER_MIME_TYPE).copy(name = "Easy Gallery")),
            null,
        )
    }
    private val storages: StorageRegistry = mockk { coEvery { storage(null) } returns root }
    private val prefs: UserPreferencesRepository = mockk {
        coEvery { current() } returns UserPreferences(accountEmail = "a@example.com")
    }
    private val downloads: DownloadScheduler = mockk(relaxed = true)

    // p1·p2 는 이 기기에서 올렸고 기기에도 있다. p3 은 올렸지만 기기에서 지웠다
    private val ledger: UploadLedgerRepository = mockk {
        every { observeRemoteToMedia(null) } returns flowOf(mapOf("p1" to 1L, "p2" to 2L, "p3" to 3L))
    }
    private val media: MediaRepository = mockk {
        every { observeMedia(any()) } returns flowOf(listOf(item(1), item(2)))
    }

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun createViewModel() = DrivePhotosViewModel(
        drive = drive,
        storages = storages,
        prefs = prefs,
        downloads = downloads,
        auth = mockk<AuthRepository>(relaxed = true),
        ledger = ledger,
        mediaRepository = media,
        driveImageLoader = mockk<ImageLoader>(),
        driveDataSourceFactory = mockk(),
    )

    private fun DrivePhotosViewModel.content() = uiState.value as DrivePhotosUiState.Content

    @Test
    fun `첫 쪽을 읽고 폴더 칩과 기기에 있음을 싣는다`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.load()
        advanceUntilIdle()
        val state = vm.content()
        assertEquals(page1, state.entries)
        assertEquals("t2", state.nextPageToken)
        assertEquals(setOf("p1", "p2"), state.onDeviceIds)
        assertEquals(listOf(DriveMediaScope.Folder("folder", "Easy Gallery", readOnly = false)), state.folders)
    }

    @Test
    fun `계정이 없으면 안내만`() = runTest(testDispatcher) {
        coEvery { prefs.current() } returns UserPreferences()
        val vm = createViewModel()
        vm.load()
        advanceUntilIdle()
        assertEquals(DrivePhotosUiState.NotConnected, vm.uiState.value)
    }

    @Test
    fun `기기에 없음 - 걸러서 적으면 다음 쪽을 알아서 더 읽는다`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.load()
        advanceUntilIdle()
        vm.setFilter(DrivePhotoFilter.NOT_ON_DEVICE)
        advanceUntilIdle()
        val state = vm.content()
        assertEquals(null, state.nextPageToken)
        assertEquals(listOf("p3", "p4", "p5"), state.visible.map { it.id })
    }

    @Test
    fun `영상은 서버 조건이 바뀌어 다시 읽는다`() = runTest(testDispatcher) {
        coEvery { drive.listMedia(DriveMediaScope.AppUploads, true, false, null, any()) } returns
            DrivePage(listOf(entry("v1", mime = "video/mp4")), null)
        val vm = createViewModel()
        vm.load()
        advanceUntilIdle()
        vm.setFilter(DrivePhotoFilter.VIDEOS)
        advanceUntilIdle()
        assertEquals(listOf("v1"), vm.content().visible.map { it.id })
    }

    @Test
    fun `찍은 날짜순은 끝까지 읽는다`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.load()
        advanceUntilIdle()
        vm.setOrder(DriveMediaOrder.TAKEN)
        advanceUntilIdle()
        val state = vm.content()
        assertEquals(5, state.entries.size)
        assertEquals(null, state.nextPageToken)
        coVerify { drive.listMedia(DriveMediaScope.AppUploads, false, false, "t2", 1_000) }
    }

    @Test
    fun `휴지통으로 보내면 빠지고 되돌리면 제자리로`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.load()
        advanceUntilIdle()
        vm.trash(listOf(page1[1]))
        advanceUntilIdle()
        assertEquals(listOf("p1", "p3"), vm.content().entries.map { it.id })
        vm.restore(listOf(page1[1]))
        advanceUntilIdle()
        assertEquals(listOf("p1", "p2", "p3"), vm.content().entries.map { it.id })
        coVerify { drive.setTrashed("p2", trashed = true) }
        coVerify { drive.setTrashed("p2", trashed = false) }
    }

    @Test
    fun `보기 전용 범위에서는 휴지통이 없다`() = runTest(testDispatcher) {
        coEvery { drive.listMedia(any(), any(), any(), any(), any()) } returns DrivePage(page1, null)
        val vm = createViewModel()
        vm.load()
        advanceUntilIdle()
        vm.setScope(DriveMediaScope.Folder("shared", "Shared", readOnly = true))
        advanceUntilIdle()
        assertTrue(!vm.content().canTrash)
        vm.trash(listOf(page1[0]))
        advanceUntilIdle()
        coVerify(exactly = 0) { drive.setTrashed(any(), any()) }
    }

    /** 올린 시각이 내려가는 순서 — 서버가 주는 순서와 같다 */
    private fun entry(id: String, mime: String = "image/jpeg") = DriveEntry(
        id = id,
        name = "$id.jpg",
        mimeType = mime,
        sizeBytes = null,
        modifiedTimeMillis = null,
        webViewLink = null,
        createdTimeMillis = 1_000_000L - (id.filter { it.isDigit() }.toLongOrNull() ?: 0L),
    )

    private fun item(id: Long) = MediaItem(
        id = id,
        uri = uri,
        displayName = "IMG_$id.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 1_024,
        dateTakenMillis = 1_757_000_000_000,
        bucketId = 1,
        bucketName = "Camera",
        relativePath = "DCIM/Camera/",
    )
}
