package com.jjw.easygallery.feature.gallery

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.auth.AuthException
import com.jjw.easygallery.core.data.media.ActionOutcome
import com.jjw.easygallery.core.data.media.MediaAction
import com.jjw.easygallery.core.data.media.MediaActionRunner
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.domain.model.Album
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.UploadSummary
import com.jjw.easygallery.core.domain.model.albumsFrom
import com.jjw.easygallery.core.domain.usecase.EnqueueUploadsUseCase
import com.jjw.easygallery.core.domain.usecase.ManageUploadQueueUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
@Suppress("TooGenericExceptionCaught") // UI 경계: 편집·큐 등록 실패는 종류를 가리지 않고 메시지로 보여준다
class GalleryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    uploadQueue: UploadQueueRepository,
    private val enqueueUploads: EnqueueUploadsUseCase,
    private val manageQueue: ManageUploadQueueUseCase,
    private val actionRunner: MediaActionRunner,
) : ViewModel() {

    // null = 아직 권한 상태를 확인하지 않음
    private val permissionStatus = MutableStateFlow<MediaPermissionStatus?>(null)
    private val filter = MutableStateFlow(MediaFilter.All)
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val isMutating = MutableStateFlow(false)
    private val uploadSummary: Flow<UploadSummary> = uploadQueue.observeSummary()
    private val events = Channel<GalleryEvent>(Channel.BUFFERED)
    val eventFlow: Flow<GalleryEvent> = events.receiveAsFlow()

    private var latestItems: List<MediaItem> = emptyList()

    /** 시스템 동의 다이얼로그가 떠 있는 동안 보류된 액션 */
    private var pendingAction: MediaAction? = null

    val uiState: StateFlow<GalleryUiState> = combine(permissionStatus, filter) { status, f -> status to f }
        .flatMapLatest { (status, f) -> stateFor(status, f) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = GalleryUiState.Loading,
        )

    /** UI 가 권한을 확인·요청한 결과를 알려준다. 화면 복귀 시마다 호출되어도 안전(StateFlow 중복 제거). */
    fun onPermissionStatusChanged(status: MediaPermissionStatus) {
        permissionStatus.value = status
    }

    fun setFavoritesOnly(enabled: Boolean) {
        clearSelection()
        filter.value = if (enabled) MediaFilter.Favorites else MediaFilter.All
    }

    fun toggleSelection(id: Long) {
        selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    /** 드래그 범위 선택 결과를 통째로 반영 */
    fun setSelection(ids: Set<Long>) {
        selectedIds.value = ids
    }

    // ---------- 업로드 ----------

    /** 선택 항목을 업로드 큐에 넣는다. 실제 전송은 WorkManager 가 백그라운드에서 수행. */
    fun uploadSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            try {
                val added = enqueueUploads(items)
                clearSelection()
                events.send(GalleryEvent.Enqueued(added = added, skipped = items.size - added))
            } catch (e: AuthException) {
                Timber.w(e, "upload needs sign-in")
                events.send(GalleryEvent.SignInRequired)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "enqueue failed")
                events.send(GalleryEvent.Error(e.message ?: e.toString()))
            }
        }
    }

    fun cancelUploads() {
        viewModelScope.launch { manageQueue.cancelAll() }
    }

    // ---------- 편집 ----------

    fun deleteSelected() = perform(MediaAction.Delete(selectedItems()))

    fun trashSelected() = perform(MediaAction.Trash(selectedItems(), trashed = true))

    /** 선택이 모두 즐겨찾기면 해제, 아니면 전부 즐겨찾기 */
    fun toggleFavoriteSelected() {
        val items = selectedItems()
        perform(MediaAction.Favorite(items, favorite = !items.all { it.isFavorite }))
    }

    /** 확장자를 안 적으면 원본 확장자를 유지한다. */
    fun renameSelected(newName: String) {
        val item = selectedItems().singleOrNull() ?: return
        val normalized = normalizeDisplayName(newName, item.extension) ?: return
        if (normalized == item.displayName) return
        perform(MediaAction.Rename(item, normalized))
    }

    fun moveSelected(relativePath: String) = perform(MediaAction.Move(selectedItems(), relativePath))

    /** 동의 다이얼로그 결과 */
    fun onConsentResult(granted: Boolean) {
        val action = pendingAction ?: return
        pendingAction = null
        if (!granted) {
            isMutating.value = false
            viewModelScope.launch { events.send(GalleryEvent.ActionCancelled) }
            return
        }
        viewModelScope.launch { runGuarded { actionRunner.afterConsent(action) } }
    }

    private fun perform(action: MediaAction) {
        if (action.items.isEmpty() || isMutating.value) return
        viewModelScope.launch { runGuarded { actionRunner.run(action) } }
    }

    private suspend fun runGuarded(block: suspend () -> ActionOutcome) {
        isMutating.value = true
        try {
            when (val outcome = block()) {
                is ActionOutcome.NeedsConsent -> {
                    pendingAction = outcome.action
                    events.send(GalleryEvent.LaunchConsent(outcome.intentSender))
                    // 동의 결과가 올 때까지 isMutating 유지
                    return
                }
                is ActionOutcome.Done -> {
                    clearSelection()
                    events.send(GalleryEvent.ActionDone(outcome.action, outcome.affected))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "media action failed")
            events.send(GalleryEvent.Error(e.message ?: e.toString()))
        }
        isMutating.value = false
    }

    private fun selectedItems(): List<MediaItem> = latestItems.filter { it.id in selectedIds.value }

    private fun stateFor(status: MediaPermissionStatus?, filter: MediaFilter): Flow<GalleryUiState> = when (status) {
        null -> flowOf(GalleryUiState.Loading)
        MediaPermissionStatus.Denied -> flowOf(GalleryUiState.PermissionRequired)
        MediaPermissionStatus.Full, MediaPermissionStatus.Partial -> contentFlow(status, filter)
    }

    private fun contentFlow(status: MediaPermissionStatus, filter: MediaFilter): Flow<GalleryUiState> {
        val media = mediaRepository.observeMedia(filter).onEach { latestItems = it }
        return combine(media, selectedIds, uploadSummary, isMutating) { items, selected, summary, mutating ->
            GalleryUiState.Content(
                sections = groupByDate(items),
                itemCount = items.size,
                isPartialAccess = status == MediaPermissionStatus.Partial,
                selectedIds = selected,
                upload = summary,
                favoritesOnly = filter == MediaFilter.Favorites,
                albums = albumsFrom(items),
                supportsTrashAndFavorites = mediaRepository.supportsTrashAndFavorites,
                selectedAllFavorite = selected.isNotEmpty() && items.filter { it.id in selected }.all { it.isFavorite },
                isMutating = mutating,
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
        val albums: List<Album> = emptyList(),
        val supportsTrashAndFavorites: Boolean = true,
        val selectedAllFavorite: Boolean = false,
        val isMutating: Boolean = false,
    ) : GalleryUiState {
        val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
    }
    data class Error(val throwable: Throwable) : GalleryUiState
}

sealed interface GalleryEvent {
    data object SignInRequired : GalleryEvent
    data class Enqueued(val added: Int, val skipped: Int) : GalleryEvent
    data class LaunchConsent(val intentSender: IntentSender) : GalleryEvent
    data class ActionDone(val action: MediaAction, val affected: Int) : GalleryEvent
    data object ActionCancelled : GalleryEvent
    data class Error(val message: String) : GalleryEvent
}
