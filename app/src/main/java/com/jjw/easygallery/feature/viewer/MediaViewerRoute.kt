package com.jjw.easygallery.feature.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.common.text.displayMessage
import com.jjw.easygallery.core.domain.model.CategoryFilter
import com.jjw.easygallery.core.domain.model.DateRange
import com.jjw.easygallery.core.navigation.MediaViewerKey
import com.jjw.easygallery.core.ui.media.MediaActionEffect
import com.jjw.easygallery.core.ui.media.TrashFollowUp
import com.jjw.easygallery.feature.drive.driveFileLink
import com.jjw.easygallery.feature.drive.openDriveLink
import com.jjw.easygallery.feature.gallery.BackupStatusFilter
import com.jjw.easygallery.feature.gallery.BackupView
import com.jjw.easygallery.feature.gallery.GalleryTab

@Composable
fun MediaViewerRoute(
    key: MediaViewerKey,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: MediaViewerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val backups by viewModel.currentBackups.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(key) {
        viewModel.load(
            mediaId = key.mediaId,
            favoritesOnly = key.favoritesOnly,
            range = key.dateRange(),
            category = key.categoryFilter(),
            hiddenOnly = key.hiddenOnly,
            tab = key.galleryTab(),
            albumPath = key.albumPath,
            backup = key.backupView(),
        )
    }

    val manageMediaHintPending by viewModel.manageMediaHintPending.collectAsStateWithLifecycle()
    MediaActionEffect(
        events = viewModel.actionEvents,
        snackbarHostState = snackbarHostState,
        onConsentResult = viewModel::onConsentResult,
        trashFollowUp = TrashFollowUp(
            onUndo = viewModel::undoTrash,
            offerManageMedia = manageMediaHintPending,
            onManageMediaOffered = viewModel::markManageMediaHintShown,
        ),
    )

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is MediaViewerEvent.Enqueued -> snackbarHostState.showSnackbar(
                    resources.getQuantityString(R.plurals.gallery_upload_enqueued, event.added, event.added),
                )
                MediaViewerEvent.SignInRequired -> {
                    snackbarHostState.showSnackbar(resources.getString(R.string.gallery_sign_in_required))
                    onSettingsClick()
                }
                MediaViewerEvent.Hidden ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.viewer_hidden_done))
                is MediaViewerEvent.Error -> snackbarHostState.showSnackbar(event.error.displayMessage(resources))
            }
        }
    }

    // 마지막 항목까지 삭제하면 볼 것이 없으므로 닫는다
    LaunchedEffect(uiState.isLoading, uiState.items.size) {
        if (!uiState.isLoading && uiState.items.isEmpty()) onBackClick()
    }

    // 히어로 연출은 첫 진입 1회만. 회전·복원 후에는 좌표가 어긋날 수 있어 건너뛴다
    var heroDone by rememberSaveable { mutableStateOf(key.hero == null) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        MediaViewerScreen(
            uiState = uiState,
            snackbarHostState = snackbarHostState,
            contentHidden = !heroDone,
            onBackClick = onBackClick,
            onPageChanged = viewModel::onPageChanged,
            onToggleFavorite = viewModel::toggleFavorite,
            onTrash = viewModel::trash,
            onDelete = viewModel::delete,
            onHide = viewModel::hide,
            onRename = viewModel::rename,
            onMove = viewModel::move,
            onUpload = viewModel::upload,
            onToggleInfo = viewModel::toggleInfo,
            onCreateCategory = viewModel::createCategory,
            onAssignCategories = viewModel::assignCategoriesToCurrent,
            backups = backups,
            onOpenInDrive = { line -> context.openDriveLink(driveFileLink(line.remoteId), line.openAs) },
        )
        val hero = key.hero
        if (!heroDone && hero != null) {
            HeroOverlay(origin = hero, mediaId = key.mediaId, onFinished = { heroDone = true })
        }
    }
}

private fun MediaViewerKey.dateRange(): DateRange? =
    if (startEpochDay != null && endEpochDay != null) DateRange.of(startEpochDay, endEpochDay) else null

private fun MediaViewerKey.categoryFilter(): CategoryFilter? = when {
    uncategorizedOnly -> CategoryFilter.Uncategorized
    !categoryIds.isNullOrEmpty() -> CategoryFilter.Any(categoryIds.toSet())
    else -> null
}

/** 백업된 사진 화면에서 열었으면 그 세그먼트·저장소. 모르는 이름이면 백업 범위가 아닌 것으로 본다 */
private fun MediaViewerKey.backupView(): BackupView? =
    backupStatus?.let { name -> BackupStatusFilter.entries.find { it.name == name } }
        ?.let { BackupView(it, backupDestination) }

/** 모르는 이름(앱을 올리며 탭이 바뀐 뒤 복원된 키)이면 전체로 본다 */
private fun MediaViewerKey.galleryTab(): GalleryTab =
    tab?.let { name -> GalleryTab.entries.find { it.name == name } } ?: GalleryTab.ALL
