package com.jjw.easygallery.feature.albums

import android.net.Uri
import app.cash.turbine.test
import com.jjw.easygallery.core.data.hidden.HiddenMediaRepository
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val uri = mockk<Uri>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `최근 사진이 있는 앨범부터, 숨긴 사진만 있는 앨범은 빠진다`() = runTest(testDispatcher) {
        val repository: MediaRepository = mockk {
            every { supportsTrashAndFavorites } returns true
            every { observeMedia(any()) } returns flowOf(
                listOf(
                    item(1, "DCIM/Camera/", taken = 100, favorite = true),
                    item(2, "DCIM/행복이/", taken = 300),
                    item(3, "DCIM/비밀/", taken = 500),
                ),
            )
        }
        val hidden: HiddenMediaRepository = mockk { every { observeHiddenIds() } returns flowOf(setOf(3L)) }
        val viewModel = AlbumsViewModel(repository, hidden)

        viewModel.uiState.test {
            awaitItem() // Loading
            advanceUntilIdle()
            val content = expectMostRecentItem() as AlbumsUiState.Content
            assertEquals(listOf("DCIM/행복이/", "DCIM/Camera/"), content.albums.map { it.relativePath })
            assertEquals(1, content.favoriteCount)
        }
    }

    @Test
    fun `권한이 없어 조회가 실패하면 빈 목록`() = runTest(testDispatcher) {
        val repository: MediaRepository = mockk {
            every { supportsTrashAndFavorites } returns true
            every { observeMedia(any()) } returns flow { throw SecurityException("no permission") }
        }
        val hidden: HiddenMediaRepository = mockk { every { observeHiddenIds() } returns flowOf(emptySet()) }
        val viewModel = AlbumsViewModel(repository, hidden)

        viewModel.uiState.test {
            awaitItem() // Loading
            advanceUntilIdle()
            assertEquals(emptyList<Any>(), (expectMostRecentItem() as AlbumsUiState.Content).albums)
        }
    }

    private fun item(id: Long, path: String, taken: Long, favorite: Boolean = false) = MediaItem(
        id = id,
        uri = uri,
        displayName = "IMG_$id.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 1,
        dateTakenMillis = taken,
        bucketId = id,
        bucketName = path.trimEnd('/').substringAfterLast('/'),
        relativePath = path,
        isFavorite = favorite,
    )
}
