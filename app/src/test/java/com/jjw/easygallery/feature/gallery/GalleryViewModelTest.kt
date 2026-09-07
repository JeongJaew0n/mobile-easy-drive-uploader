package com.jjw.easygallery.feature.gallery

import android.net.Uri
import app.cash.turbine.test
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
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

    private val repository: MediaRepository = mockk()

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
        val viewModel = GalleryViewModel(repository)

        viewModel.uiState.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
            expectNoEvents()
        }
        verify(exactly = 0) { repository.observeMedia() }
    }

    @Test
    fun `denied permission shows PermissionRequired without querying`() = runTest(testDispatcher) {
        val viewModel = GalleryViewModel(repository)

        viewModel.uiState.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Denied)
            assertEquals(GalleryUiState.PermissionRequired, awaitItem())
        }
        verify(exactly = 0) { repository.observeMedia() }
    }

    @Test
    fun `full permission loads content grouped by date`() = runTest(testDispatcher) {
        val item = sampleItem(id = 1)
        every { repository.observeMedia() } returns flowOf(listOf(item))
        val viewModel = GalleryViewModel(repository)

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
        every { repository.observeMedia() } returns flowOf(emptyList())
        val viewModel = GalleryViewModel(repository)

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Partial)
            val content = awaitItem() as GalleryUiState.Content
            assertTrue(content.isPartialAccess)
            assertTrue(content.sections.isEmpty())
        }
    }

    @Test
    fun `repository failure maps to Error state`() = runTest(testDispatcher) {
        val boom = IllegalStateException("boom")
        every { repository.observeMedia() } returns flow { throw boom }
        val viewModel = GalleryViewModel(repository)

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Full)
            val error = awaitItem()
            assertTrue(error is GalleryUiState.Error)
            assertEquals(boom, (error as GalleryUiState.Error).throwable)
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
