package com.jjw.easygallery.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import com.jjw.easygallery.core.data.hidden.HiddenMediaRepository
import com.jjw.easygallery.core.data.media.MediaAction
import com.jjw.easygallery.core.data.media.MediaActionController
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.remote.RemoteAccountRepository
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.UploadSummary
import com.jjw.easygallery.core.domain.usecase.ManageUploadQueueUseCase
import com.jjw.easygallery.core.domain.usecase.ObserveUploadSummaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 하단 "백업" 칸(`docs/plans/bottom-navigation/spec.md`). 업로드가 어떻게 되고 있는지, 무엇을 할 수 있는지를 한곳에 모은다.
 *
 * "올린 사진 기기에서 삭제" 는 **올린 사진 전부**(숨긴 것 제외)가 대상이다 — 예전에는 갤러리에서 지금 보는 목록이 대상이었다.
 * 대상이 넓어진 만큼 화면이 먼저 개수를 보여 주고 묻는다. 휴지통이라 30일 안에는 되돌릴 수 있다.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModel @Inject constructor(
    mediaRepository: MediaRepository,
    hiddenMedia: HiddenMediaRepository,
    uploadLedger: UploadLedgerRepository,
    observeUploadSummary: ObserveUploadSummaryUseCase,
    private val manageQueue: ManageUploadQueueUseCase,
    private val actionController: MediaActionController,
    prefs: UserPreferencesRepository,
    remoteAccounts: RemoteAccountRepository,
    recentDrivePhotos: RecentDrivePhotos,
) : ViewModel() {

    /** "Google Drive 사진" 띠의 썸네일 — 인증이 붙어 있다 */
    val driveImageLoader: ImageLoader = recentDrivePhotos.imageLoader

    /** 편집 동의 흐름은 공용 컨트롤러가 맡는다 */
    val actionEvents = actionController.events

    private var latestUploadedOnDevice: List<MediaItem> = emptyList()

    /** 지금 업로드 대상(원장의 목적지)으로 올라간 것 중 기기에 남아 있는 것. 숨긴 사진은 건드리지 않는다 */
    private val uploadedOnDevice: Flow<List<MediaItem>> = combine(
        mediaRepository.observeMedia(MediaFilter.All),
        hiddenMedia.observeHiddenIds(),
        prefs.preferences.map { it.uploadAccountId }.distinctUntilChanged()
            .flatMapLatest { uploadLedger.observeUploadedIds(it) },
    ) { items, hidden, uploaded -> items.filter { it.id in uploaded && it.id !in hidden } }
        // 권한이 없으면 MediaStore 조회가 실패한다 — 0장으로 두고 나머지 칸은 그대로 보인다
        .catch { e ->
            Timber.w(e, "uploaded-on-device unavailable")
            emit(emptyList())
        }
        .onEach { latestUploadedOnDevice = it }

    private val baseState: Flow<BackupUiState.Content> = combine(
        observeUploadSummary(),
        prefs.preferences,
        remoteAccounts.observeAccounts(),
        uploadedOnDevice,
        actionController.isMutating,
    ) { summary, p, accounts, onDevice, mutating ->
        BackupUiState.Content(
            summary = summary,
            target = when (val id = p.uploadAccountId) {
                null -> if (p.isSignedIn) BackupTarget(accountName = null, folderName = p.uploadFolderName) else null
                else -> accounts.firstOrNull { it.id == id }?.let { BackupTarget(it.displayName, p.uploadFolderName) }
            },
            autoBackupEnabled = p.autoBackupEnabled,
            uploadedOnDeviceCount = onDevice.size,
            uploadedOnDeviceBytes = onDevice.sumOf { it.sizeBytes },
            isMutating = mutating,
        )
    }

    /** 요약 카드 — 어디든 올라간 기기 사진과 저장소별 수. 사진 권한이 없으면 null(셀 수 없다) */
    private val overview: Flow<BackupOverview?> = combine(
        mediaRepository.observeMedia(MediaFilter.All),
        hiddenMedia.observeHiddenIds(),
        uploadLedger.observeRecords(),
        remoteAccounts.observeAccounts(),
        prefs.preferences.map { it.accountEmail }.distinctUntilChanged(),
    ) { items, hidden, records, accounts, email ->
        backupOverview(items.filterNot { it.id in hidden }, records, accounts, email) as BackupOverview?
    }.catch { e ->
        Timber.w(e, "backup overview unavailable")
        emit(null)
    }

    val uiState: StateFlow<BackupUiState> = combine(
        baseState,
        recentDrivePhotos.observe(),
        overview,
    ) { content, photos, summary ->
        content.copy(drivePhotos = photos, overview = summary) as BackupUiState
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BackupUiState.Loading)

    fun cancelUploads() {
        viewModelScope.launch { manageQueue.cancelAll() }
    }

    /** 확인 창에서 "옮기기" 를 누른 뒤에만 부른다 */
    fun trashUploadedOnDevice() {
        val targets = latestUploadedOnDevice
        if (targets.isEmpty()) return
        actionController.perform(viewModelScope, MediaAction.Trash(targets, trashed = true))
    }

    fun onConsentResult(granted: Boolean) = actionController.onConsentResult(viewModelScope, granted)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** 업로드 대상. [accountName] 이 null 이면 Google Drive(화면이 이름을 붙인다) */
data class BackupTarget(val accountName: String?, val folderName: String?)

sealed interface BackupUiState {
    data object Loading : BackupUiState

    data class Content(
        val summary: UploadSummary = UploadSummary(),
        /** null 이면 올릴 곳이 연결돼 있지 않다 */
        val target: BackupTarget? = null,
        val autoBackupEnabled: Boolean = false,
        val uploadedOnDeviceCount: Int = 0,
        val uploadedOnDeviceBytes: Long = 0,
        val isMutating: Boolean = false,
        /** null 이면 Google 계정이 없다 — "Google Drive 사진" 카드를 두지 않는다 */
        val drivePhotos: DrivePhotosPreview? = null,
        /** 요약 카드. null 이면 셀 수 없다(사진 권한 없음) — 카드를 두지 않는다 */
        val overview: BackupOverview? = null,
    ) : BackupUiState
}

/** 백업 칸의 Drive 최근 사진 띠 */
data class DrivePhotosPreview(
    val entries: List<DriveEntry> = emptyList(),
    val isLoading: Boolean = false,
)
