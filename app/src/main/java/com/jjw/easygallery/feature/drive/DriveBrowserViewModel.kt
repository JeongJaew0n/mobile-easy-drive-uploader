package com.jjw.easygallery.feature.drive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.download.DownloadScheduler
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.remote.MutationProgress
import com.jjw.easygallery.core.data.remote.RemoteStorage
import com.jjw.easygallery.core.data.remote.ReportsMutationProgress
import com.jjw.easygallery.core.data.remote.StorageRegistry
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.domain.model.Capability
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveFolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
// TooGenericExceptionCaught: UI 경계, 네트워크·API 오류를 모두 메시지로. TooManyFunctions: 단일 항목 CRUD + 다중 선택 일괄 작업이
// 한 화면의 액션이라 한 ViewModel 에 둔다(나누면 선택 상태를 둘이 공유해야 한다)
@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
class DriveBrowserViewModel @Inject constructor(
    private val storages: StorageRegistry,
    private val prefs: UserPreferencesRepository,
    private val downloads: DownloadScheduler,
    private val ledger: UploadLedgerRepository,
) : ViewModel() {

    private lateinit var drive: RemoteStorage
    private var accountId: String? = null

    private val _uiState = MutableStateFlow(DriveBrowserUiState())
    val uiState: StateFlow<DriveBrowserUiState> = _uiState.asStateFlow()

    private val events = Channel<DriveBrowserEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var loaded = false
    private var searchJob: Job? = null
    private var unfilteredEntries: List<DriveEntry>? = null

    /**
     * NavEntry 키의 계정·폴더로 초기화. 재구성마다 호출돼도 한 번만 로드한다.
     * [folderId] null 이면 그 저장소의 루트, [rootName] 은 루트 표시 이름.
     */
    fun load(accountId: String?, folderId: String?, folderName: String?, rootName: String) {
        if (loaded) return
        loaded = true
        this.accountId = accountId
        viewModelScope.launch {
            try {
                drive = storages.storage(accountId)
                val folder = DriveFolder(folderId ?: drive.rootId, folderName ?: rootName)
                _uiState.update {
                    it.copy(
                        current = folder,
                        isLoading = true,
                        error = null,
                        capabilities = drive.capabilities,
                        accountName = drive.account.displayName,
                    )
                }
                observeMutationProgress()
                observeUploadedFromDevice()
                fetchPage(reset = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "storage unavailable")
                _uiState.update { it.copy(isLoading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /** 이 기기에서 이 계정으로 올린 파일 ID — 행에 "이 기기에서 올림" 표시 */
    private fun observeUploadedFromDevice() {
        viewModelScope.launch {
            ledger.observeRemoteIds(accountId).collect { ids ->
                _uiState.update { it.copy(uploadedFromDeviceIds = ids) }
            }
        }
    }

    /** S3 처럼 폴더 변경이 오브젝트 단위로 진행되는 제공자면 "n / total" 을 상태에 흘린다 */
    private fun observeMutationProgress() {
        val reporter = drive as? ReportsMutationProgress ?: return
        viewModelScope.launch {
            reporter.mutationProgress.collect { progress -> _uiState.update { it.copy(mutationProgress = progress) } }
        }
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        fetchPage(reset = true)
    }

    // ---- 검색(`docs/DRIVE_FILE_CRUD.md` §8) ----

    private val remoteSearch: Boolean get() = Capability.SEARCH in _uiState.value.capabilities

    /** 검색 모드. SEARCH 능력이 없는 저장소는 현재 폴더 목록을 로컬에서 거른다(`unfilteredEntries` 보관) */
    fun startSearch() {
        if (!remoteSearch) unfilteredEntries = _uiState.value.entries
        _uiState.update { it.copy(searchQuery = "", selectedIds = emptySet()) }
    }

    /** 입력마다 호출 — 원격 검색은 [SEARCH_DEBOUNCE_MILLIS] 뒤 조회, 로컬 필터는 즉시. 빈 문자열은 결과를 비운다 */
    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query, selectedIds = emptySet()) }
        searchJob?.cancel()
        if (!remoteSearch) {
            val source = unfilteredEntries ?: emptyList()
            _uiState.update {
                it.copy(entries = if (query.isBlank()) source else source.filter { e -> e.name.contains(query, true) })
            }
            return
        }
        if (query.isBlank()) {
            _uiState.update { it.copy(entries = emptyList(), nextPageToken = null, isLoading = false, error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            _uiState.update { it.copy(isLoading = true, error = null) }
            fetchPage(reset = true)
        }
    }

    /** 검색 종료 → 원래 폴더 목록으로(로컬 필터였으면 보관한 목록 그대로, 원격이면 다시 읽음) */
    fun exitSearch() {
        searchJob?.cancel()
        val kept = unfilteredEntries
        unfilteredEntries = null
        if (kept != null) {
            _uiState.update { it.copy(searchQuery = null, entries = kept) }
            return
        }
        _uiState.update { it.copy(searchQuery = null, entries = emptyList(), nextPageToken = null, isLoading = true) }
        fetchPage(reset = true)
    }

    /** 목록 끝에 닿았을 때 다음 페이지 */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || state.nextPageToken == null) return
        _uiState.update { it.copy(isLoadingMore = true) }
        fetchPage(reset = false)
    }

    fun createFolder(name: String) {
        val current = _uiState.value.current ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isMutating = true) }
            try {
                val created = drive.createFolder(trimmed, current.id)
                val entry = DriveEntry(
                    id = created.id,
                    name = created.name,
                    mimeType = DriveEntry.FOLDER_MIME_TYPE,
                    sizeBytes = null,
                    modifiedTimeMillis = System.currentTimeMillis(),
                    webViewLink = null,
                )
                _uiState.update { state ->
                    // 폴더 먼저·이름순 정렬을 유지하며 삽입
                    state.copy(entries = (state.entries + entry).sortedForDrive(), isMutating = false)
                }
                events.send(DriveBrowserEvent.FolderCreated(created))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "create folder failed")
                _uiState.update { it.copy(isMutating = false) }
                events.send(DriveBrowserEvent.Error(e.message ?: e.toString()))
            }
        }
    }

    /** 이동 대상 선택기용: [parentId] 의 하위 폴더 전부(페이지 이어서) */
    suspend fun listFolders(parentId: String): List<DriveFolder> {
        val result = ArrayList<DriveFolder>()
        var token: String? = null
        do {
            val page = drive.listChildren(parentId, token, foldersOnly = true)
            result += page.entries.map { it.toFolder() }
            token = page.nextPageToken
        } while (token != null)
        return result
    }

    fun rename(entry: DriveEntry, newName: String) {
        val name = newName.trim()
        if (name.isEmpty() || name == entry.name) return
        mutate(
            optimistic = { list -> list.replaceSorted(entry.id) { it.copy(name = name) } },
            action = { drive.rename(entry.id, name) },
            onSuccess = { DriveBrowserEvent.Renamed(name) },
        )
    }

    fun move(entry: DriveEntry, target: DriveFolder) {
        val current = _uiState.value.current ?: return
        if (target.id == current.id) return
        mutate(
            optimistic = { list -> list.filterNot { it.id == entry.id } },
            action = { drive.move(entry.id, fromParentId = current.id, toParentId = target.id) },
            onSuccess = { DriveBrowserEvent.Moved(entry, target) },
        )
    }

    /** 휴지통이 있는 저장소면 확인 없이 휴지통으로(스낵바 "실행 취소" → [restore]), 없으면 영구 삭제(UI 가 먼저 확인) */
    fun trash(entry: DriveEntry) {
        val hasTrash = Capability.TRASH in _uiState.value.capabilities
        mutate(
            optimistic = { list -> list.filterNot { it.id == entry.id } },
            action = { drive.delete(entry.id) },
            onSuccess = { if (hasTrash) DriveBrowserEvent.Trashed(entry) else DriveBrowserEvent.Deleted(entry) },
        )
    }

    fun restore(entry: DriveEntry) {
        mutate(
            optimistic = { list -> (list + entry).sortedForDrive() },
            action = { drive.restore(entry.id) },
            onSuccess = { DriveBrowserEvent.Restored },
        )
    }

    // ---- 다운로드(`docs/DRIVE_FILE_CRUD.md` §9) ----

    fun download(entry: DriveEntry) = downloadAll(listOf(entry))

    fun downloadSelected() {
        downloadAll(selectedEntries())
        clearSelection()
    }

    /** 폴더는 건너뛰고 파일마다 워크 하나. 실제 진행은 알림에서 */
    private fun downloadAll(entries: List<DriveEntry>) {
        val files = entries.filterNot { it.isFolder }
        if (files.isEmpty()) return
        files.forEach { downloads.enqueue(accountId, it) }
        viewModelScope.launch { events.send(DriveBrowserEvent.DownloadStarted(files.size)) }
    }

    // ---- 다중 선택(`docs/DRIVE_FILE_CRUD.md` §7) ----

    fun toggleSelection(entry: DriveEntry) = _uiState.update {
        val ids = if (entry.id in it.selectedIds) it.selectedIds - entry.id else it.selectedIds + entry.id
        it.copy(selectedIds = ids)
    }

    fun selectAll() = _uiState.update { it.copy(selectedIds = it.entries.map { e -> e.id }.toSet()) }

    fun clearSelection() = _uiState.update { it.copy(selectedIds = emptySet()) }

    /** 선택 항목을 휴지통(없으면 영구 삭제)으로 — 하나씩 처리하고 실패한 것은 목록에 남긴다 */
    fun trashSelected() {
        val targets = selectedEntries()
        if (targets.isEmpty()) return
        val hasTrash = Capability.TRASH in _uiState.value.capabilities
        mutateBatch(
            targets = targets,
            action = { drive.delete(it.id) },
            onSuccess = { done -> DriveBrowserEvent.BatchTrashed(done, isTrash = hasTrash) },
        )
    }

    /** 선택 항목을 [target] 으로 — 선택된 폴더 자신·현재 폴더는 거부 */
    fun moveSelected(target: DriveFolder) {
        val current = _uiState.value.current ?: return
        val targets = selectedEntries()
        if (targets.isEmpty() || target.id == current.id) return
        if (targets.any { it.isFolder && it.id == target.id }) {
            viewModelScope.launch { events.send(DriveBrowserEvent.Error(MOVE_INTO_SELF_MESSAGE)) }
            return
        }
        mutateBatch(
            targets = targets,
            action = { drive.move(it.id, fromParentId = current.id, toParentId = target.id) },
            onSuccess = { done -> DriveBrowserEvent.BatchMoved(done.size, target) },
        )
    }

    /** 일괄 휴지통의 "실행 취소" */
    fun restoreAll(entries: List<DriveEntry>) {
        if (entries.isEmpty()) return
        if (_uiState.value.isMutating) return
        _uiState.update { it.copy(entries = (it.entries + entries).sortedForDrive(), isMutating = true) }
        viewModelScope.launch {
            val failed = ArrayList<DriveEntry>()
            entries.forEachIndexed { index, entry ->
                _uiState.update { it.copy(mutationProgress = MutationProgress(index, entries.size)) }
                runCatching { drive.restore(entry.id) }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Timber.e(e, "restore failed %s", entry.id)
                    failed += entry
                }
            }
            _uiState.update { state ->
                state.copy(
                    entries = state.entries.filterNot { e -> failed.any { f -> f.id == e.id } },
                    isMutating = false,
                    mutationProgress = null,
                )
            }
            events.send(
                if (failed.isEmpty()) DriveBrowserEvent.Restored else DriveBrowserEvent.BatchFailed(failed.size),
            )
        }
    }

    private fun selectedEntries(): List<DriveEntry> {
        val state = _uiState.value
        return state.entries.filter { it.id in state.selectedIds }
    }

    /**
     * 선택 항목을 낙관적으로 목록에서 빼고 하나씩 [action]. 실패한 항목은 다시 목록에 넣고
     * 성공 건수로 [onSuccess] 이벤트, 실패가 있으면 [DriveBrowserEvent.BatchFailed] 를 덧붙인다.
     */
    private fun mutateBatch(
        targets: List<DriveEntry>,
        action: suspend (DriveEntry) -> Unit,
        onSuccess: (done: List<DriveEntry>) -> DriveBrowserEvent,
    ) {
        if (_uiState.value.isMutating) return
        val ids = targets.map { it.id }.toSet()
        _uiState.update {
            it.copy(entries = it.entries.filterNot { e -> e.id in ids }, selectedIds = emptySet(), isMutating = true)
        }
        viewModelScope.launch {
            val done = ArrayList<DriveEntry>()
            val failed = ArrayList<DriveEntry>()
            targets.forEachIndexed { index, entry ->
                _uiState.update { it.copy(mutationProgress = MutationProgress(index, targets.size)) }
                runCatching { action(entry) }
                    .onSuccess { done += entry }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Timber.e(e, "batch mutation failed %s", entry.id)
                        failed += entry
                    }
            }
            _uiState.update { state ->
                state.copy(
                    entries = (state.entries + failed).sortedForDrive(),
                    isMutating = false,
                    mutationProgress = null,
                )
            }
            if (done.isNotEmpty()) events.send(onSuccess(done))
            if (failed.isNotEmpty()) events.send(DriveBrowserEvent.BatchFailed(failed.size))
        }
    }

    /** 낙관적 갱신 → API → 실패 시 원래 목록으로 되돌리고 오류 이벤트 */
    private fun mutate(
        optimistic: (List<DriveEntry>) -> List<DriveEntry>,
        action: suspend () -> Unit,
        onSuccess: () -> DriveBrowserEvent,
    ) {
        if (_uiState.value.isMutating) return
        val before = _uiState.value.entries
        _uiState.update { it.copy(entries = optimistic(it.entries), isMutating = true) }
        viewModelScope.launch {
            try {
                action()
                _uiState.update { it.copy(isMutating = false) }
                events.send(onSuccess())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "drive mutation failed")
                _uiState.update { it.copy(entries = before, isMutating = false) }
                events.send(DriveBrowserEvent.Error(e.message ?: e.toString()))
            }
        }
    }

    fun selectAsUploadFolder() {
        val current = _uiState.value.current ?: return
        viewModelScope.launch {
            prefs.setUploadTarget(accountId, current.id, current.name)
            events.send(DriveBrowserEvent.UploadFolderSelected(current))
        }
    }

    private fun fetchPage(reset: Boolean) {
        val current = _uiState.value.current ?: return
        val token = if (reset) null else _uiState.value.nextPageToken
        viewModelScope.launch {
            try {
                val query = _uiState.value.searchQuery
                val page = if (query != null) drive.search(query, token) else drive.listChildren(current.id, token)
                _uiState.update { state ->
                    state.copy(
                        entries = if (reset) page.entries else state.entries + page.entries,
                        nextPageToken = page.nextPageToken,
                        isLoading = false,
                        isLoadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "drive list failed")
                val message = e.message ?: e.toString()
                _uiState.update {
                    it.copy(isLoading = false, isLoadingMore = false, error = if (reset) message else null)
                }
                if (!reset) events.send(DriveBrowserEvent.Error(e.message ?: e.toString()))
            }
        }
    }
}

private const val MOVE_INTO_SELF_MESSAGE = "폴더를 자기 자신 안으로 옮길 수 없습니다"
private const val SEARCH_DEBOUNCE_MILLIS = 350L

data class DriveBrowserUiState(
    val current: DriveFolder? = null,
    val entries: List<DriveEntry> = emptyList(),
    val nextPageToken: String? = null,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isMutating: Boolean = false,
    /** 길게 눌러 고른 항목. 비어 있지 않으면 선택 모드 */
    val selectedIds: Set<String> = emptySet(),
    /** null 이면 폴더 탐색, 아니면 검색 모드(빈 문자열 = 입력 대기). 원격 검색 결과는 부모를 모르므로 이동 불가 */
    val searchQuery: String? = null,
    /** 이 기기에서 이 계정으로 올린 원격 파일 ID(업로드 원장) */
    val uploadedFromDeviceIds: Set<String> = emptySet(),
    /** 변경 중 오브젝트 단위 진행(S3 폴더 이름 변경·이동·삭제). null 이면 불확정 진행바 */
    val mutationProgress: MutationProgress? = null,
    val error: String? = null,
    /** 저장소가 지원하는 동작 — 메뉴 구성에 쓴다 */
    val capabilities: Set<Capability> = emptySet(),
    /** 상단 부제에 보이는 저장소 이름(Google Drive / 사용자가 정한 이름) */
    val accountName: String? = null,
) {
    val isSelecting: Boolean get() = selectedIds.isNotEmpty()
    val isSearching: Boolean get() = searchQuery != null

    /** 원격 검색 결과(부모 미상)에서만 이동을 막는다. 로컬 필터는 같은 폴더라 이동 가능 */
    val isRemoteSearchResult: Boolean get() = isSearching && Capability.SEARCH in capabilities
}

/** 폴더 먼저, 이름순(대소문자 무시) — Drive 목록 정렬(`folder,name_natural`)과 맞춘다 */
internal fun List<DriveEntry>.sortedForDrive(): List<DriveEntry> =
    sortedWith(compareBy<DriveEntry> { !it.isFolder }.thenBy { it.name.lowercase() })

private inline fun List<DriveEntry>.replaceSorted(id: String, transform: (DriveEntry) -> DriveEntry): List<DriveEntry> =
    map { if (it.id == id) transform(it) else it }.sortedForDrive()

sealed interface DriveBrowserEvent {
    data class FolderCreated(val folder: DriveFolder) : DriveBrowserEvent
    data class Renamed(val name: String) : DriveBrowserEvent
    data class Moved(val entry: DriveEntry, val target: DriveFolder) : DriveBrowserEvent
    data class Trashed(val entry: DriveEntry) : DriveBrowserEvent
    data class Deleted(val entry: DriveEntry) : DriveBrowserEvent
    data object Restored : DriveBrowserEvent
    data class BatchTrashed(val entries: List<DriveEntry>, val isTrash: Boolean) : DriveBrowserEvent
    data class BatchMoved(val count: Int, val target: DriveFolder) : DriveBrowserEvent
    data class BatchFailed(val count: Int) : DriveBrowserEvent
    data class DownloadStarted(val count: Int) : DriveBrowserEvent
    data class UploadFolderSelected(val folder: DriveFolder) : DriveBrowserEvent
    data class Error(val message: String) : DriveBrowserEvent
}
