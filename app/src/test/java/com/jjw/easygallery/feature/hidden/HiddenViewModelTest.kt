package com.jjw.easygallery.feature.hidden

import android.net.Uri
import app.cash.turbine.test
import com.jjw.easygallery.core.data.hidden.HiddenMediaRepository
import com.jjw.easygallery.core.data.hidden.HiddenPinRepository
import com.jjw.easygallery.core.data.hidden.PinGate
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HiddenViewModelTest {

    private val gate = MutableStateFlow(PinGate(isSet = true, failedAttempts = 0, lockedUntilMillis = 0))
    private val hiddenIds = MutableStateFlow<Set<Long>>(emptySet())
    private val media: MediaRepository = mockk {
        every { observeMedia(any()) } returns flowOf(listOf(item(1), item(2)))
    }
    private val hiddenMedia: HiddenMediaRepository = mockk {
        every { observeHiddenIds() } returns hiddenIds
    }
    private val pinRepository: HiddenPinRepository = mockk {
        every { observeGate() } returns gate
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

    private fun createViewModel() = HiddenViewModel(media, hiddenMedia, pinRepository)

    @Test
    fun `PIN 이 설정돼 있으면 입력 화면`() = runTest(testDispatcher) {
        createViewModel().uiState.test {
            awaitItem() // Loading
            assertFalse((awaitItem() as HiddenUiState.Locked).isSetup)
        }
    }

    @Test
    fun `PIN 이 없으면 설정 화면`() = runTest(testDispatcher) {
        gate.value = PinGate(isSet = false, failedAttempts = 0, lockedUntilMillis = 0)
        createViewModel().uiState.test {
            awaitItem()
            assertTrue((awaitItem() as HiddenUiState.Locked).isSetup)
        }
    }

    /**
     * 복구의 핵심. 잠긴 동안에도 기기 잠금으로 들어오면 곧바로 새 PIN 을 정할 수 있어야 한다 —
     * 잊어버린 사람이 10분을 기다려야 하면 복구 장치가 아니다.
     */
    @Test
    fun `기기 잠금을 통과하면 잠겨 있어도 새 PIN 을 정할 수 있다`() = runTest(testDispatcher) {
        gate.value = PinGate(isSet = true, failedAttempts = 9, lockedUntilMillis = Long.MAX_VALUE)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem()
            (awaitItem() as HiddenUiState.Locked).let {
                assertFalse(it.isSetup)
                assertEquals(Long.MAX_VALUE, it.lockedUntilMillis)
            }

            viewModel.onDeviceCredentialConfirmed()

            (awaitItem() as HiddenUiState.Locked).let {
                assertTrue("새 PIN 을 정하는 화면이어야", it.isSetup)
                assertEquals("잠금이 걷혀야", 0, it.lockedUntilMillis)
                assertEquals("실패 횟수도 가려야", 0, it.failedAttempts)
            }
        }
    }

    @Test
    fun `화면을 잠그면 복구 상태도 풀린다`() = runTest(testDispatcher) {
        gate.value = PinGate(isSet = true, failedAttempts = 9, lockedUntilMillis = Long.MAX_VALUE)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem()
            awaitItem()
            viewModel.onDeviceCredentialConfirmed()
            assertTrue((awaitItem() as HiddenUiState.Locked).isSetup)

            viewModel.lock()
            (awaitItem() as HiddenUiState.Locked).let {
                assertFalse("다시 입력 화면", it.isSetup)
                assertEquals(Long.MAX_VALUE, it.lockedUntilMillis)
            }
        }
    }

    private fun item(id: Long) = MediaItem(
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
