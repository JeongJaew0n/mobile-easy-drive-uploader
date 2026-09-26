package com.jjw.easygallery.feature.gallery

import android.net.Uri
import app.cash.turbine.test
import com.jjw.easygallery.core.data.category.CategoryRepository
import com.jjw.easygallery.core.data.category.OrphanAssignmentCleaner
import com.jjw.easygallery.core.data.hidden.HiddenMediaRepository
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
import com.jjw.easygallery.core.domain.model.Category
import com.jjw.easygallery.core.domain.model.CategoryAssignments
import com.jjw.easygallery.core.domain.model.CategoryFilter
import com.jjw.easygallery.core.domain.model.DateRange
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import com.jjw.easygallery.core.domain.model.UploadSummary
import com.jjw.easygallery.core.domain.usecase.AssignCategoriesUseCase
import com.jjw.easygallery.core.domain.usecase.EnqueueUploadsUseCase
import com.jjw.easygallery.core.domain.usecase.ManageUploadQueueUseCase
import io.mockk.coEvery
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
    private val uploadedIds = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>>(emptySet())
    private val uploadLedger: UploadLedgerRepository = mockk {
        every { observeUploadedIds(any()) } returns uploadedIds
    }
    private val enqueueUploads: EnqueueUploadsUseCase = mockk()
    private val remoteAccounts: RemoteAccountRepository =
        mockk { every { observeAccounts() } returns flowOf(emptyList()) }
    private val manageQueue: ManageUploadQueueUseCase = mockk()
    private val categories = kotlinx.coroutines.flow.MutableStateFlow<List<Category>>(emptyList())
    private val assignments = kotlinx.coroutines.flow.MutableStateFlow<CategoryAssignments>(emptyMap())
    private val categoryRepository: CategoryRepository = mockk {
        every { observeCategories() } returns categories
        every { observeAssignments() } returns assignments
        coEvery { removeMedia(any()) } returns Unit
    }
    private val assignCategories = AssignCategoriesUseCase(categoryRepository)
    private val hiddenIds = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>>(emptySet())
    private val hiddenMedia: HiddenMediaRepository = mockk {
        every { observeHiddenIds() } returns hiddenIds
        coEvery { hide(any()) } answers { hiddenIds.value = hiddenIds.value + firstArg<Collection<Long>>() }
    }

    // 기본은 "무제한 회선 + 충전 중" — 대기 이유가 생기지 않아 기존 기대값이 그대로 유지된다
    private val conditions: DeviceConditionsMonitor = mockk {
        every { observe() } returns flowOf(DeviceConditions(isUnmetered = true, isCharging = true))
    }
    private val prefs: UserPreferencesRepository = mockk {
        every { preferences } returns kotlinx.coroutines.flow.MutableStateFlow(UserPreferences())
    }

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

    private fun createViewModel() =
        GalleryViewModel(
            repository,
            uploadQueue,
            uploadLedger,
            enqueueUploads,
            manageQueue,
            actionController,
            categoryRepository,
            assignCategories,
            OrphanAssignmentCleaner(repository, categoryRepository),
            prefs,
            hiddenMedia,
            conditions,
            remoteAccounts,
            auth = mockk(relaxed = true),
            startGuestUpload = mockk(relaxed = true),
        )

    @Test
    fun `stays Loading and does not query until permission status is known`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.uiState.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
            expectNoEvents()
        }
        verify(exactly = 0) { repository.observeMedia(any()) }
    }

    @Test
    fun `denied permission shows PermissionRequired without querying`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

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
        val viewModel = createViewModel()

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
        val viewModel = createViewModel()

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
        val viewModel = createViewModel()

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
    fun `date range keeps only items taken in that period`() = runTest(testDispatcher) {
        val zone = java.time.ZoneId.systemDefault()
        fun at(date: java.time.LocalDate) =
            date.atTime(java.time.LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        val older = sampleItem(1).copy(dateTakenMillis = at(java.time.LocalDate.of(2026, 1, 1)))
        val inRange = sampleItem(2).copy(dateTakenMillis = at(java.time.LocalDate.of(2026, 9, 7)))
        every { repository.observeMedia(any()) } returns flowOf(listOf(inRange, older))
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Full)
            assertEquals(2, (awaitItem() as GalleryUiState.Content).itemCount)

            val range = DateRange(java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 30))
            viewModel.setDateRange(range)
            val filtered = awaitItem() as GalleryUiState.Content
            assertEquals(1, filtered.itemCount)
            assertEquals(listOf(inRange), filtered.sections.single().items)
            assertEquals(range, filtered.dateRange)

            viewModel.setDateRange(null)
            assertEquals(2, (awaitItem() as GalleryUiState.Content).itemCount)
        }
    }

    @Test
    fun `item animations are skipped right after a filter change and resume afterwards`() = runTest(testDispatcher) {
        every { repository.observeMedia(any()) } returns flowOf(listOf(sampleItem(1), sampleItem(2)))
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Full)
            // 최초 목록은 애니메이션 없이 바로 그린다(시작 페이드 제거)
            assertEquals(false, (awaitItem() as GalleryUiState.Content).animateItemChanges)

            // 기간 필터 변경 직후 첫 목록은 통째로 바뀌므로 애니메이션 생략
            viewModel.setDateRange(DateRange(java.time.LocalDate.of(2020, 1, 1), java.time.LocalDate.of(2030, 1, 1)))
            assertEquals(false, (awaitItem() as GalleryUiState.Content).animateItemChanges)

            // 그 뒤 선택 변경처럼 목록이 그대로인 갱신은 다시 애니메이션
            viewModel.toggleSelection(1)
            assertTrue((awaitItem() as GalleryUiState.Content).animateItemChanges)
        }
    }

    @Test
    fun `uploaded ids drive the badge set, backup count and the not-backed-up filter`() = runTest(testDispatcher) {
        every { repository.observeMedia(any()) } returns flowOf(listOf(sampleItem(1), sampleItem(2), sampleItem(3)))
        uploadedIds.value = setOf(2L)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Full)
            val all = awaitItem() as GalleryUiState.Content
            assertEquals(setOf(2L), all.uploadedIds)
            assertEquals(1, all.uploadedCount)
            assertEquals(3, all.itemCount)

            viewModel.setNotBackedUpOnly(true)
            val pending = awaitItem() as GalleryUiState.Content
            assertEquals(listOf(1L, 3L), pending.sections.flatMap { it.items }.map { it.id })
            assertTrue(pending.notBackedUpOnly)

            // 업로드가 끝나 원장이 갱신되면 필터 결과에서도 빠진다
            uploadedIds.value = setOf(2L, 3L)
            val remaining = (awaitItem() as GalleryUiState.Content).sections.flatMap { it.items }.map { it.id }
            assertEquals(listOf(1L), remaining)
        }
    }

    @Test
    fun `repository failure maps to Error state`() = runTest(testDispatcher) {
        val boom = IllegalStateException("boom")
        every { repository.observeMedia(any()) } returns flow { throw boom }
        val viewModel = createViewModel()

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

    private fun sampleItem(id: Long, relativePath: String = "DCIM/Camera/"): MediaItem = MediaItem(
        id = id,
        uri = mockk<Uri>(relaxed = true),
        displayName = "IMG_$id.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 1_024,
        dateTakenMillis = 1_757_000_000_000,
        bucketId = 1,
        bucketName = "Camera",
        relativePath = relativePath,
    )

    // ---------- 숨김 (docs/PHOTO_HIDING.md) ----------

    @Test
    fun `숨긴 사진은 목록에서 빠진다`() = runTest(testDispatcher) {
        every { repository.observeMedia(any()) } returns flowOf(listOf(sampleItem(1), sampleItem(2), sampleItem(3)))
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Full)
            assertEquals(3, (awaitItem() as GalleryUiState.Content).itemCount)

            hiddenIds.value = setOf(2L)
            (awaitItem() as GalleryUiState.Content).let {
                assertEquals(2, it.itemCount)
                assertEquals(listOf(1L, 3L), it.sections.flatMap { s -> s.items }.map { m -> m.id })
            }
        }
    }

    /** 탭을 바꾸거나 필터를 걸어도 숨긴 사진은 나오면 안 된다 */
    @Test
    fun `숨김은 탭보다 바깥이다`() = runTest(testDispatcher) {
        val camera = sampleItem(1, "DCIM/Camera/")
        val kakao = sampleItem(2, "Pictures/KakaoTalk/")
        every { repository.observeMedia(any()) } returns flowOf(listOf(camera, kakao))
        hiddenIds.value = setOf(2L)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Full)
            awaitItem()

            viewModel.setTab(GalleryTab.OTHER)
            assertEquals(0, (awaitItem() as GalleryUiState.Content).itemCount)
        }
    }

    @Test
    fun `기간 달력의 날짜별 개수도 숨김을 뺀다`() = runTest(testDispatcher) {
        every { repository.observeMedia(any()) } returns flowOf(listOf(sampleItem(1), sampleItem(2)))
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Full)
            assertEquals(2, (awaitItem() as GalleryUiState.Content).dayCounts.values.sum())

            hiddenIds.value = setOf(1L)
            assertEquals(1, (awaitItem() as GalleryUiState.Content).dayCounts.values.sum())
        }
    }

    // ---------- 출처 탭 (docs/plans/gallery-source-tabs) ----------

    @Test
    fun `탭은 출처로 목록을 가른다`() = runTest(testDispatcher) {
        val camera = sampleItem(1, "DCIM/Camera/")
        val shot = sampleItem(2, "DCIM/Screenshots/")
        val kakao = sampleItem(3, "Pictures/KakaoTalk/")
        every { repository.observeMedia(any()) } returns flowOf(listOf(camera, shot, kakao))
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Full)
            assertEquals(3, (awaitItem() as GalleryUiState.Content).itemCount)

            viewModel.setTab(GalleryTab.CAMERA)
            (awaitItem() as GalleryUiState.Content).let {
                assertEquals(listOf(camera), it.sections.single().items)
                assertEquals(GalleryTab.CAMERA, it.tab)
            }

            viewModel.setTab(GalleryTab.SCREENSHOT)
            assertEquals(listOf(shot), (awaitItem() as GalleryUiState.Content).sections.single().items)

            viewModel.setTab(GalleryTab.OTHER)
            assertEquals(listOf(kakao), (awaitItem() as GalleryUiState.Content).sections.single().items)
        }
    }

    /** 탭을 바꾸면 이전 탭에서 걸어둔 조건이 따라오지 않는다(2026-09-17 결정) */
    @Test
    fun `탭을 바꾸면 기간·백업·카테고리 필터가 풀린다`() = runTest(testDispatcher) {
        val camera = sampleItem(1, "DCIM/Camera/")
        val kakao = sampleItem(2, "Pictures/KakaoTalk/")
        every { repository.observeMedia(any()) } returns flowOf(listOf(camera, kakao))
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Full)
            awaitItem()

            viewModel.setNotBackedUpOnly(true)
            assertTrue((awaitItem() as GalleryUiState.Content).notBackedUpOnly)
            viewModel.setCategoryFilter(CategoryFilter.Uncategorized)
            assertEquals(CategoryFilter.Uncategorized, (awaitItem() as GalleryUiState.Content).categoryFilter)

            viewModel.setTab(GalleryTab.OTHER)
            (awaitItem() as GalleryUiState.Content).let {
                assertEquals(GalleryTab.OTHER, it.tab)
                assertEquals(false, it.notBackedUpOnly)
                assertEquals(null, it.categoryFilter)
                assertEquals(null, it.dateRange)
                assertEquals(false, it.favoritesOnly)
            }
        }
    }

    @Test
    fun `같은 탭을 다시 눌러도 아무 일도 없다`() = runTest(testDispatcher) {
        every { repository.observeMedia(any()) } returns flowOf(listOf(sampleItem(1)))
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Full)
            awaitItem()
            viewModel.setNotBackedUpOnly(true)
            awaitItem()

            // ALL 이 이미 선택돼 있다 — 필터가 풀리면 안 된다
            viewModel.setTab(GalleryTab.ALL)
            expectNoEvents()
        }
    }

    @Test
    fun `category filter keeps only matching items and uncategorized shows the rest`() = runTest(testDispatcher) {
        every { repository.observeMedia(any()) } returns flowOf(listOf(sampleItem(1), sampleItem(2), sampleItem(3)))
        assignments.value = mapOf(1L to setOf(10L), 2L to setOf(20L))
        categories.value = listOf(Category(10, "A", 0, 0), Category(20, "B", 1, 1))
        val viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // Loading
            viewModel.onPermissionStatusChanged(MediaPermissionStatus.Full)
            assertEquals(3, (awaitItem() as GalleryUiState.Content).itemCount)

            viewModel.setCategoryFilter(CategoryFilter.Any(setOf(10L, 20L)))
            val any = awaitItem() as GalleryUiState.Content
            assertEquals(2, any.itemCount)
            assertEquals(false, any.animateItemChanges)

            viewModel.setCategoryFilter(CategoryFilter.Uncategorized)
            val none = awaitItem() as GalleryUiState.Content
            assertEquals(listOf(3L), none.sections.flatMap { it.items }.map { it.id })

            viewModel.setCategoryFilter(null)
            assertEquals(3, (awaitItem() as GalleryUiState.Content).itemCount)
        }
    }
}
