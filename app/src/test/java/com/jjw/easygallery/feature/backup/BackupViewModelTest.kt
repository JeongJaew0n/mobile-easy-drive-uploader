package com.jjw.easygallery.feature.backup

import android.net.Uri
import app.cash.turbine.test
import com.jjw.easygallery.core.data.hidden.HiddenMediaRepository
import com.jjw.easygallery.core.data.media.ActionOutcome
import com.jjw.easygallery.core.data.media.MediaAction
import com.jjw.easygallery.core.data.media.MediaActionController
import com.jjw.easygallery.core.data.media.MediaActionRunner
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.prefs.UserPreferences
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.remote.RemoteAccountRepository
import com.jjw.easygallery.core.data.upload.DeviceConditions
import com.jjw.easygallery.core.data.upload.DeviceConditionsMonitor
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import com.jjw.easygallery.core.domain.model.UploadSummary
import com.jjw.easygallery.core.domain.usecase.ManageUploadQueueUseCase
import com.jjw.easygallery.core.domain.usecase.ObserveUploadSummaryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class BackupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    // 항목끼리 같은지 비교하므로 Uri 하나를 같이 쓴다(목 객체는 서로 다르다)
    private val uri = mockk<Uri>(relaxed = true)
    private val prefsFlow = MutableStateFlow(UserPreferences(accountEmail = "a@example.com"))
    private val prefs: UserPreferencesRepository = mockk { every { preferences } returns prefsFlow }
    private val repository: MediaRepository = mockk {
        every { observeMedia(any()) } returns flowOf(listOf(item(1), item(2), item(3)))
    }
    private val hidden: HiddenMediaRepository = mockk { every { observeHiddenIds() } returns flowOf(setOf(3L)) }
    private val ledger: UploadLedgerRepository = mockk {
        every { observeUploadedIds(any()) } returns flowOf(setOf(1L, 3L))
    }
    private val queue: UploadQueueRepository = mockk { every { observeSummary() } returns flowOf(UploadSummary()) }
    private val conditions: DeviceConditionsMonitor = mockk {
        every { observe() } returns flowOf(DeviceConditions(isUnmetered = true, isCharging = true))
    }
    private val runner: MediaActionRunner = mockk()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = BackupViewModel(
        mediaRepository = repository,
        hiddenMedia = hidden,
        uploadLedger = ledger,
        observeUploadSummary = ObserveUploadSummaryUseCase(queue, conditions, prefs),
        manageQueue = mockk<ManageUploadQueueUseCase>(relaxed = true),
        actionController = MediaActionController(runner),
        prefs = prefs,
        remoteAccounts = mockk<RemoteAccountRepository> { every { observeAccounts() } returns flowOf(emptyList()) },
    )

    @Test
    fun `올린 사진은 기기에 있고 숨기지 않은 것만 센다`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.uiState.test {
            awaitItem() // Loading
            advanceUntilIdle()
            val content = expectMostRecentItem() as BackupUiState.Content
            // 1 은 올림, 2 는 안 올림, 3 은 올렸지만 숨김
            assertEquals(1, content.uploadedOnDeviceCount)
            assertEquals(BackupTarget(accountName = null, folderName = null), content.target)
        }
    }

    @Test
    fun `휴지통으로 옮기는 것도 같은 대상만`() = runTest(testDispatcher) {
        coEvery { runner.run(any()) } answers { ActionOutcome.Done(firstArg(), 1) }
        val viewModel = createViewModel()
        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            viewModel.trashUploadedOnDevice()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify { runner.run(MediaAction.Trash(listOf(item(1)), trashed = true)) }
    }

    @Test
    fun `연결된 곳이 없으면 대상이 없다`() = runTest(testDispatcher) {
        prefsFlow.value = UserPreferences()
        val viewModel = createViewModel()
        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            assertEquals(null, (expectMostRecentItem() as BackupUiState.Content).target)
        }
    }

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
