package com.jjw.easygallery.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountInfo
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import com.jjw.easygallery.core.domain.model.VideoCompression
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme

@Composable
fun SettingsRoute(
    onBackClick: () -> Unit,
    onUploadFolderClick: () -> Unit,
    onUploadQueueClick: () -> Unit,
    onDriveClick: () -> Unit,
    onAutoBackupClick: () -> Unit,
    onDuplicatesClick: () -> Unit,
    onCategoriesClick: () -> Unit = {},
    onAddRemoteAccountClick: () -> Unit = {},
    onOpenRemoteAccount: (accountId: String) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current

    // Android 12+: 시스템 설정에서 부여하는 특수 권한이라 RESUME 마다 다시 읽는다
    var canManageMedia by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        canManageMedia = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)
        onPauseOrDispose { }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onConsentResult(result.resultCode, result.data) }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is SettingsEvent.LaunchConsent ->
                    consentLauncher.launch(IntentSenderRequest.Builder(event.pendingIntent).build())
                SettingsEvent.SignedIn ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.settings_signed_in))
                SettingsEvent.SignInCancelled ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.settings_sign_in_cancelled))
                is SettingsEvent.SignInFailed -> snackbarHostState.showSnackbar(
                    resources.getString(signInFailureMessage(event.statusCode), event.statusCode),
                )
                is SettingsEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    SettingsScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onSignInClick = viewModel::signIn,
        onSignOutClick = viewModel::signOut,
        onUploadFolderClick = onUploadFolderClick,
        onUploadQueueClick = onUploadQueueClick,
        onDriveClick = onDriveClick,
        onAutoBackupClick = onAutoBackupClick,
        onDuplicatesClick = onDuplicatesClick,
        onCategoriesClick = onCategoriesClick,
        onAddRemoteAccountClick = onAddRemoteAccountClick,
        onOpenRemoteAccount = onOpenRemoteAccount,
        onRemoveRemoteAccount = viewModel::removeRemoteAccount,
        onWifiOnlyChange = viewModel::setUploadWifiOnly,
        onChargingOnlyChange = viewModel::setUploadChargingOnly,
        onCategoryBadgesChange = viewModel::setShowCategoryBadges,
        onVideoCompressionChange = viewModel::setVideoCompression,
        manageMedia = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) canManageMedia else null,
        onManageMediaClick = {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onUploadFolderClick: () -> Unit,
    onUploadQueueClick: () -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onChargingOnlyChange: (Boolean) -> Unit,
    onCategoryBadgesChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    onVideoCompressionChange: (VideoCompression) -> Unit = {},
    onDriveClick: () -> Unit = {},
    onAutoBackupClick: () -> Unit = {},
    onDuplicatesClick: () -> Unit = {},
    onCategoriesClick: () -> Unit = {},
    onAddRemoteAccountClick: () -> Unit = {},
    onOpenRemoteAccount: (accountId: String) -> Unit = {},
    onRemoveRemoteAccount: (accountId: String) -> Unit = {},
    /** null = 이 기기에서 지원 안 함(Android 11 이하) */
    manageMedia: Boolean? = null,
    onManageMediaClick: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountCard(
                uiState = uiState,
                onSignInClick = onSignInClick,
                onSignOutClick = onSignOutClick,
            )
            RemoteAccountsSection(
                accounts = uiState.remoteAccounts,
                remoteInfos = uiState.remoteInfos,
                uploadAccountId = uiState.uploadAccountId,
                driveEmail = uiState.accountEmail,
                onOpenDrive = onDriveClick,
                onAdd = onAddRemoteAccountClick,
                onOpen = onOpenRemoteAccount,
                onRemove = onRemoveRemoteAccount,
            )
            if (uiState.canUpload) {
                Text(
                    text = stringResource(R.string.settings_upload_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                UploadFolderRow(
                    folderName = uiState.uploadFolderName?.let { folder ->
                        stringResource(
                            R.string.remote_upload_target,
                            uiState.uploadAccountName ?: stringResource(R.string.remote_kind_google),
                            folder,
                        )
                    },
                    onClick = onUploadFolderClick,
                )
                NavigationRow(
                    icon = painterResource(R.drawable.ic_insert_drive_file),
                    title = stringResource(R.string.settings_browse_drive),
                    onClick = onDriveClick,
                )
                NavigationRow(
                    icon = painterResource(R.drawable.ic_cloud_upload),
                    title = stringResource(R.string.settings_auto_backup),
                    onClick = onAutoBackupClick,
                )
                NavigationRow(
                    icon = painterResource(R.drawable.ic_cloud_upload),
                    title = stringResource(R.string.settings_upload_queue),
                    onClick = onUploadQueueClick,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_upload_wifi_only),
                    description = stringResource(R.string.settings_upload_wifi_only_description),
                    checked = uiState.uploadWifiOnly,
                    onCheckedChange = onWifiOnlyChange,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_upload_charging_only),
                    description = stringResource(R.string.settings_upload_charging_only_description),
                    checked = uiState.uploadChargingOnly,
                    onCheckedChange = onChargingOnlyChange,
                )
                VideoCompressionRow(
                    current = uiState.videoCompression,
                    onChange = onVideoCompressionChange,
                )
            }
            Text(
                text = stringResource(R.string.settings_gallery_section),
                style = MaterialTheme.typography.titleMedium,
            )
            NavigationRow(
                icon = painterResource(R.drawable.ic_content_copy),
                title = stringResource(R.string.duplicates_title),
                onClick = onDuplicatesClick,
            )
            NavigationRow(
                icon = painterResource(R.drawable.ic_label),
                title = stringResource(R.string.settings_categories),
                onClick = onCategoriesClick,
            )
            SwitchRow(
                title = stringResource(R.string.settings_category_badges),
                description = stringResource(R.string.settings_category_badges_description),
                checked = uiState.showCategoryBadges,
                onCheckedChange = onCategoryBadgesChange,
            )
            if (manageMedia != null) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onManageMediaClick)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_manage_media),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(
                                    if (manageMedia) {
                                        R.string.settings_manage_media_granted
                                    } else {
                                        R.string.settings_manage_media_description
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** 업로드 영상 압축 프리셋 — 탭하면 선택 다이얼로그 */
@Composable
private fun VideoCompressionRow(
    current: VideoCompression,
    onChange: (VideoCompression) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_video_compression), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(current.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        HorizontalDivider()
    }
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.settings_video_compression)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.settings_video_compression_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    VideoCompression.entries.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    open = false
                                    onChange(preset)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(selected = preset == current, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(preset.labelRes()))
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private fun VideoCompression.labelRes(): Int = when (this) {
    VideoCompression.ORIGINAL -> R.string.settings_video_compression_original
    VideoCompression.HD_1080 -> R.string.settings_video_compression_1080
    VideoCompression.HD_720 -> R.string.settings_video_compression_720
}

@Composable
private fun NavigationRow(
    icon: androidx.compose.ui.graphics.painter.Painter,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        HorizontalDivider()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(vertical = 12.dp),
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
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        HorizontalDivider()
    }
}

@Composable
private fun AccountCard(
    uiState: SettingsUiState,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.settings_account_section),
                style = MaterialTheme.typography.titleMedium,
            )
            if (uiState.isSignedIn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AccountCircle, contentDescription = null, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        uiState.accountName?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        Text(
                            text = uiState.accountEmail.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val used = uiState.storageUsedBytes
                val limit = uiState.storageLimitBytes
                if (used != null) {
                    val usedText = Formatter.formatShortFileSize(context, used)
                    val text = if (limit != null) {
                        val limitText = Formatter.formatShortFileSize(context, limit)
                        stringResource(R.string.settings_storage_usage, usedText, limitText)
                    } else {
                        stringResource(R.string.settings_storage_usage_unlimited, usedText)
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onSignOutClick, enabled = !uiState.isBusy) {
                    Text(stringResource(R.string.settings_sign_out))
                }
            } else {
                Text(
                    text = stringResource(R.string.settings_account_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onSignInClick, enabled = !uiState.isBusy) {
                    if (uiState.isBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.settings_sign_in))
                }
            }
            Text(
                text = stringResource(R.string.settings_scope_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UploadFolderRow(
    folderName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_folder), contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_upload_folder), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = folderName ?: stringResource(R.string.settings_upload_folder_default),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        HorizontalDivider()
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenSignedOutPreview() {
    EasyGalleryTheme {
        SettingsScreen(
            uiState = SettingsUiState(),
            onBackClick = {},
            onSignInClick = {},
            onSignOutClick = {},
            onUploadFolderClick = {},
            onUploadQueueClick = {},
            onWifiOnlyChange = {},
            onChargingOnlyChange = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenSignedInPreview() {
    EasyGalleryTheme {
        SettingsScreen(
            uiState = SettingsUiState(
                isSignedIn = true,
                accountEmail = "someone@gmail.com",
                accountName = "홍길동",
                storageUsedBytes = 3_200_000_000,
                storageLimitBytes = 16_106_127_360,
                uploadFolderName = "Easy Gallery",
            ),
            onBackClick = {},
            onSignInClick = {},
            onSignOutClick = {},
            onUploadFolderClick = {},
            onUploadQueueClick = {},
            onWifiOnlyChange = {},
            onChargingOnlyChange = {},
        )
    }
}

/** 연결된 원격 저장소 목록 + 추가(`docs/MULTI_CLOUD.md` §5) */
@Composable
private fun RemoteAccountsSection(
    accounts: List<RemoteAccount>,
    remoteInfos: Map<String, RemoteAccountInfo>,
    uploadAccountId: String?,
    driveEmail: String?,
    onOpenDrive: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
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
private fun RemoteAccountRow(
    account: RemoteAccount,
    isUploadTarget: Boolean,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)?,
    usage: RemoteAccountInfo? = null,
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
                RemoteAccountKind.WEBDAV, RemoteAccountKind.SMB -> R.drawable.ic_folder
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

/** Play 서비스 상태 코드 → 안내 문구(`docs/GOOGLE_SIGN_IN_TROUBLESHOOTING.md` §3) */
internal fun signInFailureMessage(statusCode: Int): Int = when (statusCode) {
    STATUS_DEVELOPER_ERROR -> R.string.settings_sign_in_failed_developer
    STATUS_NETWORK_ERROR -> R.string.settings_sign_in_failed_network
    else -> R.string.settings_sign_in_failed_generic
}

private const val STATUS_DEVELOPER_ERROR = 10
private const val STATUS_NETWORK_ERROR = 7
