package com.jjw.easygallery.feature.viewer

import android.net.Uri
import com.jjw.easygallery.core.data.media.MediaActionController
import com.jjw.easygallery.core.data.media.MediaActionRunner
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.domain.model.MediaDetails
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import com.jjw.easygallery.core.domain.usecase.EnqueueUploadsUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MediaViewerViewModelTest {

    private val items = MutableStateFlow(listOf(item(1), item(2), item(3)))
    private val repository: MediaRepository = mockk {
        every { supportsTrashAndFavorites } returns true
        every { observeMedia(any()) } returns items
        coEvery { readDetails(any()) } returns MediaDetails()
    }
    private val enqueueUploads: EnqueueUploadsUseCase = mockk()
    private val uploadLedger: UploadLedgerRepository = mockk {
        every { observeUploadedIds() } returns MutableStateFlow(setOf(2L))
    }
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
    fun `load selects the requested item and reports its index`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        collectState(viewModel)

        viewModel.load(mediaId = 2, favoritesOnly = false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2L, state.current?.id)
        assertEquals(1, state.currentIndex)
        assertEquals(3, state.items.size)
        verify { repository.observeMedia(MediaFilter.All) }
    }

    @Test
    fun `favoritesOnly entry observes the favorites filter`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        collectState(viewModel)

        viewModel.load(mediaId = 1, favoritesOnly = true)
        advanceUntilIdle()

        verify { repository.observeMedia(MediaFilter.Favorites) }
    }

    @Test
    fun `isUploaded follows the ledger for the current item`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        collectState(viewModel)

        viewModel.load(mediaId = 2, favoritesOnly = false)
        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.isUploaded)

        viewModel.onPageChanged(0)
        advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.isUploaded)
    }

    @Test
    fun `onPageChanged moves the current item`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        collectState(viewModel)
        viewModel.load(mediaId = 1, favoritesOnly = false)
        advanceUntilIdle()

        viewModel.onPageChanged(2)
        advanceUntilIdle()

        assertEquals(3L, viewModel.uiState.value.current?.id)
        assertEquals(2, viewModel.uiState.value.currentIndex)
    }

    @Test
    fun `deleting the current item keeps the same slot`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        collectState(viewModel)
        viewModel.load(mediaId = 2, favoritesOnly = false)
        advanceUntilIdle()

        // 2번이 사라지면 같은 자리에 있던 3번이 보여야 한다
        items.value = listOf(item(1), item(3))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.currentIndex)
        assertEquals(3L, state.items[state.currentIndex].id)
    }

    @Test
    fun `image details are loaded once per item`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        collectState(viewModel)
        viewModel.load(mediaId = 1, favoritesOnly = false)
        advanceUntilIdle()

        viewModel.onPageChanged(0)
        advanceUntilIdle()

        assertEquals(MediaDetails(), viewModel.uiState.value.details)
        io.mockk.coVerify(exactly = 1) { repository.readDetails(match { it.id == 1L }) }
    }

    /** uiState 는 WhileSubscribed 라 구독자가 있어야 흐른다. */
    private fun kotlinx.coroutines.test.TestScope.collectState(viewModel: MediaViewerViewModel) {
        backgroundScope.launch { viewModel.uiState.collect { } }
    }

    private fun createViewModel() = MediaViewerViewModel(
        mediaRepository = repository,
        actionController = MediaActionController(mockk<MediaActionRunner>()),
        enqueueUploads = enqueueUploads,
        uploadLedger = uploadLedger,
        categoryRepository = mockk { every { observeAssignments() } returns MutableStateFlow(emptyMap()) },
    )

    private companion object {
        fun item(id: Long) = MediaItem(
            id = id,
            uri = mockk<Uri>(relaxed = true),
            displayName = "IMG_$id.jpg",
            type = MediaType.IMAGE,
            mimeType = "image/jpeg",
            sizeBytes = 1_000,
            dateTakenMillis = 1_757_000_000_000 - id,
            bucketId = 1,
            bucketName = "Camera",
            relativePath = "DCIM/Camera/",
        )
    }
}
