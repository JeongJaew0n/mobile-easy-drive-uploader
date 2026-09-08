package com.jjw.easygallery.feature.gallery

import android.net.Uri
import app.cash.turbine.test
import com.jjw.easygallery.core.data.media.MediaActionController
import com.jjw.easygallery.core.data.media.MediaActionRunner
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import com.jjw.easygallery.core.domain.model.UploadSummary
import com.jjw.easygallery.core.domain.usecase.EnqueueUploadsUseCase
import com.jjw.easygallery.core.domain.usecase.ManageUploadQueueUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GalleryViewModelTest {

    private val repository: MediaRepository = mockk {
        every { supportsTrashAndFavorites } returns true
    }
    private val actionController = MediaActionController(mockk<MediaActionRunner>())
    private val uploadQueue: UploadQueueRepository = mockk {
        every { observeSummary() } returns flowOf(UploadSummary())
    }
    private val enqueueUploads: EnqueueUploadsUseCase = mockk()
    private val manageQueue: ManageUploadQueueUseCase = mockk()

    // StandardTestDispatcher: 구독 전까지 upstream 이 실행되지 않아 상태 전이 순서를 관찰할 수 있다.
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stays Loading and does not query until permission status is known`() = runTest(testDispatcher) {
        val viewModel = GalleryViewModel(repository, uploadQueue, enqueueUploads, manageQueue, actionController)

        viewModel.uiState.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
            expectNoEvents()
        }
        verify(exactly = 0) { repository.observeMedia(any()) }
    }

    @Test
    fun `denied permission shows PermissionRequired without querying`() = runTest(testDispatcher) {
        val viewModel = GalleryViewModel(repository, uploadQueue, enqueueUploads, manageQueue, actionController)

        viewModel.uiState.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Denied)
            assertEquals(GalleryUiState.PermissionRequired, awaitItem())
        }
        verify(exactly = 0) { repository.observeMedia(any()) }
    }

    @Test
    fun `full permission loads content grouped by date`() = runTest(testDispatcher) {
        val item = sampleItem(id = 1)
        every { repository.observeMedia(any()) } returns flowOf(listOf(item))
        val viewModel = GalleryViewModel(repository, uploadQueue, enqueueUploads, manageQueue, actionController)

        viewModel.uiState.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Full)
            val content = awaitItem() as GalleryUiState.Content
            assertEquals(1, content.itemCount)
            assertEquals(listOf(item), content.sections.single().items)
            assertEquals(false, content.isPartialAccess)
        }
    }

    @Test
    fun `partial permission flags content as partial access`() = runTest(testDispatcher) {
        every { repository.observeMedia(any()) } returns flowOf(emptyList())
        val viewModel = GalleryViewModel(repository, uploadQueue, enqueueUploads, manageQueue, actionController)

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Partial)
            val content = awaitItem() as GalleryUiState.Content
            assertTrue(content.isPartialAccess)
            assertTrue(content.sections.isEmpty())
        }
    }

    @Test
    fun `toggleSelection adds and removes ids and clearSelection resets`() = runTest(testDispatcher) {
        val items = listOf(sampleItem(1), sampleItem(2))
        every { repository.observeMedia(any()) } returns flowOf(items)
        val viewModel = GalleryViewModel(repository, uploadQueue, enqueueUploads, manageQueue, actionController)

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Full)
            assertTrue((awaitItem() as GalleryUiState.Content).selectedIds.isEmpty())

            viewModel.toggleSelection(1)
            assertEquals(setOf(1L), (awaitItem() as GalleryUiState.Content).selectedIds)

            viewModel.toggleSelection(2)
            val both = awaitItem() as GalleryUiState.Content
            assertEquals(setOf(1L, 2L), both.selectedIds)
            assertTrue(both.isSelectionMode)

            viewModel.toggleSelection(1)
            assertEquals(setOf(2L), (awaitItem() as GalleryUiState.Content).selectedIds)

            viewModel.clearSelection()
            assertEquals(false, (awaitItem() as GalleryUiState.Content).isSelectionMode)

            viewModel.setSelection(setOf(1L, 2L))
            assertEquals(setOf(1L, 2L), (awaitItem() as GalleryUiState.Content).selectedIds)
        }
    }

    @Test
    fun `repository failure maps to Error state`() = runTest(testDispatcher) {
        val boom = IllegalStateException("boom")
        every { repository.observeMedia(any()) } returns flow { throw boom }
        val viewModel = GalleryViewModel(repository, uploadQueue, enqueueUploads, manageQueue, actionController)

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Full)
            val error = awaitItem()
            assertTrue(error is GalleryUiState.Error)
            // combine 은 스택트레이스 복구를 위해 예외 사본을 만들 수 있으므로 동일성 대신 타입·메시지 비교
            val throwable = (error as GalleryUiState.Error).throwable
            assertTrue(throwable is IllegalStateException)
            assertEquals(boom.message, throwable.message)
        }
    }

    private fun sampleItem(id: Long): MediaItem = MediaItem(
        id = id,
        uri = mockk<Uri>(relaxed = true),
        displayName = "IMG_$id.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 1_024,
        dateTakenMillis = 1_757_000_000_000,
        bucketId = 1,
        bucketName = "Camera",
    )
}
