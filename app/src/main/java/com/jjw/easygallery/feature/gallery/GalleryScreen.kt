package com.jjw.easygallery.feature.gallery

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme

@Composable
fun GalleryRoute(
    onSettingsClick: () -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // LocalContext.getString 은 Configuration 변경을 따라가지 못해 lint 가 막는다 → LocalResources 사용
    val resources = LocalResources.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.onPermissionStatusChanged(MediaPermission.status(context)) }

    // 시스템 설정에서 권한을 바꾸고 돌아온 경우를 잡기 위해 RESUME 마다 재확인
    LifecycleResumeEffect(Unit) {
        viewModel.onPermissionStatusChanged(MediaPermission.status(context))
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                GalleryEvent.SignInRequired -> {
                    val result = snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.gallery_sign_in_required),
                        actionLabel = resources.getString(R.string.action_settings),
                    )
                    if (result == SnackbarResult.ActionPerformed) onSettingsClick()
                }
                is GalleryEvent.UploadFinished -> snackbarHostState.showSnackbar(
                    if (event.failed == 0) {
                        resources.getQuantityString(
                            R.plurals.gallery_upload_done,
                            event.succeeded,
                            event.succeeded,
                        )
                    } else {
                        resources.getString(R.string.gallery_upload_done_with_failures, event.succeeded, event.failed)
                    },
                )
            }
        }
    }

    GalleryScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onSettingsClick = onSettingsClick,
        onRequestPermission = { permissionLauncher.launch(MediaPermission.required()) },
        onOpenAppSettings = {
            val packageUri = Uri.fromParts("package", context.packageName, null)
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        onToggleSelection = viewModel::toggleSelection,
        onClearSelection = viewModel::clearSelection,
        onUploadSelected = viewModel::uploadSelected,
        onCancelUpload = viewModel::cancelUpload,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryScreen(
    uiState: GalleryUiState,
    onSettingsClick: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onUploadSelected: () -> Unit,
    onCancelUpload: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val content = uiState as? GalleryUiState.Content
    val selectionMode = content?.isSelectionMode == true

    // 선택 모드에서 뒤로가기는 선택 해제
    BackHandler(enabled = selectionMode, onBack = onClearSelection)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionMode && content != null) {
                SelectionTopBar(
                    selectedCount = content.selectedIds.size,
                    uploadEnabled = content.upload == null,
                    onClear = onClearSelection,
                    onUpload = onUploadSelected,
                )
            } else {
                GalleryTopBar(itemCount = content?.itemCount, onSettingsClick = onSettingsClick)
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (uiState) {
                GalleryUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                GalleryUiState.PermissionRequired -> PermissionRequiredContent(
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                    modifier = Modifier.align(Alignment.Center),
                )

                is GalleryUiState.Error -> Text(
                    text = uiState.throwable.localizedMessage ?: uiState.throwable.toString(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )

                is GalleryUiState.Content -> Column(Modifier.fillMaxSize()) {
                    uiState.upload?.let { UploadProgressBanner(status = it, onCancel = onCancelUpload) }
                    if (uiState.isPartialAccess) {
                        PartialAccessBanner(onManageSelection = onRequestPermission)
                    }
                    if (uiState.sections.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.gallery_empty))
                        }
                    } else {
                        GalleryGrid(
                            sections = uiState.sections,
                            selectedIds = uiState.selectedIds,
                            onToggleSelection = onToggleSelection,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(
    itemCount: Int?,
    onSettingsClick: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.gallery_title))
                if (itemCount != null) {
                    Text(
                        text = pluralStringResource(R.plurals.gallery_media_count, itemCount, itemCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    uploadEnabled: Boolean,
    onClear: () -> Unit,
    onUpload: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.gallery_selected_count, selectedCount)) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_selection))
            }
        },
        actions = {
            IconButton(onClick = onUpload, enabled = uploadEnabled) {
                Icon(
                    painterResource(R.drawable.ic_cloud_upload),
                    contentDescription = stringResource(R.string.action_upload_to_drive),
                )
            }
        },
    )
}

@Composable
private fun UploadProgressBanner(
    status: UploadStatus,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.gallery_uploading,
                        status.currentIndex,
                        status.total,
                        status.currentName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
            LinearProgressIndicator(progress = { status.fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PermissionRequiredContent(
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.gallery_permission_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.gallery_permission_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onRequestPermission) {
            Text(stringResource(R.string.gallery_permission_grant))
        }
        TextButton(onClick = onOpenAppSettings) {
            Text(stringResource(R.string.gallery_permission_open_settings))
        }
    }
}

@Composable
private fun PartialAccessBanner(
    onManageSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.gallery_partial_access_message),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onManageSelection) {
                Text(stringResource(R.string.gallery_partial_access_manage))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GalleryScreenPermissionPreview() {
    EasyGalleryTheme {
        GalleryScreen(
            uiState = GalleryUiState.PermissionRequired,
            onSettingsClick = {},
            onRequestPermission = {},
            onOpenAppSettings = {},
            onToggleSelection = {},
            onClearSelection = {},
            onUploadSelected = {},
            onCancelUpload = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun GalleryScreenUploadingPreview() {
    EasyGalleryTheme {
        GalleryScreen(
            uiState = GalleryUiState.Content(
                sections = emptyList(),
                itemCount = 0,
                isPartialAccess = false,
                upload = UploadStatus(currentIndex = 2, total = 5, currentName = "IMG_0002.jpg", fraction = 0.4f),
            ),
            onSettingsClick = {},
            onRequestPermission = {},
            onOpenAppSettings = {},
            onToggleSelection = {},
            onClearSelection = {},
            onUploadSelected = {},
            onCancelUpload = {},
        )
    }
}
