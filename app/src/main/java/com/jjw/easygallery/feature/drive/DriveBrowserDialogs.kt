package com.jjw.easygallery.feature.drive

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.DriveEntry

@Composable
internal fun DriveBrowserDialogs(
    uiState: DriveBrowserUiState,
    showCreateDialog: Boolean,
    renaming: DriveEntry?,
    moving: DriveEntry?,
    onDismissCreate: () -> Unit,
    onDismissRename: () -> Unit,
    onDismissMove: () -> Unit,
    onCreateFolder: (String) -> Unit,
    entryActions: DriveEntryActions,
) {
    if (showCreateDialog) {
        CreateFolderDialog(
            onDismiss = onDismissCreate,
            onConfirm = { name ->
                onDismissCreate()
                onCreateFolder(name)
            },
        )
    }
    if (renaming != null) {
        DriveRenameDialog(
            currentName = renaming.name,
            onDismiss = onDismissRename,
            onConfirm = { name ->
                onDismissRename()
                entryActions.onRename(renaming, name)
            },
        )
    }
    val current = uiState.current
    if (moving != null && current != null) {
        DriveFolderPickerSheet(
            start = current,
            excludeFolderId = moving.takeIf { it.isFolder }?.id,
            currentParentId = current.id,
            loadFolders = entryActions.loadFolders,
            onDismiss = onDismissMove,
            onPick = { target ->
                onDismissMove()
                entryActions.onMove(moving, target)
            },
        )
    }
}

@Composable
internal fun DriveRenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.drive_rename_label)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank() && name.trim() != currentName) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_picker_new_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.folder_picker_folder_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
