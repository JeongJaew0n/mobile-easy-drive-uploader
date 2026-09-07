package com.jjw.easygallery.feature.gallery

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.onPermissionStatusChanged(MediaPermission.status(context)) }

    // 시스템 설정에서 권한을 바꾸고 돌아온 경우를 잡기 위해 RESUME 마다 재확인
    LifecycleResumeEffect(Unit) {
        viewModel.onPermissionStatusChanged(MediaPermission.status(context))
        onPauseOrDispose { }
    }

    GalleryScreen(
        uiState = uiState,
        onSettingsClick = onSettingsClick,
        onRequestPermission = { permissionLauncher.launch(MediaPermission.required()) },
        onOpenAppSettings = {
            val packageUri = Uri.fromParts("package", context.packageName, null)
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryScreen(
    uiState: GalleryUiState,
    onSettingsClick: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.gallery_title))
                        if (uiState is GalleryUiState.Content) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.gallery_media_count,
                                    uiState.itemCount,
                                    uiState.itemCount,
                                ),
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
                    if (uiState.isPartialAccess) {
                        PartialAccessBanner(onManageSelection = onRequestPermission)
                    }
                    if (uiState.sections.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.gallery_empty))
                        }
                    } else {
                        GalleryGrid(sections = uiState.sections, modifier = Modifier.fillMaxSize())
                    }
                }
            }
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
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun GalleryScreenEmptyPreview() {
    EasyGalleryTheme {
        GalleryScreen(
            uiState = GalleryUiState.Content(sections = emptyList(), itemCount = 0, isPartialAccess = true),
            onSettingsClick = {},
            onRequestPermission = {},
            onOpenAppSettings = {},
        )
    }
}
