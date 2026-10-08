package com.jjw.easygallery.feature.drive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.DataSource
import coil3.ImageLoader
import com.jjw.easygallery.core.data.drive.DriveImages
import com.jjw.easygallery.core.data.drive.DriveMedia
import com.jjw.easygallery.core.data.drive.DriveRepository
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.domain.model.DriveEntry
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

/** [DriveFileViewer] — Drive 파일 하나를 읽고, 휴지통·되돌리기를 한다 */
@HiltViewModel
// TooGenericExceptionCaught: UI 경계 — 404·403·네트워크 오류를 모두 화면 상태로
@Suppress("TooGenericExceptionCaught")
class DriveFileViewModel @Inject constructor(
    private val drive: DriveRepository,
    private val prefs: UserPreferencesRepository,
    @param:DriveImages val driveImageLoader: ImageLoader,
    @param:DriveMedia val driveDataSourceFactory: DataSource.Factory,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DriveFileUiState>(DriveFileUiState.Loading)
    val uiState: StateFlow<DriveFileUiState> = _uiState.asStateFlow()

    private val channel = Channel<DriveFileEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    private var loadedId: String? = null

    fun load(fileId: String) {
        if (loadedId == fileId) return
        loadedId = fileId
        viewModelScope.launch {
            _uiState.value = try {
                val entry = drive.getMediaFile(fileId)
                // 다른 계정으로 올려 공유받은 파일은 보이기만 한다 — 휴지통으로 보내면 403(2026-10-08 기기의 cal.png)
                val owner = entry.ownerEmail
                val mine = owner == null || owner.equals(prefs.current().accountEmail, ignoreCase = true)
                DriveFileUiState.Loaded(entry, canTrash = mine)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 다른 계정으로 올린 파일은 지금 계정에 404 다. 까닭을 가리지 않고 같은 안내 — 할 수 있는 일이 Drive 앱뿐이다
                Timber.w(e, "drive file unreadable")
                DriveFileUiState.Unreadable
            }
        }
    }

    fun trash() = mutate(trashed = true)

    fun restore() = mutate(trashed = false)

    private fun mutate(trashed: Boolean) {
        val loaded = _uiState.value as? DriveFileUiState.Loaded ?: return
        viewModelScope.launch {
            try {
                drive.setTrashed(loaded.entry.id, trashed)
                _uiState.update { (it as? DriveFileUiState.Loaded)?.copy(trashed = trashed) ?: it }
                channel.send(if (trashed) DriveFileEvent.Trashed(loaded.entry.name) else DriveFileEvent.Restored)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "drive file trash failed")
                channel.send(DriveFileEvent.Error(e))
            }
        }
    }
}

sealed interface DriveFileUiState {
    data object Loading : DriveFileUiState
    data object Unreadable : DriveFileUiState

    /** [trashed] 면 휴지통으로 보낸 뒤 — 실행 취소를 기다린다(휴지통 단추를 숨긴다) */
    data class Loaded(val entry: DriveEntry, val canTrash: Boolean = true, val trashed: Boolean = false) :
        DriveFileUiState
}

sealed interface DriveFileEvent {
    data class Trashed(val name: String) : DriveFileEvent
    data object Restored : DriveFileEvent
    data class Error(val error: Throwable) : DriveFileEvent
}
