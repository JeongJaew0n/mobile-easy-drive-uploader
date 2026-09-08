package com.jjw.easygallery.feature.autobackup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.upload.work.AutoBackupScheduler
import com.jjw.easygallery.core.domain.model.Album
import com.jjw.easygallery.core.domain.model.albumsFrom
import com.jjw.easygallery.core.domain.usecase.AutoBackupUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
@Suppress("TooGenericExceptionCaught") // UI 경계
class AutoBackupViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
    mediaRepository: MediaRepository,
    private val autoBackup: AutoBackupUseCase,
    private val scheduler: AutoBackupScheduler,
) : ViewModel() {

    private val isBusy = MutableStateFlow(false)
    private val events = Channel<AutoBackupEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val uiState: StateFlow<AutoBackupUiState> = combine(
        prefs.preferences,
        mediaRepository.observeMedia(MediaFilter.All).map { albumsFrom(it) },
        isBusy,
    ) { p, albums, busy ->
        AutoBackupUiState(
            isSignedIn = p.canUpload,
            enabled = p.autoBackupEnabled,
            albums = albums,
            selectedPaths = p.autoBackupPaths,
            includeVideos = p.autoBackupIncludeVideos,
            lastRunMillis = p.autoBackupLastRunMillis.takeIf { it > 0 },
            isBusy = busy,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AutoBackupUiState())

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setAutoBackupEnabled(enabled, nowSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND)
        if (enabled) scheduler.enable() else scheduler.disable()
    }

    fun toggleAlbum(album: Album) = viewModelScope.launch {
        val current = prefs.current().autoBackupPaths
        prefs.setAutoBackupPaths(
            if (album.relativePath in current) current - album.relativePath else current + album.relativePath,
        )
    }

    fun setIncludeVideos(enabled: Boolean) = viewModelScope.launch { prefs.setAutoBackupIncludeVideos(enabled) }

    /** 즉시 한 번 스캔. 워커를 거치지 않고 직접 실행해 결과를 바로 보여준다 */
    fun scanNow() = runBusy {
        val result = autoBackup.scanAndEnqueue()
        events.send(AutoBackupEvent.ScanFinished(result.enqueued, result.skipped))
    }

    fun requestBackfill() = runBusy {
        val count = autoBackup.pendingBackfillCount()
        events.send(AutoBackupEvent.ConfirmBackfill(count))
    }

    fun confirmBackfill() = runBusy {
        val result = autoBackup.backfill()
        events.send(AutoBackupEvent.ScanFinished(result.enqueued, result.skipped))
    }

    private fun runBusy(block: suspend () -> Unit) {
        if (isBusy.value) return
        viewModelScope.launch {
            isBusy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "auto-backup action failed")
                events.send(AutoBackupEvent.Error(e.message ?: e.toString()))
            } finally {
                isBusy.value = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}

data class AutoBackupUiState(
    /** Drive 로그인 또는 다른 저장소가 업로드 대상이면 true(자동 백업 가능) */
    val isSignedIn: Boolean = false,
    val enabled: Boolean = false,
    val albums: List<Album> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val includeVideos: Boolean = true,
    val lastRunMillis: Long? = null,
    val isBusy: Boolean = false,
    val isLoading: Boolean = true,
)

sealed interface AutoBackupEvent {
    data class ScanFinished(val enqueued: Int, val skipped: Int) : AutoBackupEvent
    data class ConfirmBackfill(val count: Int) : AutoBackupEvent
    data class Error(val message: String) : AutoBackupEvent
}
