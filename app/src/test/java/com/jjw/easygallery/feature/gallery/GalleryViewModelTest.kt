package com.jjw.easygallery.feature.gallery

import app.cash.turbine.test
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.domain.model.MediaItem
import io.mockk.every
import io.mockk.mockk
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

    // StandardTestDispatcher: 구독 전까지 upstream 이 실행되지 않아 Loading → 결과 순서를 관찰할 수 있다.
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
    fun `initial state is Loading then Success with repository items`() = runTest(testDispatcher) {
        val item = mockk<MediaItem>()
        every { repository.observeMedia() } returns flowOf(listOf(item))

        val viewModel = GalleryViewModel(repository)
        assertEquals(GalleryUiState.Loading, viewModel.uiState.value)

        viewModel.uiState.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
            assertEquals(GalleryUiState.Success(listOf(item)), awaitItem())
        }
    }

    @Test
    fun `repository failure maps to Error state`() = runTest(testDispatcher) {
        val boom = IllegalStateException("boom")
        every { repository.observeMedia() } returns flow { throw boom }

        val viewModel = GalleryViewModel(repository)

        viewModel.uiState.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
            val error = awaitItem()
            assertTrue(error is GalleryUiState.Error)
            assertEquals(boom, (error as GalleryUiState.Error).throwable)
        }
    }
}
