package com.jjw.easygallery.feature.backup

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.UploadSummary
import com.jjw.easygallery.core.ui.media.MediaActionEffect
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme
import com.jjw.easygallery.feature.gallery.MediaPermission
import com.jjw.easygallery.feature.gallery.MediaPermissionStatus
import com.jjw.easygallery.feature.gallery.UploadFailedBanner
import com.jjw.easygallery.feature.gallery.UploadProgressBanner

@Composable
fun BackupRoute(
    onUploadQueueClick: () -> Unit,
    onAutoBackupClick: () -> Unit,
    onDrivePhotosClick: (openFileId: String?) -> Unit,
    onBackedUpClick: () -> Unit,
    onSettingsClick: () -> Unit,
    navigationBar: @Composable () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // 사진 권한이 없어도 앱이 직접 저장한 파일(Drive 에서 받은 것)은 보여서 "2장 중 1장" 처럼 엉뚱하게 센다(2026-10-08 기기).
    // 셀 수 없으면 요약 카드를 두지 않는다. 설정에서 바꾸고 돌아오는 경우가 있어 RESUME 마다 다시 본다
    val context = LocalContext.current
    var mediaAccess by remember { mutableStateOf(MediaPermission.status(context)) }
    LifecycleResumeEffect(Unit) {
        mediaAccess = MediaPermission.status(context)
        onPauseOrDispose { }
    }
    MediaActionEffect(
        events = viewModel.actionEvents,
        snackbarHostState = snackbarHostState,
        onConsentResult = viewModel::onConsentResult,
    )
    BackupScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        imageLoader = viewModel.driveImageLoader,
        showOverview = mediaAccess != MediaPermissionStatus.Denied,
        actions = BackupActions(
            onUploadQueueClick = onUploadQueueClick,
            onAutoBackupClick = onAutoBackupClick,
            onDrivePhotosClick = onDrivePhotosClick,
            onBackedUpClick = onBackedUpClick,
            onSettingsClick = onSettingsClick,
            onCancelUploads = viewModel::cancelUploads,
            onTrashUploaded = viewModel::trashUploadedOnDevice,
        ),
        navigationBar = navigationBar,
    )
}

/** 백업 칸에서 누를 수 있는 것들 — 파라미터 폭발 방지 */
internal data class BackupActions(
    val onUploadQueueClick: () -> Unit = {},
    val onAutoBackupClick: () -> Unit = {},
    /** null 이면 Drive 사진 화면, 아니면 그 사진의 넘겨 보기까지 */
    val onDrivePhotosClick: (openFileId: String?) -> Unit = {},
    /** 요약 카드·최근 백업 → 백업된 사진 화면 */
    val onBackedUpClick: () -> Unit = {},
    val onSettingsClick: () -> Unit = {},
    val onCancelUploads: () -> Unit = {},
    val onTrashUploaded: () -> Unit = {},
)

/**
 * 하단 "백업" 칸(`docs/plans/bottom-navigation/spec.md`). 위에서부터: 얼마나·어디에 올라갔나(요약 카드) → 어디로 올리는지 →
 * 지금 무슨 일이 있는지(진행·실패) →
 * Drive 에 무엇이 있는지(Google Drive 사진 카드) → 할 수 있는 일(업로드 목록·자동 백업·올린 사진 정리).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupScreen(
    uiState: BackupUiState,
    actions: BackupActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    navigationBar: @Composable () -> Unit = {},
    imageLoader: ImageLoader? = null,
    /** 사진 권한이 없으면 false — 셀 수 없다 */
    showOverview: Boolean = true,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_backup)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = navigationBar,
    ) { innerPadding ->
        when (uiState) {
            BackupUiState.Loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is BackupUiState.Content -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (uiState.isMutating) LinearProgressIndicator(Modifier.fillMaxWidth())
                uiState.overview?.takeIf { showOverview }?.let { overview ->
                    BackupSummaryCard(overview, failed = uiState.summary.failed, onOpen = actions.onBackedUpClick)
                }
                TargetCard(uiState.target, onSettingsClick = actions.onSettingsClick)
                BackupStatus(uiState.summary, actions)
                uiState.drivePhotos?.let { photos ->
                    DrivePhotosCard(photos, imageLoader, onOpen = actions.onDrivePhotosClick)
                }
                BackupRows(uiState, actions)
            }
        }
    }
}

@Composable
private fun TargetCard(target: BackupTarget?, onSettingsClick: () -> Unit) {
    Surface(
        onClick = onSettingsClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.backup_target_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (target == null) {
                Text(stringResource(R.string.backup_target_none), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.backup_target_none_action),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = target.accountName ?: stringResource(R.string.remote_kind_google),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = target.folderName ?: stringResource(R.string.settings_upload_folder_default),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 진행 중이면 진행 배너, 실패가 있으면 실패 배너, 아무것도 없으면 한 줄 */
@Composable
private fun BackupStatus(summary: UploadSummary, actions: BackupActions) {
    when {
        summary.hasActive -> UploadProgressBanner(
            summary = summary,
            onCancel = actions.onCancelUploads,
            onClick = actions.onUploadQueueClick,
        )
        summary.failed > 0 -> UploadFailedBanner(
            failed = summary.failed,
            onClick = actions.onUploadQueueClick,
            reason = summary.failureReason,
        )
        else -> Text(
            text = stringResource(R.string.backup_idle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BackupRows(uiState: BackupUiState.Content, actions: BackupActions) {
    var confirmTrash by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        BackupRow(
            icon = painterResource(R.drawable.ic_cloud_upload),
            title = stringResource(R.string.settings_upload_queue),
            onClick = actions.onUploadQueueClick,
        )
        BackupRow(
            icon = painterResource(R.drawable.ic_cloud_done),
            title = stringResource(R.string.settings_auto_backup),
            subtitle = stringResource(
                if (uiState.autoBackupEnabled) R.string.backup_auto_on else R.string.backup_auto_off,
            ),
            onClick = actions.onAutoBackupClick,
        )
        val count = uiState.uploadedOnDeviceCount
        BackupRow(
            icon = painterResource(R.drawable.ic_delete_forever),
            title = stringResource(R.string.gallery_menu_trash_uploaded),
            subtitle = if (count > 0) {
                stringResource(
                    R.string.backup_uploaded_on_device,
                    count,
                    Formatter.formatShortFileSize(context, uiState.uploadedOnDeviceBytes),
                )
            } else {
                stringResource(R.string.backup_uploaded_on_device_none)
            },
            enabled = count > 0 && !uiState.isMutating,
            onClick = { confirmTrash = true },
        )
    }
    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.backup_trash_uploaded_title,
                        uiState.uploadedOnDeviceCount,
                        uiState.uploadedOnDeviceCount,
                    ),
                )
            },
            text = { Text(stringResource(R.string.backup_trash_uploaded_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmTrash = false
                    actions.onTrashUploaded()
                }) { Text(stringResource(R.string.action_trash)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTrash = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun BackupRow(
    icon: Painter,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            Icon(icon, contentDescription = null, tint = tint)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = tint)
        }
        HorizontalDivider()
    }
}

@Preview
@Composable
private fun BackupScreenPreview() {
    EasyGalleryTheme {
        BackupScreen(
            uiState = BackupUiState.Content(
                summary = UploadSummary(total = 50, failed = 3),
                target = BackupTarget(accountName = null, folderName = "Easy Gallery"),
                autoBackupEnabled = true,
                uploadedOnDeviceCount = 1240,
                uploadedOnDeviceBytes = 4_800_000_000,
            ),
            actions = BackupActions(),
        )
    }
}
