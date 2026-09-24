package com.jjw.easygallery.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R

/**
 * 지정 폴더의 별칭을 받는다.
 *
 * `drive.file` 에서는 남의 폴더를 `files.get` 할 수 없어 **이름을 읽을 방법이 없다**.
 * 그래서 사용자가 직접 붙인다 — `docs/DRIVE_FILE_SCOPE.md` §4.
 */
@Composable
internal fun FolderAliasDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var alias by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_folder_alias_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_folder_alias_description))
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_folder_alias_label)) },
                    modifier = Modifier,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(alias) }, enabled = alias.isNotBlank()) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
