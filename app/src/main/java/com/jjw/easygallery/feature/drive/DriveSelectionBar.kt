package com.jjw.easygallery.feature.drive

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.jjw.easygallery.R

/** 선택 모드 상단바 — "n개 선택", 전체 선택, 이동(능력이 있을 때), 삭제 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveSelectionTopBar(
    count: Int,
    canMove: Boolean,
    enabled: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    canDownload: Boolean = false,
    onDownload: () -> Unit = {},
) {
    TopAppBar(
        title = { Text(stringResource(R.string.gallery_selected_count, count)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
            }
        },
        actions = {
            IconButton(onClick = onSelectAll, enabled = enabled) {
                Icon(
                    painterResource(R.drawable.ic_select_all),
                    contentDescription = stringResource(R.string.action_select_all),
                )
            }
            if (canDownload) {
                IconButton(onClick = onDownload, enabled = enabled) {
                    Icon(
                        painterResource(R.drawable.ic_file_download),
                        contentDescription = stringResource(R.string.drive_menu_download),
                    )
                }
            }
            if (canMove) {
                IconButton(onClick = onMove, enabled = enabled) {
                    Icon(
                        painterResource(R.drawable.ic_drive_file_move),
                        contentDescription = stringResource(R.string.drive_menu_move),
                    )
                }
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_remove))
            }
        },
    )
}

/** 선택 모드 다이얼로그 — 영구 삭제 확인(휴지통 없는 저장소), 이동 대상 폴더 선택 */
@Composable
internal fun DriveSelectionDialogs(
    uiState: DriveBrowserUiState,
    deletingSelection: Boolean,
    movingSelection: Boolean,
    onDismissDelete: () -> Unit,
    onDismissMove: () -> Unit,
    entryActions: DriveEntryActions,
) {
    if (deletingSelection) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(stringResource(R.string.drive_batch_delete_title, uiState.selectedIds.size)) },
            text = { Text(stringResource(R.string.drive_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissDelete()
                        entryActions.onTrashSelected()
                    },
                ) {
                    Text(stringResource(R.string.action_delete_forever))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    val current = uiState.current
    if (movingSelection && current != null) {
        DriveFolderPickerSheet(
            start = current,
            excludeFolderId = null,
            currentParentId = current.id,
            loadFolders = entryActions.loadFolders,
            onDismiss = onDismissMove,
            onPick = { target ->
                onDismissMove()
                entryActions.onMoveSelected(target)
            },
        )
    }
}
