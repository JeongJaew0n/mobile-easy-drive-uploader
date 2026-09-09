package com.jjw.easygallery.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountInfo
import com.jjw.easygallery.core.domain.model.RemoteAccountKind

/** 연결된 원격 저장소 목록 + 추가(`docs/MULTI_CLOUD.md` §5) */
@Composable
internal fun RemoteAccountsSection(
    accounts: List<RemoteAccount>,
    remoteInfos: Map<String, RemoteAccountInfo>,
    uploadAccountId: String?,
    driveEmail: String?,
    onOpenDrive: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEdit: (String) -> Unit = {},
) {
    var removing by remember { mutableStateOf<RemoteAccount?>(null) }
    Column {
        Text(text = stringResource(R.string.remote_section), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.remote_section_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        // Google Drive 는 Play 서비스가 토큰을 관리하므로 계정 행이 없다 — 목록 첫 줄에 고정으로 보여 준다
        if (driveEmail != null) {
            RemoteAccountRow(
                account = RemoteAccount(
                    id = RemoteAccount.GOOGLE_DRIVE_ID,
                    kind = RemoteAccountKind.GOOGLE_DRIVE,
                    displayName = stringResource(R.string.remote_kind_google),
                    endpoint = driveEmail,
                ),
                isUploadTarget = uploadAccountId == null,
                onOpen = onOpenDrive,
                onRemove = null,
            )
        }
        accounts.forEach { account ->
            RemoteAccountRow(
                account = account,
                isUploadTarget = account.id == uploadAccountId,
                onOpen = { onOpen(account.id) },
                onRemove = { removing = account },
                usage = remoteInfos[account.id],
                onEdit = { onEdit(account.id) },
            )
        }
        TextButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.remote_add))
        }
    }
    removing?.let { account ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remote_remove)) },
            text = { Text(stringResource(R.string.remote_remove_confirm, account.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(account.id)
                        removing = null
                    },
                ) {
                    Text(stringResource(R.string.remote_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** [onRemove] null 이면 ⋮ 메뉴 없음(Google Drive 행 — 연결 해제는 계정 카드에서) */
@Composable
internal fun RemoteAccountRow(
    account: RemoteAccount,
    isUploadTarget: Boolean,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)?,
    usage: RemoteAccountInfo? = null,
    onEdit: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = when (account.kind) {
                RemoteAccountKind.WEBDAV, RemoteAccountKind.SMB, RemoteAccountKind.SFTP -> R.drawable.ic_folder
                RemoteAccountKind.GOOGLE_DRIVE -> R.drawable.ic_insert_drive_file
                else -> R.drawable.ic_cloud_upload
            }
            Icon(painterResource(icon), contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(account.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = account.location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                usage?.storageUsedBytes?.let { used ->
                    val usedText = Formatter.formatShortFileSize(context, used)
                    val limitText = usage.storageLimitBytes?.let { Formatter.formatShortFileSize(context, it) }
                    Text(
                        text = if (limitText != null) {
                            stringResource(R.string.remote_storage_usage, usedText, limitText)
                        } else {
                            stringResource(R.string.remote_storage_usage_unlimited, usedText)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isUploadTarget) {
                    Text(
                        text = stringResource(R.string.remote_upload_target_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (onRemove != null) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (onEdit != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.remote_edit_menu)) },
                                onClick = {
                                    menuExpanded = false
                                    onEdit()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remote_remove)) },
                            onClick = {
                                menuExpanded = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
    }
}
