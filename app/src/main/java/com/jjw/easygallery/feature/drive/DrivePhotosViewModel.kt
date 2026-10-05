package com.jjw.easygallery.feature.drive

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.DataSource
import coil3.ImageLoader
import com.jjw.easygallery.core.data.auth.AuthRepository
import com.jjw.easygallery.core.data.auth.AuthorizationRequiredException
import com.jjw.easygallery.core.data.download.DownloadScheduler
import com.jjw.easygallery.core.data.drive.DriveImages
import com.jjw.easygallery.core.data.drive.DriveMedia
import com.jjw.easygallery.core.data.drive.DriveMediaQuery
import com.jjw.easygallery.core.data.drive.DriveRepository
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.remote.StorageRegistry
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveMediaOrder
import com.jjw.easygallery.core.domain.model.DriveMediaScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Drive 사진 — 폴더를 가로질러 사진첩처럼 본다(`docs/plans/drive-photos/spec.md`).
 *
 * 목록은 서버가 올린 시각 최신순으로 한 쪽씩 준다. 찍은 날짜순은 서버가 못 해서 끝까지 읽어 기기에서 정렬한다.
 * "기기에 없음" 은 원장과 MediaStore 로 기기에서 거르고, 걸러서 남는 게 적으면 다음 쪽을 알아서 더 읽는다.
 */
@HiltViewModel
// TooGenericExceptionCaught: UI 경계 — 네트워크·API 오류를 모두 화면 상태로 바꾼다
@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
class DrivePhotosViewModel @Inject constructor(
    private val drive: DriveRepository,
    private val storages: StorageRegistry,
    private val prefs: UserPreferencesRepository,
    private val downloads: DownloadScheduler,
    private val auth: AuthRepository,
    ledger: UploadLedgerRepository,
    mediaRepository: MediaRepository,
    @param:DriveImages val driveImageLoader: ImageLoader,
    @param:DriveMedia val driveDataSourceFactory: DataSource.Factory,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DrivePhotosUiState>(DrivePhotosUiState.Loading)
    val uiState: StateFlow<DrivePhotosUiState> = _uiState.asStateFlow()

    private val events = Channel<DrivePhotosEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var fetchJob: Job? = null
    private var loaded = false

    /** 화면 상태가 아직 Loading 일 때 온 값도 잃지 않게 따로 쥔다 */
    private var latestOnDeviceIds: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            combine(
                ledger.observeRemoteToMedia(accountId = null),
                mediaRepository.observeMedia(MediaFilter.All).map { items -> items.mapTo(HashSet()) { it.id } },
            ) { remoteToMedia, deviceIds -> onDeviceRemoteIds(remoteToMedia, deviceIds) }
                // 사진 권한이 없으면 MediaStore 조회가 실패한다 — 전부 "기기에 없음" 으로 보인다
                .catch { e ->
                    Timber.w(e, "device media unavailable")
                    emit(emptySet())
                }
                .collect { ids ->
                    latestOnDeviceIds = ids
                    updateContent { it.copy(onDeviceIds = ids) }
                    fillFilteredIfSparse()
                }
        }
    }

    /** 처음 한 번. 연결된 계정이 없으면 안내만 보인다 */
    fun load() {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val p = prefs.current()
            if (!p.isSignedIn) {
                _uiState.value = DrivePhotosUiState.NotConnected
                return@launch
            }
            _uiState.value = DrivePhotosUiState.Content(
                viewScopeGranted = p.driveViewScopeGranted,
                accountEmail = p.accountEmail,
                onDeviceIds = latestOnDeviceIds,
            )
            loadFolders()
            reload()
        }
    }

    /** 범위 칩의 폴더들 — Drive 화면 루트와 같은 목록(지정 → 기본 → 보기 전용) */
    private suspend fun loadFolders() {
        val folders = runCatching {
            val storage = storages.storage(null)
            storage.listChildren(storage.rootId).entries.filter { it.isFolder }
        }.onFailure { Timber.w(it, "drive folders unavailable") }.getOrDefault(emptyList())
        updateContent { state ->
            state.copy(folders = folders.map { DriveMediaScope.Folder(it.id, it.name, readOnly = it.readOnly) })
        }
    }

    fun setScope(scope: DriveMediaScope) = changeQuery { it.copy(scope = scope) }

    fun setOrder(order: DriveMediaOrder) = changeQuery { it.copy(order = order) }

    /** 영상은 서버 조건이 바뀌어 다시 읽고, 기기에 없음은 읽은 것을 거르기만 한다 */
    fun setFilter(filter: DrivePhotoFilter) {
        val state = content() ?: return
        if (state.filter == filter) return
        val requery = (filter == DrivePhotoFilter.VIDEOS) != (state.filter == DrivePhotoFilter.VIDEOS)
        if (requery) {
            changeQuery { it.copy(filter = filter) }
        } else {
            updateContent { it.copy(filter = filter, selectedIds = emptySet()) }
            fillFilteredIfSparse()
        }
    }

    private fun changeQuery(transform: (DrivePhotosUiState.Content) -> DrivePhotosUiState.Content) {
        val state = content() ?: return
        val next = transform(state)
        if (next == state) return
        _uiState.value = next.copy(selectedIds = emptySet())
        reload()
    }

    fun refresh() {
        updateContent { it.copy(isRefreshing = true) }
        reload(keepEntries = true)
    }

    fun loadMore() {
        val state = content() ?: return
        if (state.nextPageToken == null || state.isLoading || state.isLoadingMore) return
        if (fetchJob?.isActive == true) return
        updateContent { it.copy(isLoadingMore = true) }
        fetchJob = viewModelScope.launch { fetchPage(state, state.nextPageToken) }
    }

    fun onAuthRecoveryResult(data: Intent?) {
        viewModelScope.launch {
            val ok = runCatching { auth.completeSignIn(data) }
                .onFailure { Timber.i(it, "auth recovery not completed") }
                .isSuccess
            if (ok) reload()
        }
    }

    private fun reload(keepEntries: Boolean = false) {
        val state = content() ?: return
        fetchJob?.cancel()
        updateContent {
            it.copy(
                entries = if (keepEntries) it.entries else emptyList(),
                nextPageToken = null,
                isLoading = !keepEntries,
                isLoadingMore = false,
                loadedCount = null,
                error = null,
                authRecovery = null,
            )
        }
        fetchJob = viewModelScope.launch {
            if (state.order == DriveMediaOrder.TAKEN) fetchAll(state) else fetchPage(state, pageToken = null)
        }
    }

    private suspend fun fetchPage(query: DrivePhotosUiState.Content, pageToken: String?) {
        try {
            val page = drive.listMedia(
                scope = query.scope,
                videosOnly = query.filter == DrivePhotoFilter.VIDEOS,
                viewScopeGranted = query.viewScopeGranted,
                pageToken = pageToken,
            )
            updateContent { state ->
                state.copy(
                    entries = if (pageToken == null) page.entries else state.entries.appendNew(page.entries),
                    nextPageToken = page.nextPageToken,
                    isLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                )
            }
            fillFilteredIfSparse()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFetchFailed(e, firstPage = pageToken == null)
        }
    }

    /** 찍은 날짜순 — 서버가 정렬하지 못해 끝까지 읽는다. 읽는 동안 몇 장째인지 보인다 */
    private suspend fun fetchAll(query: DrivePhotosUiState.Content) {
        try {
            val all = ArrayList<DriveEntry>()
            var token: String? = null
            do {
                val page = drive.listMedia(
                    scope = query.scope,
                    videosOnly = query.filter == DrivePhotoFilter.VIDEOS,
                    viewScopeGranted = query.viewScopeGranted,
                    pageToken = token,
                    pageSize = DriveMediaQuery.PAGE_SIZE_ALL,
                )
                all += page.entries
                token = page.nextPageToken
                updateContent { it.copy(loadedCount = all.size) }
            } while (token != null)
            updateContent {
                it.copy(
                    entries = all.distinctBy { e -> e.id }.sortedFor(DriveMediaOrder.TAKEN),
                    nextPageToken = null,
                    isLoading = false,
                    isRefreshing = false,
                    loadedCount = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFetchFailed(e, firstPage = true)
        }
    }

    private suspend fun onFetchFailed(e: Exception, firstPage: Boolean) {
        Timber.e(e, "drive media list failed")
        val keep = content()?.entries?.isNotEmpty() == true
        updateContent {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                loadedCount = null,
                // 이미 보이는 것이 있으면 화면을 오류로 덮지 않고 알림만 띄운다
                error = if (firstPage && !keep) e else null,
                authRecovery = (e as? AuthorizationRequiredException)?.pendingIntent,
            )
        }
        if (!firstPage || keep) events.send(DrivePhotosEvent.Error(e))
    }

    /** 기기에 없음으로 걸러서 한 화면도 안 차면 다음 쪽을 더 읽는다 — 스크롤할 것이 없으면 "더 읽기" 가 불리지 않는다 */
    private fun fillFilteredIfSparse() {
        val state = content() ?: return
        if (state.filter != DrivePhotoFilter.NOT_ON_DEVICE || state.nextPageToken == null) return
        if (state.visible.size >= SPARSE_THRESHOLD) return
        loadMore()
    }

    // ---- 고르기·받기·휴지통 ----

    fun toggleSelection(entry: DriveEntry) = updateContent {
        val ids = if (entry.id in it.selectedIds) it.selectedIds - entry.id else it.selectedIds + entry.id
        it.copy(selectedIds = ids)
    }

    fun selectAll() = updateContent { it.copy(selectedIds = it.visible.mapTo(HashSet()) { e -> e.id }) }

    fun clearSelection() = updateContent { it.copy(selectedIds = emptySet()) }

    fun download(entries: List<DriveEntry>) {
        if (entries.isEmpty()) return
        entries.forEach { downloads.enqueue(null, it) }
        clearSelection()
        viewModelScope.launch { events.send(DrivePhotosEvent.DownloadStarted(entries.size)) }
    }

    fun downloadSelected() = download(selectedEntries())

    fun trashSelected() = trash(selectedEntries())

    /** Drive 휴지통 — 되돌릴 수 있어 묻지 않고, 스낵바의 "실행 취소" 로 [restore] */
    fun trash(entries: List<DriveEntry>) {
        val state = content() ?: return
        if (entries.isEmpty() || state.scope.isReadOnly || state.isMutating) return
        val ids = entries.mapTo(HashSet()) { it.id }
        updateContent {
            it.copy(entries = it.entries.filterNot { e -> e.id in ids }, selectedIds = emptySet(), isMutating = true)
        }
        viewModelScope.launch {
            val failed = entries.filterNot { entry -> runQuietly { drive.setTrashed(entry.id, trashed = true) } }
            updateContent { it.copy(entries = it.entries.appendNew(failed).ordered(it.order), isMutating = false) }
            val done = entries - failed.toSet()
            if (done.isNotEmpty()) events.send(DrivePhotosEvent.Trashed(done))
            if (failed.isNotEmpty()) events.send(DrivePhotosEvent.Failed(failed.size))
        }
    }

    fun restore(entries: List<DriveEntry>) {
        if (entries.isEmpty()) return
        updateContent { it.copy(isMutating = true) }
        viewModelScope.launch {
            val restored = entries.filter { entry -> runQuietly { drive.setTrashed(entry.id, trashed = false) } }
            updateContent { it.copy(entries = it.entries.appendNew(restored).ordered(it.order), isMutating = false) }
            events.send(
                if (restored.size == entries.size) {
                    DrivePhotosEvent.Restored
                } else {
                    DrivePhotosEvent.Failed(entries.size - restored.size)
                },
            )
        }
    }

    private suspend fun runQuietly(action: suspend () -> Unit): Boolean = try {
        action()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "drive media mutation failed")
        false
    }

    /** 되살린 것을 제자리에 — 서버가 준 순서(올린 시각)로 다시 정렬한다 */
    private fun List<DriveEntry>.ordered(order: DriveMediaOrder): List<DriveEntry> = when (order) {
        DriveMediaOrder.UPLOADED -> sortedWith(
            compareByDescending<DriveEntry, Long?>(nullsFirst()) { it.sortTime(DriveMediaOrder.UPLOADED) },
        )
        DriveMediaOrder.TAKEN -> sortedFor(order)
    }

    private fun selectedEntries(): List<DriveEntry> {
        val state = content() ?: return emptyList()
        return state.visible.filter { it.id in state.selectedIds }
    }

    private fun content(): DrivePhotosUiState.Content? = _uiState.value as? DrivePhotosUiState.Content

    private fun updateContent(transform: (DrivePhotosUiState.Content) -> DrivePhotosUiState.Content) {
        _uiState.update { state -> if (state is DrivePhotosUiState.Content) transform(state) else state }
    }

    private companion object {
        /** 기기에 없음으로 걸렀을 때 이만큼은 채워 둔다(한 화면 남짓) */
        const val SPARSE_THRESHOLD = 60
    }
}

sealed interface DrivePhotosUiState {
    data object Loading : DrivePhotosUiState

    /** Google 계정이 연결돼 있지 않다 */
    data object NotConnected : DrivePhotosUiState

    data class Content(
        val scope: DriveMediaScope = DriveMediaScope.AppUploads,
        val order: DriveMediaOrder = DriveMediaOrder.UPLOADED,
        val filter: DrivePhotoFilter = DrivePhotoFilter.ALL,
        /** 범위 칩에 놓을 폴더들 */
        val folders: List<DriveMediaScope.Folder> = emptyList(),
        /** 읽은 것 전부(거르기 전). 순서는 [order] 대로 */
        val entries: List<DriveEntry> = emptyList(),
        val nextPageToken: String? = null,
        /** 이 기기에도 있는 Drive 파일 ID */
        val onDeviceIds: Set<String> = emptySet(),
        val selectedIds: Set<String> = emptySet(),
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val isMutating: Boolean = false,
        /** 찍은 날짜순으로 끝까지 읽는 중이면 지금까지 읽은 수 */
        val loadedCount: Int? = null,
        val error: Throwable? = null,
        /** 권한이 끊겨 재동의가 필요하면 띄울 인텐트 */
        val authRecovery: PendingIntent? = null,
        /** 읽기 권한을 옵트인했다 — "Drive 전체" 칩이 생기고 "앱이 올린 것" 은 표식으로 좁힌다 */
        val viewScopeGranted: Boolean = false,
        /** Drive 링크를 열 때 계정을 고르라고 묻지 않게 싣는다 */
        val accountEmail: String? = null,
    ) : DrivePhotosUiState {
        /** 화면에 보일 것 — 거르기를 적용했다 */
        val visible: List<DriveEntry> by lazy { entries.filteredBy(filter, onDeviceIds) }
        val cells: List<DrivePhotoCell> by lazy { visible.toCells(order) }
        val isSelecting: Boolean get() = selectedIds.isNotEmpty()

        /** 고칠 수 있는가 — 보기 전용 폴더·Drive 전체는 읽기 권한으로만 보인다 */
        val canTrash: Boolean get() = !scope.isReadOnly
    }
}

sealed interface DrivePhotosEvent {
    data class Trashed(val entries: List<DriveEntry>) : DrivePhotosEvent
    data object Restored : DrivePhotosEvent
    data class Failed(val count: Int) : DrivePhotosEvent
    data class DownloadStarted(val count: Int) : DrivePhotosEvent
    data class Error(val error: Throwable) : DrivePhotosEvent
}
