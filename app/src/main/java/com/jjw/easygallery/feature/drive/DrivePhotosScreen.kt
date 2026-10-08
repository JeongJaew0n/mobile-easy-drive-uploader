package com.jjw.easygallery.feature.drive

import android.app.PendingIntent
import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import com.jjw.easygallery.R
import com.jjw.easygallery.core.common.text.displayMessage
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveMediaOrder
import com.jjw.easygallery.core.domain.model.DriveMediaScope
import com.jjw.easygallery.core.navigation.DrivePhotosKey
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme

/**
 * Drive 사진 화면(`docs/plans/drive-photos/spec.md` §3.2). [DrivePhotosKey.openFileId] 가 있으면 목록을 읽자마자
 * 그 사진의 넘겨 보기를 띄운다 — 백업 칸의 썸네일 띠에서 들어온 경우다.
 */
@Composable
fun DrivePhotosRoute(
    key: DrivePhotosKey,
    onBackClick: () -> Unit,
    onOpenFolders: () -> Unit,
    /** 받은 사진을 기기 상세보기로 연다(스낵바 "보기") */
    onOpenDeviceMedia: (mediaId: Long) -> Unit = {},
    viewModel: DrivePhotosViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    var viewingId by rememberSaveable { mutableStateOf(key.openFileId) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            showPhotosEvent(event, snackbarHostState, resources, viewModel, onOpenDeviceMedia)
        }
    }
    val authRecoveryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onAuthRecoveryResult(result.data) }

    val content = uiState as? DrivePhotosUiState.Content
    BackHandler(enabled = content?.isSelecting == true) { viewModel.clearSelection() }

    DrivePhotosScreen(
        uiState = uiState,
        imageLoader = viewModel.driveImageLoader,
        snackbarHostState = snackbarHostState,
        onRecoverAuth = { pendingIntent ->
            authRecoveryLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
        },
        actions = DrivePhotosActions(
            onBackClick = onBackClick,
            onOpenFolders = onOpenFolders,
            onOpen = { entry -> viewingId = entry.id },
            onToggleSelect = viewModel::toggleSelection,
            onSelectAll = viewModel::selectAll,
            onClearSelection = viewModel::clearSelection,
            onDownloadSelected = viewModel::downloadSelected,
            onTrashSelected = viewModel::trashSelected,
            onScope = viewModel::setScope,
            onFilter = viewModel::setFilter,
            onOrder = viewModel::setOrder,
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
        ),
    )

    val id = viewingId
    // 처음 열 때만 그 사진이 목록에 있어야 한다(백업 칸에서 들어오면 첫 쪽을 기다린다). 열린 뒤에는 그 사진을 휴지통으로
    // 보내도 닫지 않고 옆 사진으로 넘어간다
    var pagerOpen by remember(id) { mutableStateOf(false) }
    val canShow = pagerOpen || content?.visible?.any { it.id == id } == true
    if (content != null && id != null && canShow) {
        LaunchedEffect(id) { pagerOpen = true }
        key(id) {
            DriveMediaPager(
                entries = content.visible,
                initialId = id,
                imageLoader = viewModel.driveImageLoader,
                dataSourceFactory = viewModel.driveDataSourceFactory,
                onDismiss = { viewingId = null },
                actions = DriveViewerActions(
                    onDownload = { viewModel.download(listOf(it)) },
                    onTrash = if (content.canTrash) ({ viewModel.trash(listOf(it)) }) else null,
                    onOpenInDrive = { entry ->
                        entry.webViewLink?.let { context.openDriveLink(it, content.accountEmail) }
                    },
                ),
                onDeviceIds = content.onDeviceIds,
                onNearEnd = viewModel::loadMore,
                snackbarHostState = snackbarHostState,
            )
        }
    }
}

/** Drive 사진 화면의 단추들 — 파라미터 폭발 방지 */
internal data class DrivePhotosActions(
    val onBackClick: () -> Unit = {},
    val onOpenFolders: () -> Unit = {},
    val onOpen: (DriveEntry) -> Unit = {},
    val onToggleSelect: (DriveEntry) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onDownloadSelected: () -> Unit = {},
    val onTrashSelected: () -> Unit = {},
    val onScope: (DriveMediaScope) -> Unit = {},
    val onFilter: (DrivePhotoFilter) -> Unit = {},
    val onOrder: (DriveMediaOrder) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
)

private suspend fun showPhotosEvent(
    event: DrivePhotosEvent,
    snackbarHostState: SnackbarHostState,
    resources: Resources,
    viewModel: DrivePhotosViewModel,
    onOpenDeviceMedia: (Long) -> Unit,
) {
    when (event) {
        is DrivePhotosEvent.Trashed -> {
            val result = snackbarHostState.showSnackbar(
                message = resources.getString(R.string.drive_batch_trashed, event.entries.size),
                actionLabel = resources.getString(R.string.action_undo),
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.restore(event.entries)
        }
        DrivePhotosEvent.Restored -> snackbarHostState.showSnackbar(resources.getString(R.string.drive_restored))
        is DrivePhotosEvent.Failed ->
            snackbarHostState.showSnackbar(resources.getString(R.string.drive_batch_failed, event.count))
        is DrivePhotosEvent.DownloadStarted ->
            snackbarHostState.showSnackbar(resources.getString(R.string.drive_download_started, event.count))
        is DrivePhotosEvent.DownloadFinished -> {
            val mediaId = event.result.lastMediaId
            val result = snackbarHostState.showSnackbar(
                message = downloadFinishedText(event.result, resources),
                actionLabel = mediaId?.let { resources.getString(R.string.action_view) },
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed && mediaId != null) onOpenDeviceMedia(mediaId)
        }
        is DrivePhotosEvent.Error -> snackbarHostState.showSnackbar(event.error.displayMessage(resources))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrivePhotosScreen(
    uiState: DrivePhotosUiState,
    actions: DrivePhotosActions,
    imageLoader: ImageLoader?,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onRecoverAuth: (PendingIntent) -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = { DrivePhotosTopBar(uiState, actions) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (uiState) {
                DrivePhotosUiState.Loading -> Centered { CircularProgressIndicator() }
                DrivePhotosUiState.NotConnected -> Centered {
                    Message(stringResource(R.string.drive_photos_not_connected))
                }
                is DrivePhotosUiState.Content -> {
                    DrivePhotosFilterBar(uiState, actions)
                    if (uiState.isMutating) LinearProgressIndicator(Modifier.fillMaxWidth())
                    DrivePhotosBody(uiState, actions, imageLoader, onRecoverAuth)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrivePhotosBody(
    state: DrivePhotosUiState.Content,
    actions: DrivePhotosActions,
    imageLoader: ImageLoader?,
    onRecoverAuth: (PendingIntent) -> Unit,
) {
    when {
        state.error != null -> Centered {
            Message(state.error.displayMessage(LocalResources.current), color = MaterialTheme.colorScheme.error)
            // 권한이 끊긴 것이면 다시 시도는 같은 오류만 반복한다 — 재동의를 띄워야 빠져나간다
            val recovery = state.authRecovery
            if (recovery != null) {
                TextButton(onClick = { onRecoverAuth(recovery) }) { Text(stringResource(R.string.drive_reauthorize)) }
            } else {
                TextButton(onClick = actions.onRefresh) { Text(stringResource(R.string.action_retry)) }
            }
        }
        state.isLoading -> Centered {
            CircularProgressIndicator()
            state.loadedCount?.let { Message(stringResource(R.string.drive_photos_loading_all, it)) }
        }
        state.visible.isEmpty() && !state.isLoadingMore -> Centered { Message(stringResource(emptyMessage(state))) }
        imageLoader == null -> Unit
        else -> PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = actions.onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            DrivePhotosGrid(
                state = state,
                imageLoader = imageLoader,
                onClick = { entry -> if (state.isSelecting) actions.onToggleSelect(entry) else actions.onOpen(entry) },
                onLongClick = actions.onToggleSelect,
                onLoadMore = actions.onLoadMore,
            )
        }
    }
}

private fun emptyMessage(state: DrivePhotosUiState.Content): Int = when (state.filter) {
    DrivePhotoFilter.ALL -> R.string.drive_photos_empty
    DrivePhotoFilter.NOT_ON_DEVICE -> R.string.drive_photos_empty_not_on_device
    DrivePhotoFilter.VIDEOS -> R.string.drive_photos_empty_videos
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) { content() }
    }
}

@Composable
private fun Message(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text = text, color = color, textAlign = TextAlign.Center)
}

@Preview
@Composable
private fun DrivePhotosScreenEmptyPreview() {
    EasyGalleryTheme {
        DrivePhotosScreen(
            uiState = DrivePhotosUiState.Content(isLoading = false),
            actions = DrivePhotosActions(),
            imageLoader = null,
        )
    }
}
