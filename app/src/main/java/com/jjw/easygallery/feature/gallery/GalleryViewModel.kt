package com.jjw.easygallery.feature.gallery

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.auth.AuthException
import com.jjw.easygallery.core.data.auth.AuthRepository
import com.jjw.easygallery.core.data.auth.GuestPick
import com.jjw.easygallery.core.data.auth.SignInCancelledException
import com.jjw.easygallery.core.data.category.CategoryRepository
import com.jjw.easygallery.core.data.category.OrphanAssignmentCleaner
import com.jjw.easygallery.core.data.chosen.ChosenMediaRepository
import com.jjw.easygallery.core.data.hidden.HiddenMediaRepository
import com.jjw.easygallery.core.data.media.MediaAction
import com.jjw.easygallery.core.data.media.MediaActionController
import com.jjw.easygallery.core.data.media.MediaActionEvent
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.prefs.GALLERY_CELL_STEP_DEFAULT
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.remote.RemoteAccountRepository
import com.jjw.easygallery.core.data.upload.BackupDestinations
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.domain.model.Album
import com.jjw.easygallery.core.domain.model.BackupDestination
import com.jjw.easygallery.core.domain.model.Category
import com.jjw.easygallery.core.domain.model.CategoryAssignments
import com.jjw.easygallery.core.domain.model.CategoryFilter
import com.jjw.easygallery.core.domain.model.DateRange
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.UploadState
import com.jjw.easygallery.core.domain.model.UploadSummary
import com.jjw.easygallery.core.domain.model.albumsFrom
import com.jjw.easygallery.core.domain.model.filterByDate
import com.jjw.easygallery.core.domain.usecase.AssignCategoriesUseCase
import com.jjw.easygallery.core.domain.usecase.EnqueueUploadsUseCase
import com.jjw.easygallery.core.domain.usecase.GuestIsPrimaryException
import com.jjw.easygallery.core.domain.usecase.GuestSession
import com.jjw.easygallery.core.domain.usecase.GuestUploadStarted
import com.jjw.easygallery.core.domain.usecase.ManageUploadQueueUseCase
import com.jjw.easygallery.core.domain.usecase.ObserveUploadSummaryUseCase
import com.jjw.easygallery.core.domain.usecase.StartGuestUploadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
@Suppress(
    "TooGenericExceptionCaught", // UI 경계: 큐 등록 실패는 종류를 가리지 않고 메시지로 보여준다
    "TooManyFunctions", // 화면이 호출하는 API 표면(필터 4·선택 3·편집 7·카테고리 3). 내부 로직은 UseCase/컨트롤러에 있다
    "LongParameterList", // Hilt 생성자 주입의 조합 지점. 각 의존성은 저장소/유즈케이스로 이미 분리돼 있다
)
class GalleryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    uploadQueue: UploadQueueRepository,
    uploadLedger: UploadLedgerRepository,
    private val enqueueUploads: EnqueueUploadsUseCase,
    private val manageQueue: ManageUploadQueueUseCase,
    private val actionController: MediaActionController,
    private val categoryRepository: CategoryRepository,
    private val assignCategories: AssignCategoriesUseCase,
    orphanCleaner: OrphanAssignmentCleaner,
    private val prefs: UserPreferencesRepository,
    private val hiddenMedia: HiddenMediaRepository,
    private val chosenMedia: ChosenMediaRepository,
    observeUploadSummary: ObserveUploadSummaryUseCase,
    remoteAccounts: RemoteAccountRepository,
    private val auth: AuthRepository,
    private val startGuestUpload: StartGuestUploadUseCase,
) : ViewModel() {

    /** "다른 Google 계정으로 업로드" 를 보일지 — 주 계정이 연결돼 있을 때만(공유할 상대가 있어야 한다) */
    val guestUploadAvailable: StateFlow<Boolean> = prefs.preferences.map { it.isSignedIn }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    /** 끝났는데 아직 "기기에서 지우라" 를 닫지 않은 B. 갤러리 배너로 보인다 */
    val guestCleanupEmail: StateFlow<String?> = prefs.preferences.map { it.guestCleanupEmail }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** 계정 선택 창·폴더 고르기를 다녀오는 동안 선택을 붙들어 둔다 */
    private var guestItems: List<MediaItem> = emptyList()

    private val _guestFolderChoice = MutableStateFlow<GuestSession?>(null)

    /** B 가 정해져 폴더를 고를 차례면 그 계정. 화면이 폴더 고르기 시트를 띄운다(guest spec §7) */
    val guestFolderChoice: StateFlow<GuestSession?> = _guestFolderChoice.asStateFlow()

    /** 선택 상단바 "다른 저장소로 업로드" 메뉴 — 로그인된 Drive + 연결된 저장소. 하나뿐이면 메뉴를 숨긴다 */
    val uploadTargets: StateFlow<List<UploadTargetOption>> = combine(
        prefs.preferences,
        remoteAccounts.observeAccounts(),
    ) { p, accounts ->
        buildList {
            if (p.isSignedIn) {
                add(UploadTargetOption(null, GOOGLE_DRIVE_LABEL, isDefault = p.uploadAccountId == null))
            }
            accounts.forEach { acc ->
                add(UploadTargetOption(acc.id, acc.displayName, isDefault = acc.id == p.uploadAccountId))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val showCategoryBadges: Flow<Boolean> = prefs.preferences.map { it.showCategoryBadges }

    // null = 아직 권한 상태를 확인하지 않음
    private val permissionStatus = MutableStateFlow<MediaPermissionStatus?>(null)
    private val filter = MutableStateFlow(MediaFilter.All)
    private val dateRange = MutableStateFlow<DateRange?>(null)
    private val notBackedUpOnly = MutableStateFlow(false)
    private val categoryFilter = MutableStateFlow<CategoryFilter?>(null)

    // 앱을 켜면 고른 사진부터(2026-09-27 사용자 결정)
    private val tab = MutableStateFlow(GalleryTab.CHOSEN)

    /** 앨범 필터 — 앨범(폴더)의 `relativePath`. null 이면 없음(`docs/plans/album-view/spec.md`) */
    private val albumFilter = MutableStateFlow<String?>(null)

    /** 백업된 사진 화면이면 세그먼트·저장소 칩(`docs/plans/backed-up-photos/spec.md`). 아니면 null */
    private val backupView = MutableStateFlow<BackupView?>(null)

    /** 백업 범위일 때만 원장 전부와 큐를 모은다 — 사진 칸에서는 읽지 않는다 */
    private val backupInputs: Flow<BackupInputs?> = backupView.flatMapLatest { view ->
        if (view == null) {
            flowOf(null)
        } else {
            combine(
                uploadLedger.observeRecords(),
                uploadQueue.observeTasks(),
                prefs.preferences.map { it.accountEmail }.distinctUntilChanged(),
                remoteAccounts.observeAccounts(),
            ) { records, tasks, email, accounts -> BackupInputs(view, records, tasks, email, accounts) }
        }
    }

    // 배지·"백업 안 됨" 필터는 현재 업로드 대상 계정 기준(다른 계정에 올린 건 그 계정을 골랐을 때 보인다)
    private val uploadedIds: Flow<Set<Long>> = prefs.preferences
        .map { it.uploadAccountId }
        .distinctUntilChanged()
        .flatMapLatest { uploadLedger.observeUploadedIds(it) }

    /** 필터·기간이 바뀔 때마다 증가. 이 값이 바뀐 직후 첫 목록 갱신은 항목 이동 애니메이션을 끈다(수백 개 동시 이동 방지) */
    private var filterVersion = 0

    // -1 로 시작해 최초 목록은 애니메이션 없이 바로 그린다(시작 페이드 제거, ANIMATION_IMPROVEMENT.md §10)
    private var animatedVersion = -1
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    /** 큐 요약 + 멈춘 까닭. 백업 칸과 같은 문장을 쓰도록 유스케이스가 만든다 */
    private val uploadSummary: Flow<UploadSummary> = observeUploadSummary()

    /**
     * 썸네일 배지(`docs/plans/bottom-navigation/spec.md`): 큐에서 대기·진행 중인 것과 실패한 것.
     * 올라간 것(원장)이 실패보다 앞선다 — 예전 배치의 실패 줄이 남아 있어도 원장에 있으면 올라간 것이다(화면이 가른다).
     */
    private val queueBadges: Flow<QueueBadges> = uploadQueue.observeTasks()
        .map { tasks ->
            QueueBadges(
                pending = tasks.filter { it.state == UploadState.PENDING || it.state == UploadState.RUNNING }
                    .mapTo(HashSet()) { it.mediaId },
                failed = tasks.filter { it.state == UploadState.FAILED }.mapTo(HashSet()) { it.mediaId },
            )
        }
        .distinctUntilChanged()

    /** 썸네일에 무엇을 얹을지 — 설정 둘과 큐 상태를 한 묶음으로(combine 인자 수를 줄인다) */
    private val thumbnailDecorations: Flow<ThumbnailDecorations> = combine(
        showCategoryBadges,
        prefs.preferences.map { it.showBackedUpBadge }.distinctUntilChanged(),
        queueBadges,
        prefs.preferences.map { it.galleryCellSizeStep }.distinctUntilChanged(),
    ) { category, backedUp, queue, cellStep -> ThumbnailDecorations(category, backedUp, queue, cellStep) }

    /** 두 손가락으로 칸 크기를 바꿨다(docs/plans/ux-round2/spec.md §6). 다음에 열어도 그 크기 */
    fun setCellSizeStep(step: Int) {
        viewModelScope.launch { prefs.setGalleryCellSizeStep(step) }
    }

    private val events = Channel<GalleryEvent>(Channel.BUFFERED)
    val eventFlow: Flow<GalleryEvent> = events.receiveAsFlow()

    /** 편집 동의 흐름은 공용 컨트롤러가 담당 */
    val actionEvents = actionController.events

    private var latestItems: List<MediaItem> = emptyList()

    /** 하단바 토글이 넣을지 뺄지 정한다. 목록이 갱신될 때마다 바뀐다 */
    private var latestChosenIds: Set<Long> = emptySet()

    val uiState: StateFlow<GalleryUiState> = combine(permissionStatus, filter) { status, f -> status to f }
        .flatMapLatest { (status, f) -> stateFor(status, f) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = GalleryUiState.Loading,
        )

    init {
        // 고아 할당 정리(전체 접근 권한일 때만). 근거: docs/CATEGORIES.md §6
        orphanCleaner.start(viewModelScope, permissionStatus.map { it == MediaPermissionStatus.Full })
    }

    /** UI 가 권한을 확인·요청한 결과를 알려준다. 화면 복귀 시마다 호출되어도 안전(StateFlow 중복 제거). */
    fun onPermissionStatusChanged(status: MediaPermissionStatus) {
        permissionStatus.value = status
    }

    fun setFavoritesOnly(enabled: Boolean) {
        clearSelection()
        filterVersion++
        filter.value = if (enabled) MediaFilter.Favorites else MediaFilter.All
    }

    /** Drive 에 아직 올라가지 않은 항목만 보기 */
    fun setNotBackedUpOnly(enabled: Boolean) {
        clearSelection()
        filterVersion++
        notBackedUpOnly.value = enabled
    }

    /** null 이면 기간 제한 없음 */
    fun setDateRange(range: DateRange?) {
        clearSelection()
        filterVersion++
        dateRange.value = range
    }

    /** null 이면 카테고리 제한 없음. 기존 필터와 AND, 여러 카테고리는 OR */
    fun setCategoryFilter(filter: CategoryFilter?) {
        clearSelection()
        filterVersion++
        categoryFilter.value = filter
    }

    /**
     * 상단 탭(출처) 전환. **다른 필터를 모두 푼다**(2026-09-17 사용자 결정) —
     * 탭은 "지금 무엇을 보고 있는가" 의 최상위 기준이라, 이전 탭에서 걸어둔 조건이 따라오면
     * 결과가 비어 보이는 이유를 알기 어렵다.
     */
    fun setTab(next: GalleryTab) {
        if (tab.value == next) return
        clearSelection()
        filterVersion++
        tab.value = next
        filter.value = MediaFilter.All
        dateRange.value = null
        notBackedUpOnly.value = false
        categoryFilter.value = null
        albumFilter.value = null
    }

    /**
     * 앨범 하나만 본다. 고르면 탭을 '전체' 로 옮긴다 — "고른 사진" 탭 안에서 앨범을 고르면 두 조건이 겹쳐
     * 비어 보이고, 왜 비었는지 알기 어렵다. 탭을 옮기면 다른 필터도 풀린다(탭 규칙 그대로).
     */
    fun setAlbumFilter(relativePath: String?) {
        if (albumFilter.value == relativePath) return
        if (relativePath != null && tab.value != GalleryTab.ALL) setTab(GalleryTab.ALL)
        clearSelection()
        filterVersion++
        albumFilter.value = relativePath
    }

    /**
     * 앨범 칸에서 연 화면의 범위를 건다(`docs/plans/bottom-navigation/spec.md`). 앨범이면 그 폴더만,
     * 즐겨찾기면 즐겨찾기만 — 둘 다 탭은 '전체' 다(출처 탭은 이 화면에 없다).
     */
    fun openScope(scope: GalleryScope) {
        when (scope) {
            is GalleryScope.Album -> setAlbumFilter(scope.relativePath)
            GalleryScope.Favorites -> {
                setTab(GalleryTab.ALL)
                setFavoritesOnly(true)
            }
            GalleryScope.Backup -> {
                setTab(GalleryTab.ALL)
                setBackupView(BackupView())
            }
        }
    }

    /** 백업된 사진 화면의 세그먼트·저장소 칩. 대기·실패에는 칩이 없다(큐는 지금 대상 하나로 돈다) */
    fun setBackupView(view: BackupView) {
        val next = if (view.status == BackupStatusFilter.PENDING_OR_FAILED) view.copy(destination = null) else view
        if (backupView.value == next) return
        clearSelection()
        filterVersion++
        backupView.value = next
    }

    // ---------- 카테고리 ----------

    suspend fun createCategory(name: String, colorIndex: Int): Result<Category> =
        categoryRepository.create(name, colorIndex)

    /** 선택 항목에 [add] 를 붙이고 [remove] 를 뗀다. 선택은 유지(다른 작업을 이어서 할 수 있게) */
    fun assignCategoriesToSelection(add: Set<Long>, remove: Set<Long>) {
        val ids = selectedIds.value
        if (ids.isEmpty() || (add.isEmpty() && remove.isEmpty())) return
        viewModelScope.launch {
            try {
                assignCategories(ids, add, remove)
                events.send(GalleryEvent.CategoriesAssigned(ids.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "assign categories failed")
                events.send(GalleryEvent.Error(e))
            }
        }
    }

    /** 편집 완료 후처리: 선택 해제, 영구 삭제면 카테고리 할당도 제거 */
    fun onActionDone(event: MediaActionEvent.Done) {
        clearSelection()
        if (event.action is MediaAction.Delete) {
            viewModelScope.launch { categoryRepository.removeMedia(event.action.items.map { it.id }) }
        }
    }

    fun toggleSelection(id: Long) {
        selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    /** 선택한 항목을 숨긴다. 파일은 건드리지 않으므로 동의 창이 없다 */
    fun hideSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            hiddenMedia.hide(ids)
            clearSelection()
            events.send(GalleryEvent.Hidden(ids.size))
        }
    }

    /**
     * 선택한 항목을 고른 사진에 넣는다. 이미 전부 들어 있으면 뺀다 — 하단바의 같은 버튼으로 되돌린다.
     * 파일은 건드리지 않으므로 동의 창이 없다.
     */
    fun toggleChosenSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        val remove = ids.all { it in latestChosenIds }
        viewModelScope.launch {
            if (remove) chosenMedia.unchoose(ids) else chosenMedia.choose(ids)
            clearSelection()
            events.send(GalleryEvent.Chosen(ids.size, added = !remove))
        }
    }

    /**
     * 지금 화면에 보이는 것을 전부 고른다(필터가 걸려 있으면 그 결과 전체).
     * 이미 전부 골라져 있으면 해제한다 — 같은 버튼으로 되돌릴 수 있어야 한다.
     */
    fun toggleSelectAllVisible() {
        val visible = latestItems.map { it.id }.toSet()
        selectedIds.value = if (selectedIds.value.containsAll(visible) && visible.isNotEmpty()) {
            emptySet()
        } else {
            visible
        }
    }

    /** 드래그 범위 선택 결과를 통째로 반영 */
    fun setSelection(ids: Set<Long>) {
        selectedIds.value = ids
    }

    // ---------- 업로드 ----------

    /** 선택 항목을 업로드 큐에 넣는다. 실제 전송은 WorkManager 가 백그라운드에서 수행. */
    fun uploadSelected() = uploadSelected(target = null)

    /** 이번만 [target] 계정으로(설정은 바꾸지 않는다). null 이면 기본 대상 */
    fun uploadSelected(target: UploadTargetOption?) {
        val items = selectedItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            try {
                val added = if (target == null) {
                    enqueueUploads(items)
                } else {
                    enqueueUploads.toAccount(items, target.accountId)
                }
                clearSelection()
                events.send(GalleryEvent.Enqueued(added = added, skipped = items.size - added))
            } catch (e: AuthException) {
                Timber.w(e, "upload needs sign-in")
                events.send(GalleryEvent.SignInRequired)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "enqueue failed")
                events.send(GalleryEvent.Error(e))
            }
        }
    }

    fun cancelUploads() {
        viewModelScope.launch { manageQueue.cancelAll() }
    }

    // ---------- 다른 계정 업로드(docs/plans/guest-account-upload) ----------

    /** 계정 선택 창부터. 주 계정은 건드리지 않는다 */
    fun startGuestUpload() {
        guestItems = selectedItems()
        if (guestItems.isEmpty()) return
        viewModelScope.launch {
            guestStep {
                when (val pick = auth.beginGuestPick()) {
                    is GuestPick.NeedsChooser -> events.send(GalleryEvent.GuestChooser(pick.pendingIntent))
                    is GuestPick.Picked -> runGuestUpload(pick.accessToken)
                }
            }
        }
    }

    fun onGuestPickResult(data: Intent?) {
        viewModelScope.launch { guestStep { runGuestUpload(auth.completeGuestPick(data)) } }
    }

    fun dismissGuestCleanup() {
        viewModelScope.launch { prefs.setGuestCleanupEmail(null) }
    }

    suspend fun listGuestFolders(parentId: String): List<DriveFolder> {
        val email = _guestFolderChoice.value?.email ?: return emptyList()
        return startGuestUpload.listFolders(email, parentId)
    }

    suspend fun createGuestFolder(name: String, parentId: String): DriveFolder {
        val email = checkNotNull(_guestFolderChoice.value?.email) { "no guest session" }
        return startGuestUpload.createFolder(email, name, parentId)
    }

    /** 폴더를 골랐다 — 공유하고 큐에 넣는다 */
    fun onGuestFolderPicked(folder: DriveFolder) {
        val session = _guestFolderChoice.value ?: return
        _guestFolderChoice.value = null
        viewModelScope.launch {
            guestStep {
                val started = startGuestUpload.start(guestItems, session.email, folder)
                guestItems = emptyList()
                clearSelection()
                events.send(GalleryEvent.GuestStarted(started))
            }
        }
    }

    /** 폴더 고르기를 닫았다. 선택은 그대로 둔다 — 다시 시작할 수 있게 */
    fun dismissGuestFolderChoice() {
        _guestFolderChoice.value = null
    }

    private suspend fun runGuestUpload(accessToken: String) {
        _guestFolderChoice.value = startGuestUpload.prepare(accessToken)
    }

    /** 다른 계정 업로드의 실패를 한 곳에서 문구로 바꾼다. 선택 창을 닫은 것은 실패가 아니다 */
    private suspend fun guestStep(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: SignInCancelledException) {
            Timber.i(e, "guest pick cancelled")
        } catch (e: GuestIsPrimaryException) {
            Timber.i(e, "guest is primary")
            events.send(GalleryEvent.GuestIsPrimary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "guest upload failed")
            events.send(GalleryEvent.Error(e))
        }
    }

    // ---------- 편집 ----------

    fun deleteSelected() = perform(MediaAction.Delete(selectedItems()))

    /**
     * 지금 보이는 것 중 **이미 올린 것만** 휴지통으로 보낸다(필터가 걸려 있으면 그 범위).
     *
     * 전체가 아니라 보이는 범위인 이유는, 기간이나 앨범으로 좁혀 놓고 "여기 올린 건 정리하자" 가
     * 실제 쓰임이기 때문이다. 기기 휴지통으로 가므로 30일 안에는 되돌릴 수 있고,
     * 최종 확인은 시스템 동의 창이 받는다.
     */
    fun trashSelected() = perform(MediaAction.Trash(selectedItems(), trashed = true))

    /** 휴지통 이동의 실행 취소 — 미디어 관리 권한이 있을 때만 화면이 부른다(확인 창 없이 되돌린다) */
    fun undoTrash(action: MediaAction.Trash) = perform(MediaAction.Trash(action.items, trashed = false))

    /** 휴지통 이동 뒤 "미디어 관리 허용" 을 아직 제안하지 않았다 */
    val manageMediaHintPending: StateFlow<Boolean> = prefs.preferences.map { !it.manageMediaHintShown }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    fun markManageMediaHintShown() {
        viewModelScope.launch { prefs.markManageMediaHintShown() }
    }

    /** 선택이 모두 즐겨찾기면 해제, 아니면 전부 즐겨찾기 */
    fun toggleFavoriteSelected() {
        val items = selectedItems()
        perform(MediaAction.Favorite(items, favorite = !items.all { it.isFavorite }))
    }

    fun onConsentResult(granted: Boolean) = actionController.onConsentResult(viewModelScope, granted)

    /** 확장자를 안 적으면 원본 확장자를 유지한다. */
    fun renameSelected(newName: String) {
        val item = selectedItems().singleOrNull() ?: return
        val normalized = normalizeDisplayName(newName, item.extension) ?: return
        if (normalized == item.displayName) return
        perform(MediaAction.Rename(item, normalized))
    }

    fun moveSelected(relativePath: String) = perform(MediaAction.Move(selectedItems(), relativePath))

    private fun perform(action: MediaAction) = actionController.perform(viewModelScope, action)

    private fun selectedItems(): List<MediaItem> = latestItems.filter { it.id in selectedIds.value }

    private fun stateFor(status: MediaPermissionStatus?, filter: MediaFilter): Flow<GalleryUiState> = when (status) {
        null -> flowOf(GalleryUiState.Loading)
        MediaPermissionStatus.Denied -> flowOf(GalleryUiState.PermissionRequired)
        MediaPermissionStatus.Full, MediaPermissionStatus.Partial -> contentFlow(status, filter)
    }

    /** 목록에서만 파생되는 값. 선택이 바뀔 때는 다시 계산하지 않도록 분리했다(6천 장 O(n) 재계산 방지). */
    @Suppress("LongParameterList") // 파생값 묶음 — 한 곳에서만 생성
    private class Catalog(
        val items: List<MediaItem>,
        val sections: List<GallerySection>,
        val albums: List<Album>,
        val byId: Map<Long, MediaItem>,
        val backupView: BackupView?,
        val backupDestinations: List<BackupDestination>,
        val range: DateRange?,
        val uploadedIds: Set<Long>,
        val uploadedCount: Int,
        val notBackedUpOnly: Boolean,
        /** 기간 필터 이전 목록의 날짜별 개수 — 기간 선택 달력용 */
        val dayCounts: Map<LocalDate, Int>,
        val categories: List<Category>,
        val assignments: CategoryAssignments,
        val categoryFilter: CategoryFilter?,
        val tab: GalleryTab,
        val chosenIds: Set<Long>,
        val albumFilter: String?,
        val version: Int,
    )

    /** 메모리에서 거르는 조건 묶음(기간·백업·카테고리). combine 인자 수를 줄이기 위해 하나로 */
    private data class MemoryFilters(
        val range: DateRange?,
        val notBackedUpOnly: Boolean,
        val category: CategoryFilter?,
        val tab: GalleryTab,
        /** 숨긴 사진. 어느 탭·필터에서도 보이지 않는다 */
        val hidden: Set<Long>,
        /** 고른 사진. [GalleryTab.CHOSEN] 탭의 기준이자 하단바 토글의 상태 */
        val chosen: Set<Long>,
        /** 앨범 필터(`relativePath`) */
        val album: String?,
    )

    /**
     * 순서: 숨김 → 탭(출처) → 앨범 → 백업 → 카테고리 → 기간.
     * 숨김이 가장 바깥이다 — 탭을 바꾸거나 필터를 걸어도 숨긴 사진은 나오면 안 된다.
     *
     * 중간 단계 둘을 함께 돌려준다 — 앨범 목록은 숨김만 뺀 것([Triple.first])에서, 기간 달력(dayCounts)은
     * 기간 직전 목록([Triple.second])에서 센다.
     */
    private fun MemoryFilters.applyTo(
        all: List<MediaItem>,
        uploaded: Set<Long>,
        assignments: CategoryAssignments,
    ): Triple<List<MediaItem>, List<MediaItem>, List<MediaItem>> {
        val visible = if (hidden.isEmpty()) all else all.filterNot { it.id in hidden }
        val inTab = when (tab) {
            GalleryTab.ALL -> visible
            GalleryTab.CHOSEN -> visible.filter { it.id in chosen }
            else -> visible.filter { tab.matches(it) }
        }
        val inAlbum = album?.let { path -> inTab.filter { it.relativePath == path } } ?: inTab
        val pending = if (notBackedUpOnly) inAlbum.filter { it.id !in uploaded } else inAlbum
        val categorized = category?.let { c -> pending.filter { c.matches(assignments[it.id]) } } ?: pending
        return Triple(visible, categorized, categorized.filterByDate(range))
    }

    /** 목록 → 묶음·파생값. 백업 범위면 묶음이 곧 목록이다 */
    @Suppress("LongParameterList") // contentFlow 의 combine 이 주는 값 그대로
    private fun buildCatalog(
        all: List<MediaItem>,
        f: MemoryFilters,
        uploaded: Set<Long>,
        backup: BackupInputs?,
        categories: List<Category>,
        assignments: CategoryAssignments,
    ): Catalog {
        val (visible, categorized, filtered) = f.applyTo(all, uploaded, assignments)
        val sections = when {
            backup != null -> backupSections(backup, filtered, uploaded)
            // '다른 앱' 탭은 날짜 대신 앱으로 묶는다 — 카카오톡 사진 1000여 장이
            // 날짜순으로 흩어져 있으면 어느 앱 것인지 알아볼 수 없다
            f.tab == GalleryTab.OTHER -> groupByApp(filtered)
            else -> groupByDate(filtered)
        }
        // 백업 범위는 묶음이 곧 목록이다(올린 날짜순·까닭별) — 선택·넘기기가 화면 순서를 따른다
        val items = if (backup != null) sections.flatMap { it.items } else filtered
        latestItems = items
        latestChosenIds = f.chosen
        return Catalog(
            items = items,
            sections = sections,
            // 탭 적용 전 목록에서 뽑는다. 이동 대상 폴더까지 탭으로 걸리면
            // 카메라 탭에서 다른 폴더로 옮길 수 없다. 숨김은 제외한다 —
            // 숨긴 사진만 있는 폴더가 목록에 뜨면 있다는 사실이 새어 나간다
            albums = albumsFrom(visible),
            byId = items.associateBy { it.id },
            backupView = backup?.view,
            backupDestinations = backup?.let { b ->
                BackupDestinations.ordered(b.records.map { it.destination }, b.accounts, b.primaryEmail)
            }.orEmpty(),
            range = f.range,
            uploadedIds = uploaded,
            uploadedCount = items.count { it.id in uploaded },
            notBackedUpOnly = f.notBackedUpOnly,
            dayCounts = countByDay(categorized),
            categories = categories,
            assignments = assignments,
            categoryFilter = f.category,
            tab = f.tab,
            chosenIds = f.chosen,
            albumFilter = f.album,
            version = filterVersion,
        )
    }

    private fun contentFlow(status: MediaPermissionStatus, filter: MediaFilter): Flow<GalleryUiState> {
        // 기간·백업 필터는 메모리에서 걸러 MediaStore 를 다시 조회하지 않는다.
        // 원장(uploadedIds)은 업로드가 끝날 때만 바뀌므로 여기서 결합해도 선택 토글과 무관하다.
        val filters = combine(
            dateRange,
            notBackedUpOnly,
            categoryFilter,
            tab,
            combine(hiddenMedia.observeHiddenIds(), chosenMedia.observeChosenIds(), albumFilter, ::Triple),
        ) { range, pending, category, t, (hidden, chosen, album) ->
            MemoryFilters(range, pending, category, t, hidden, chosen, album)
        }
        val catalog = combine(
            mediaRepository.observeMedia(filter),
            filters,
            combine(uploadedIds, backupInputs, ::Pair),
            categoryRepository.observeCategories(),
            categoryRepository.observeAssignments(),
        ) { all, f, (uploaded, backup), categories, assignments ->
            buildCatalog(all, f, uploaded, backup, categories, assignments)
        }

        return combine(catalog, selectedIds, uploadSummary, actionController.isMutating, thumbnailDecorations) {
                c, selected, summary, mutating, decorations ->
            // 필터 변경 후 첫 목록은 애니메이션 없이 교체, 그 뒤(삭제·이동 등)부터 animateItem
            val animate = c.version == animatedVersion
            animatedVersion = c.version
            GalleryUiState.Content(
                sections = c.sections,
                itemCount = c.items.size,
                isPartialAccess = status == MediaPermissionStatus.Partial,
                selectedIds = selected,
                upload = summary,
                favoritesOnly = filter == MediaFilter.Favorites,
                dateRange = c.range,
                uploadedIds = c.uploadedIds,
                uploadedCount = c.uploadedCount,
                notBackedUpOnly = c.notBackedUpOnly,
                dayCounts = c.dayCounts,
                categories = c.categories,
                assignments = c.assignments,
                categoryFilter = c.categoryFilter,
                tab = c.tab,
                // 이름·대표 사진은 앨범 목록에서 찾는다. 사진을 다 옮겨 앨범이 사라지면 경로만 남는다
                albumFilter = c.albumFilter?.let { path ->
                    c.albums.find { it.relativePath == path } ?: Album(path.trimEnd('/'), path, itemCount = 0)
                },
                showCategoryBadges = decorations.categoryBadges,
                showBackedUpBadge = decorations.backedUpBadge,
                pendingIds = decorations.queue.pending,
                failedIds = decorations.queue.failed,
                cellSizeStep = decorations.cellSizeStep,
                albums = c.albums,
                backupView = c.backupView,
                backupDestinations = c.backupDestinations,
                supportsTrashAndFavorites = mediaRepository.supportsTrashAndFavorites,
                selectedAllFavorite = selected.isNotEmpty() && selected.all { c.byId[it]?.isFavorite == true },
                selectedAllChosen = selected.isNotEmpty() && selected.all { it in c.chosenIds },
                isMutating = mutating,
                animateItemChanges = animate,
            ) as GalleryUiState
        }
            .onStart { emit(GalleryUiState.Loading) }
            .catch { emit(GalleryUiState.Error(it)) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** 파일 이름 정리: 공백 제거, 경로 구분자 금지, 확장자 없으면 원본 확장자 유지. 비어 있으면 null. */
internal fun normalizeDisplayName(input: String, originalExtension: String): String? {
    val trimmed = input.trim().replace('/', '_')
    if (trimmed.isEmpty() || trimmed == ".") return null
    return if (!trimmed.contains('.') && originalExtension.isNotEmpty()) "$trimmed.$originalExtension" else trimmed
}

sealed interface GalleryUiState {
    data object Loading : GalleryUiState
    data object PermissionRequired : GalleryUiState
    data class Content(
        val sections: List<GallerySection>,
        val itemCount: Int,
        val isPartialAccess: Boolean,
        val selectedIds: Set<Long> = emptySet(),
        val upload: UploadSummary = UploadSummary(),
        val favoritesOnly: Boolean = false,
        val dateRange: DateRange? = null,
        /** Drive 에 올라간 항목 ID (배지 표시용) */
        val uploadedIds: Set<Long> = emptySet(),
        /** 현재 기간·즐겨찾기 조건 안에서 백업된 개수 */
        val uploadedCount: Int = 0,
        val notBackedUpOnly: Boolean = false,
        /** 기간 필터 이전 목록의 날짜별 개수(기간 선택 달력에서 사진 있는 날 표시) */
        val dayCounts: Map<LocalDate, Int> = emptyMap(),
        /** 사용자 카테고리(sortOrder 순, 항목 수 포함) */
        val categories: List<Category> = emptyList(),
        /** mediaId → 카테고리 ID. 썸네일 배지·피커 초기 상태 */
        val assignments: CategoryAssignments = emptyMap(),
        val categoryFilter: CategoryFilter? = null,
        /** 지금 보고 있는 출처 탭 */
        val tab: GalleryTab = GalleryTab.CHOSEN,
        /** 앨범 하나만 보고 있으면 그 앨범 */
        val albumFilter: Album? = null,
        val showCategoryBadges: Boolean = true,
        /** 올라간 것에 구름 ✓ — 기본 켬(2026-10-05 사용자 결정), 설정에서 끈다 */
        val showBackedUpBadge: Boolean = true,
        /** 큐에서 대기·올리는 중(구름 ↑) */
        val pendingIds: Set<Long> = emptySet(),
        /** 큐에서 실패(구름 ✕). 원장에 있으면 올라간 것이 앞선다 */
        val failedIds: Set<Long> = emptySet(),
        /** 칸 크기 단계(`CELL_SIZE_STEPS_DP`) */
        val cellSizeStep: Int = GALLERY_CELL_STEP_DEFAULT,
        val albums: List<Album> = emptyList(),
        /** 백업된 사진 화면이면 지금 세그먼트·저장소. 아니면 null */
        val backupView: BackupView? = null,
        /** 백업된 사진 화면의 저장소 칩(원장에 나오는 곳들) */
        val backupDestinations: List<BackupDestination> = emptyList(),
        val supportsTrashAndFavorites: Boolean = true,
        val selectedAllFavorite: Boolean = false,
        /** 선택이 전부 고른 사진이면 하단바 버튼이 "빼기" 가 된다 */
        val selectedAllChosen: Boolean = false,
        val isMutating: Boolean = false,
        /** false 면 그리드가 항목 이동/등장 애니메이션을 생략한다(필터 전환 직후) */
        val animateItemChanges: Boolean = true,
    ) : GalleryUiState {
        val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
    }
    data class Error(val throwable: Throwable) : GalleryUiState
}

/** 큐 상태로 정해지는 배지 대상 */
private data class QueueBadges(val pending: Set<Long> = emptySet(), val failed: Set<Long> = emptySet())

private data class ThumbnailDecorations(
    val categoryBadges: Boolean,
    val backedUpBadge: Boolean,
    val queue: QueueBadges,
    val cellSizeStep: Int,
)

/** 업로드 대상 후보(accountId null = Google Drive) */
data class UploadTargetOption(val accountId: String?, val name: String, val isDefault: Boolean)

private const val GOOGLE_DRIVE_LABEL = "Google Drive"

sealed interface GalleryEvent {
    data object SignInRequired : GalleryEvent

    /** 다른 계정 업로드 — 계정 선택 창을 띄워야 한다 */
    data class GuestChooser(val pendingIntent: PendingIntent) : GalleryEvent
    data class GuestStarted(val result: GuestUploadStarted) : GalleryEvent
    data object GuestIsPrimary : GalleryEvent
    data class Enqueued(val added: Int, val skipped: Int) : GalleryEvent

    data class CategoriesAssigned(val count: Int) : GalleryEvent
    data class Hidden(val count: Int) : GalleryEvent

    /** 고른 사진에 [count] 개를 넣었다([added]) 또는 뺐다 */
    data class Chosen(val count: Int, val added: Boolean) : GalleryEvent
    data class Error(val error: Throwable) : GalleryEvent
}
