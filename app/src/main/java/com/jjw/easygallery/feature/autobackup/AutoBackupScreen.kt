package com.jjw.easygallery.feature.autobackup

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.Album

@Composable
fun AutoBackupRoute(
    onBackClick: () -> Unit,
    viewModel: AutoBackupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var backfillCount by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is AutoBackupEvent.ScanFinished -> snackbarHostState.showSnackbar(
                    if (event.enqueued == 0) {
                        resources.getString(R.string.auto_backup_scan_nothing)
                    } else {
                        resources.getQuantityString(R.plurals.auto_backup_scan_enqueued, event.enqueued, event.enqueued)
                    },
                )
                is AutoBackupEvent.ConfirmBackfill -> backfillCount = event.count
                is AutoBackupEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    AutoBackupScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onEnabledChange = viewModel::setEnabled,
        onToggleAlbum = viewModel::toggleAlbum,
        onIncludeVideosChange = viewModel::setIncludeVideos,
        onScanNow = viewModel::scanNow,
        onBackfill = viewModel::requestBackfill,
    )

    backfillCount?.let { count ->
        AlertDialog(
            onDismissRequest = { backfillCount = null },
            title = { Text(stringResource(R.string.auto_backup_backfill)) },
            text = {
                Text(
                    if (count == 0) {
                        stringResource(R.string.auto_backup_backfill_nothing)
                    } else {
                        pluralStringResource(R.plurals.auto_backup_backfill_confirm, count, count)
                    },
                )
            },
            confirmButton = {
                if (count > 0) {
                    TextButton(
                        onClick = {
                            backfillCount = null
                            viewModel.confirmBackfill()
                        },
                    ) { Text(stringResource(R.string.action_upload_to_drive)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { backfillCount = null }) {
                    Text(stringResource(if (count > 0) R.string.action_cancel else R.string.action_ok))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoBackupScreen(
    uiState: AutoBackupUiState,
    onBackClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onToggleAlbum: (Album) -> Unit,
    onIncludeVideosChange: (Boolean) -> Unit,
    onScanNow: () -> Unit,
    onBackfill: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.auto_backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            CircularProgressIndicator(
                Modifier
                    .padding(innerPadding)
                    .padding(24.dp),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            item(key = "enable") {
                SwitchRow(
                    title = stringResource(R.string.auto_backup_enable),
                    description = stringResource(
                        if (uiState.isSignedIn) {
                            R.string.auto_backup_enable_description
                        } else {
                            R.string.auto_backup_requires_sign_in
                        },
                    ),
                    checked = uiState.enabled,
                    enabled = uiState.isSignedIn,
                    onCheckedChange = onEnabledChange,
                )
            }
            item(key = "target") {
                TargetRow(accountName = uiState.targetAccountName, folderName = uiState.targetFolderName)
            }
            item(key = "videos") {
                SwitchRow(
                    title = stringResource(R.string.auto_backup_include_videos),
                    description = stringResource(R.string.auto_backup_include_videos_description),
                    checked = uiState.includeVideos,
                    enabled = true,
                    onCheckedChange = onIncludeVideosChange,
                )
            }
            item(key = "status") {
                StatusRow(
                    lastRunMillis = uiState.lastRunMillis,
                    selectedCount = uiState.selectedPaths.size,
                    isBusy = uiState.isBusy,
                    canRun = uiState.isSignedIn && uiState.selectedPaths.isNotEmpty(),
                    onScanNow = onScanNow,
                    onBackfill = onBackfill,
                )
            }
            item(key = "albums-header") {
                Text(
                    text = stringResource(R.string.auto_backup_albums),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            if (uiState.albums.isEmpty()) {
                item(key = "albums-empty") {
                    Text(
                        text = stringResource(R.string.auto_backup_no_albums),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(uiState.albums, key = { it.relativePath }) { album ->
                AlbumRow(
                    album = album,
                    checked = album.relativePath in uiState.selectedPaths,
                    onToggle = { onToggleAlbum(album) },
                )
            }
        }
    }
}

/** 어디로 백업되는지 — 설정 "업로드 대상"을 그대로 읽어 보여 준다(여기서 바꾸지는 않음) */
@Composable
private fun TargetRow(accountName: String?, folderName: String?) {
    val text = when {
        accountName == null -> stringResource(R.string.auto_backup_target_none)
        folderName != null -> stringResource(R.string.auto_backup_target, "$accountName · $folderName")
        else -> stringResource(R.string.auto_backup_target, accountName)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        HorizontalDivider()
    }
}

@Composable
private fun StatusRow(
    lastRunMillis: Long?,
    selectedCount: Int,
    isBusy: Boolean,
    canRun: Boolean,
    onScanNow: () -> Unit,
    onBackfill: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        val lastRun = lastRunMillis?.let {
            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        }
        Text(
            text = if (lastRun != null) {
                stringResource(R.string.auto_backup_last_run, lastRun)
            } else {
                stringResource(R.string.auto_backup_never_run)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = pluralStringResource(R.plurals.auto_backup_selected_albums, selectedCount, selectedCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onScanNow, enabled = canRun && !isBusy) {
                Text(stringResource(R.string.auto_backup_scan_now))
            }
            OutlinedButton(onClick = onBackfill, enabled = canRun && !isBusy) {
                Text(stringResource(R.string.auto_backup_backfill))
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun AlbumRow(
    album: Album,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(8.dp))
        Column {
            Text(album.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.gallery_move_album_subtitle, album.relativePath, album.itemCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
