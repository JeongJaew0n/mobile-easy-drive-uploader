package com.jjw.easygallery.feature.viewer

import android.net.Uri
import com.jjw.easygallery.core.data.category.CategoryRepository
import com.jjw.easygallery.core.data.media.MediaActionController
import com.jjw.easygallery.core.data.media.MediaActionRunner
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.prefs.UserPreferences
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.domain.model.MediaDetails
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import com.jjw.easygallery.core.domain.usecase.AssignCategoriesUseCase
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
        every { observeUploadedIds(any()) } returns MutableStateFlow(setOf(2L))
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
    fun `현재 항목을 지우면 바로 이전 사진으로 간다`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        collectState(viewModel)
        viewModel.load(mediaId = 2, favoritesOnly = false)
        advanceUntilIdle()

        items.value = listOf(item(1), item(3))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0, state.currentIndex)
        assertEquals(1L, state.current?.id)
    }

    /** current 가 null 이면 화면이 통째로 비어 검은 화면이 된다 — 그 상태로 남지 않아야 한다 */
    @Test
    fun `지운 뒤에도 현재 항목이 비지 않는다`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        collectState(viewModel)
        viewModel.load(mediaId = 3, favoritesOnly = false)
        advanceUntilIdle()

        items.value = listOf(item(1), item(2))
        advanceUntilIdle()

        assertEquals(2L, viewModel.uiState.value.current?.id)
    }

    @Test
    fun `첫 사진을 지우면 이전이 없어 그 자리에 머문다`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        collectState(viewModel)
        viewModel.load(mediaId = 1, favoritesOnly = false)
        advanceUntilIdle()

        items.value = listOf(item(2), item(3))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0, state.currentIndex)
        assertEquals(2L, state.current?.id)
    }

    /** 옮겨간 사진이 곧바로 편집 대상이 된다 — 페이저가 알려주기를 기다리지 않는다 */
    @Test
    fun `지운 뒤 연달아 지우면 옮겨간 사진이 지워진다`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        collectState(viewModel)
        viewModel.load(mediaId = 3, favoritesOnly = false)
        advanceUntilIdle()

        items.value = listOf(item(1), item(2))
        advanceUntilIdle()
        items.value = listOf(item(1))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0, state.currentIndex)
        assertEquals(1L, state.current?.id)
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
        categoryRepository = categoryRepository,
        assignCategories = AssignCategoriesUseCase(categoryRepository),
        hiddenMedia = mockk { every { observeHiddenIds() } returns MutableStateFlow(emptySet()) },
        prefs = mockk { every { preferences } returns MutableStateFlow(UserPreferences()) },
    )

    private val categoryRepository: CategoryRepository = mockk {
        every { observeAssignments() } returns MutableStateFlow(emptyMap())
        every { observeCategories() } returns MutableStateFlow(emptyList())
    }

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
