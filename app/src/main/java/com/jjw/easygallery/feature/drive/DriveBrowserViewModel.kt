package com.jjw.easygallery.feature.drive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.drive.DriveRepository
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveFolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
@Suppress("TooGenericExceptionCaught") // UI 경계: 네트워크·API 오류를 모두 메시지로 보여준다
class DriveBrowserViewModel @Inject constructor(
    private val drive: DriveRepository,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DriveBrowserUiState())
    val uiState: StateFlow<DriveBrowserUiState> = _uiState.asStateFlow()

    private val events = Channel<DriveBrowserEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var loaded = false

    /** NavEntry 키의 폴더로 초기화. 재구성마다 호출돼도 한 번만 로드한다. */
    fun load(folder: DriveFolder) {
        if (loaded) return
        loaded = true
        _uiState.update { it.copy(current = folder, isLoading = true, error = null) }
        fetchPage(reset = true)
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, error = null) }
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

    /** 확인 없이 휴지통으로. 스낵바 "실행 취소" → [restore] */
    fun trash(entry: DriveEntry) {
        mutate(
            optimistic = { list -> list.filterNot { it.id == entry.id } },
            action = { drive.setTrashed(entry.id, trashed = true) },
            onSuccess = { DriveBrowserEvent.Trashed(entry) },
        )
    }

    fun restore(entry: DriveEntry) {
        mutate(
            optimistic = { list -> (list + entry).sortedForDrive() },
            action = { drive.setTrashed(entry.id, trashed = false) },
            onSuccess = { DriveBrowserEvent.Restored },
        )
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
            prefs.setUploadFolder(current.id, current.name)
            events.send(DriveBrowserEvent.UploadFolderSelected(current))
        }
    }

    private fun fetchPage(reset: Boolean) {
        val current = _uiState.value.current ?: return
        val token = if (reset) null else _uiState.value.nextPageToken
        viewModelScope.launch {
            try {
                val page = drive.listChildren(current.id, token)
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

data class DriveBrowserUiState(
    val current: DriveFolder? = null,
    val entries: List<DriveEntry> = emptyList(),
    val nextPageToken: String? = null,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isMutating: Boolean = false,
    val error: String? = null,
)

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
    data object Restored : DriveBrowserEvent
    data class UploadFolderSelected(val folder: DriveFolder) : DriveBrowserEvent
    data class Error(val message: String) : DriveBrowserEvent
}
