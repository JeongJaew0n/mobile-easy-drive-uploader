package com.jjw.easygallery.feature.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.DateRange
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.navigation.HeroOrigin
import com.jjw.easygallery.core.ui.media.MediaActionEffect

@Composable
fun GalleryRoute(
    onSettingsClick: () -> Unit,
    onUploadQueueClick: () -> Unit,
    onTrashClick: () -> Unit,
    onDriveClick: () -> Unit,
    onDuplicatesClick: () -> Unit,
    onOpenItem: (item: MediaItem, favoritesOnly: Boolean, range: DateRange?, hero: HeroOrigin?) -> Unit,
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

    // 업로드 진행 알림을 위해 13+ 에서는 알림 권한을 먼저 묻고, 결과와 무관하게 큐에 넣는다
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.uploadSelected() }
    val startUpload = {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.uploadSelected()
        }
    }

    // 편집 동의 다이얼로그 + 결과 스낵바 (공용)
    MediaActionEffect(
        events = viewModel.actionEvents,
        snackbarHostState = snackbarHostState,
        onConsentResult = viewModel::onConsentResult,
        onActionDone = { viewModel.clearSelection() },
    )

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
                is GalleryEvent.Enqueued -> snackbarHostState.showSnackbar(
                    if (event.skipped == 0) {
                        resources.getQuantityString(R.plurals.gallery_upload_enqueued, event.added, event.added)
                    } else {
                        resources.getString(R.string.gallery_upload_enqueued_skipped, event.added, event.skipped)
                    },
                )
                is GalleryEvent.Error -> snackbarHostState.showSnackbar(event.message)
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
        onSelectionChange = viewModel::setSelection,
        onClearSelection = viewModel::clearSelection,
        onUploadSelected = startUpload,
        onCancelUpload = viewModel::cancelUploads,
        onUploadQueueClick = onUploadQueueClick,
        onFavoritesOnlyChange = viewModel::setFavoritesOnly,
        onNotBackedUpOnlyChange = viewModel::setNotBackedUpOnly,
        onDateRangeChange = viewModel::setDateRange,
        onTrashClick = onTrashClick,
        onDriveClick = onDriveClick,
        onDuplicatesClick = onDuplicatesClick,
        onOpenItem = onOpenItem,
        actions = GalleryActionCallbacks(
            onTrash = viewModel::trashSelected,
            onDelete = viewModel::deleteSelected,
            onToggleFavorite = viewModel::toggleFavoriteSelected,
            onRename = viewModel::renameSelected,
            onMove = viewModel::moveSelected,
        ),
    )
}

/** 선택 항목 편집 콜백 묶음 — 파라미터 폭발 방지 */
internal data class GalleryActionCallbacks(
    val onTrash: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onToggleFavorite: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onMove: (String) -> Unit = {},
)
