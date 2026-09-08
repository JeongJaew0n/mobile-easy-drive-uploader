package com.jjw.easygallery.feature.drive

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
) {
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
    }
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
