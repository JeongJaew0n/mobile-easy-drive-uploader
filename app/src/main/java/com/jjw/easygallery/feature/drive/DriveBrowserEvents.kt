package com.jjw.easygallery.feature.drive

import android.app.PendingIntent
import android.content.res.Resources
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.DriveFolder

/** ViewModel 이벤트 → 스낵바(실행 취소 포함). Route 에서 분리해 복잡도를 낮춘다 */
internal suspend fun showBrowserEvent(
    event: DriveBrowserEvent,
    snackbarHostState: SnackbarHostState,
    resources: Resources,
    viewModel: DriveBrowserViewModel,
    onUploadFolderSelected: (DriveFolder) -> Unit,
    onViewScopeConsent: (PendingIntent) -> Unit,
    onOpenViewFolderPicker: () -> Unit,
) {
    if (showViewFolderEvent(event, snackbarHostState, resources, onViewScopeConsent, onOpenViewFolderPicker)) return
    when (event) {
        is DriveBrowserEvent.BatchTrashed, is DriveBrowserEvent.BatchMoved, is DriveBrowserEvent.BatchFailed ->
            showBatchEvent(event, snackbarHostState, resources, viewModel)
        is DriveBrowserEvent.FolderCreated ->
            snackbarHostState.showSnackbar(resources.getString(R.string.drive_folder_created, event.folder.name))
        is DriveBrowserEvent.UploadFolderSelected -> onUploadFolderSelected(event.folder)
        is DriveBrowserEvent.Renamed ->
            snackbarHostState.showSnackbar(resources.getString(R.string.drive_renamed, event.name))
        is DriveBrowserEvent.Moved -> snackbarHostState.showSnackbar(
            resources.getString(R.string.drive_moved, event.entry.name, event.target.name),
        )
        is DriveBrowserEvent.Trashed -> {
            val result = snackbarHostState.showSnackbar(
                message = resources.getString(R.string.drive_trashed, event.entry.name),
                actionLabel = resources.getString(R.string.action_undo),
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.restore(event.entry)
        }
        is DriveBrowserEvent.Deleted ->
            snackbarHostState.showSnackbar(resources.getString(R.string.drive_deleted, event.entry.name))
        DriveBrowserEvent.Restored ->
            snackbarHostState.showSnackbar(resources.getString(R.string.drive_restored))
        is DriveBrowserEvent.DownloadStarted ->
            snackbarHostState.showSnackbar(resources.getString(R.string.drive_download_started, event.count))
        is DriveBrowserEvent.Error -> snackbarHostState.showSnackbar(event.message)
        // 위에서 이미 처리했다. else 로 뭉뚱그리지 않는 것은, 새 이벤트를 더했을 때
        // 컴파일러가 "여기도 보라" 고 말해주게 하기 위해서다
        is DriveBrowserEvent.NeedsViewScopeConsent,
        DriveBrowserEvent.ViewFolderPickerReady,
        DriveBrowserEvent.ViewScopeDenied,
        is DriveBrowserEvent.ViewFolderAdded,
        is DriveBrowserEvent.ViewFolderRemoved,
        is DriveBrowserEvent.ViewFolderAlreadyThere,
        -> Unit
    }
}

/**
 * 보기 전용 폴더(`docs/DRIVE_FILE_SCOPE.md` §10) 관련 이벤트. 처리했으면 true.
 *
 * 한 `when` 에 다 넣으면 분기가 detekt 한계를 넘는다 — 성격이 다른 묶음이라 떼어내는 편이 읽기도 낫다.
 */
private suspend fun showViewFolderEvent(
    event: DriveBrowserEvent,
    snackbarHostState: SnackbarHostState,
    resources: Resources,
    onViewScopeConsent: (PendingIntent) -> Unit,
    onOpenViewFolderPicker: () -> Unit,
): Boolean {
    when (event) {
        is DriveBrowserEvent.NeedsViewScopeConsent -> onViewScopeConsent(event.pendingIntent)
        DriveBrowserEvent.ViewFolderPickerReady -> onOpenViewFolderPicker()
        DriveBrowserEvent.ViewScopeDenied ->
            snackbarHostState.showSnackbar(resources.getString(R.string.drive_view_scope_denied))
        is DriveBrowserEvent.ViewFolderAdded ->
            snackbarHostState.showSnackbar(resources.getString(R.string.drive_view_folder_added, event.name))
        is DriveBrowserEvent.ViewFolderRemoved ->
            snackbarHostState.showSnackbar(resources.getString(R.string.drive_view_folder_removed, event.name))
        is DriveBrowserEvent.ViewFolderAlreadyThere ->
            snackbarHostState.showSnackbar(resources.getString(R.string.drive_view_folder_exists, event.name))
        else -> return false
    }
    return true
}

/** 다중 선택 일괄 작업 결과 */
private suspend fun showBatchEvent(
    event: DriveBrowserEvent,
    snackbarHostState: SnackbarHostState,
    resources: Resources,
    viewModel: DriveBrowserViewModel,
) {
    when (event) {
        is DriveBrowserEvent.BatchTrashed -> {
            if (!event.isTrash) {
                snackbarHostState.showSnackbar(resources.getString(R.string.drive_batch_deleted, event.entries.size))
            } else {
                val result = snackbarHostState.showSnackbar(
                    message = resources.getString(R.string.drive_batch_trashed, event.entries.size),
                    actionLabel = resources.getString(R.string.action_undo),
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.restoreAll(event.entries)
            }
        }
        is DriveBrowserEvent.BatchMoved -> snackbarHostState.showSnackbar(
            resources.getString(R.string.drive_batch_moved, event.count, event.target.name),
        )
        is DriveBrowserEvent.BatchFailed ->
            snackbarHostState.showSnackbar(resources.getString(R.string.drive_batch_failed, event.count))
        else -> Unit
    }
}
