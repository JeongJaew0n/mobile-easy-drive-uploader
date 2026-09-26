package com.jjw.easygallery.feature.gallery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    onHiddenClick: () -> Unit,
    onOpenItem: (item: MediaItem, filters: ViewerFilters, hero: HeroOrigin?) -> Unit,
    onManageCategories: () -> Unit = {},
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
    var pendingTarget by remember { mutableStateOf<UploadTargetOption?>(null) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.uploadSelected(pendingTarget) }
    val startUploadTo: (UploadTargetOption?) -> Unit = { target ->
        pendingTarget = target
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.uploadSelected(target)
        }
    }
    val startUpload = { startUploadTo(null) }
    val uploadTargets by viewModel.uploadTargets.collectAsStateWithLifecycle()

    // 다른 계정 업로드 — 계정 선택 창(docs/plans/guest-account-upload)
    val guestAvailable by viewModel.guestUploadAvailable.collectAsStateWithLifecycle()
    val guestCleanupEmail by viewModel.guestCleanupEmail.collectAsStateWithLifecycle()
    val guestChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        // 닫았으면 아무것도 하지 않는다. Play 서비스가 취소 인텐트를 돌려줘도 저장소가 한 번 더 거른다
        if (result.resultCode == Activity.RESULT_OK) viewModel.onGuestPickResult(result.data)
    }

    // 편집 동의 다이얼로그 + 결과 스낵바 (공용)
    MediaActionEffect(
        events = viewModel.actionEvents,
        snackbarHostState = snackbarHostState,
        onConsentResult = viewModel::onConsentResult,
        onActionDone = viewModel::onActionDone,
    )

    // 시스템 설정에서 권한을 바꾸고 돌아온 경우를 잡기 위해 RESUME 마다 재확인
    LifecycleResumeEffect(Unit) {
        viewModel.onPermissionStatusChanged(MediaPermission.status(context))
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            showGalleryEvent(
                event = event,
                snackbarHostState = snackbarHostState,
                resources = resources,
                onSettingsClick = onSettingsClick,
                onGuestChooser = { pendingIntent ->
                    guestChooserLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
                },
            )
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
        uploadTargets = uploadTargets,
        onUploadSelectedTo = { startUploadTo(it) },
        onCancelUpload = viewModel::cancelUploads,
        onUploadQueueClick = onUploadQueueClick,
        onTabChange = viewModel::setTab,
        onFavoritesOnlyChange = viewModel::setFavoritesOnly,
        onNotBackedUpOnlyChange = viewModel::setNotBackedUpOnly,
        onDateRangeChange = viewModel::setDateRange,
        onSelectAllVisible = viewModel::toggleSelectAllVisible,
        onTrashUploaded = viewModel::trashUploadedVisible,
        onCategoryFilterChange = viewModel::setCategoryFilter,
        onManageCategories = onManageCategories,
        onCreateCategory = viewModel::createCategory,
        onAssignCategories = viewModel::assignCategoriesToSelection,
        onTrashClick = onTrashClick,
        onDriveClick = onDriveClick,
        onDuplicatesClick = onDuplicatesClick,
        onHiddenClick = onHiddenClick,
        onHideSelected = viewModel::hideSelected,
        onOpenItem = onOpenItem,
        guest = GuestUploadUi(
            available = guestAvailable,
            cleanupEmail = guestCleanupEmail,
            onStart = viewModel::startGuestUpload,
            onOpenAccountSettings = {
                // 앱은 기기 계정을 지울 수 없다 — 기기의 계정 목록을 연다
                context.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                viewModel.dismissGuestCleanup()
            },
            onDismissCleanup = viewModel::dismissGuestCleanup,
        ),
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
