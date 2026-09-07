package com.jjw.easygallery.feature.folderpicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.drive.DriveRepository
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
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
class FolderPickerViewModel @Inject constructor(
    private val drive: DriveRepository,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderPickerUiState())
    val uiState: StateFlow<FolderPickerUiState> = _uiState.asStateFlow()

    private val events = Channel<FolderPickerEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var loaded = false

    /** NavEntry 키에서 받은 부모 폴더로 초기화. 재구성마다 호출돼도 한 번만 로드한다. */
    fun load(parentId: String?, parentName: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val current = if (parentId == null) {
                    drive.ensureAppRootFolder()
                } else {
                    DriveFolder(parentId, parentName ?: "")
                }
                val children = drive.listFolders(current.id)
                _uiState.update { it.copy(current = current, folders = children, isLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "folder load failed")
                _uiState.update { it.copy(isLoading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun refresh() {
        val current = _uiState.value.current ?: return
        loaded = false
        load(current.id, current.name)
    }

    fun createFolder(name: String) {
        val current = _uiState.value.current ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val created = drive.createFolder(trimmed, current.id)
                _uiState.update { state ->
                    state.copy(folders = (state.folders + created).sortedBy { it.name }, isLoading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "create folder failed")
                _uiState.update { it.copy(isLoading = false) }
                events.send(FolderPickerEvent.Error(e.message ?: e.toString()))
            }
        }
    }

    fun selectCurrent() {
        val current = _uiState.value.current ?: return
        viewModelScope.launch {
            prefs.setUploadFolder(current.id, current.name)
            events.send(FolderPickerEvent.Selected(current))
        }
    }
}

data class FolderPickerUiState(
    val current: DriveFolder? = null,
    val folders: List<DriveFolder> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

sealed interface FolderPickerEvent {
    data class Selected(val folder: DriveFolder) : FolderPickerEvent
    data class Error(val message: String) : FolderPickerEvent
}
