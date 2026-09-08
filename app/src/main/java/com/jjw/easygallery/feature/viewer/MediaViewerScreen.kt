package com.jjw.easygallery.feature.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.jjw.easygallery.core.ui.motion.LocalMotion
import com.jjw.easygallery.feature.gallery.MoveDialog
import com.jjw.easygallery.feature.gallery.RenameDialog
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaViewerScreen(
    uiState: MediaViewerUiState,
    onBackClick: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onTrash: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onMove: (String) -> Unit,
    onUpload: () -> Unit,
    onToggleInfo: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    if (uiState.isLoading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val current = uiState.current ?: return

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var videoPlaying by remember { mutableStateOf(false) }
    val motion = LocalMotion.current
    // 항목을 넘겨도 유지되도록 화면 수준에서 들고 있는다
    var playbackSpeed by rememberSaveable { mutableFloatStateOf(1f) }
    var volume by rememberSaveable { mutableFloatStateOf(1f) }
    var landscapeLocked by rememberSaveable { mutableStateOf(false) }

    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, landscapeLocked) {
        activity?.requestedOrientation = if (landscapeLocked) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        // 상세보기를 나가면 앱 기본 회전 동작으로 되돌린다
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showMove by rememberSaveable { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = uiState.currentIndex) { uiState.items.size }
    val zoomState = rememberZoomState(current.id)

    // 재생 중에는 잠시 뒤 컨트롤을 숨겨 영상에 집중하게 한다
    LaunchedEffect(chromeVisible, videoPlaying) {
        if (chromeVisible && videoPlaying) {
            delay(CONTROLS_AUTO_HIDE_MILLIS)
            chromeVisible = false
        }
    }
    // 항목이 바뀌면 재생 상태를 초기화하고 컨트롤을 다시 보여준다
    LaunchedEffect(current.id) {
        videoPlaying = false
        chromeVisible = true
    }

    // 스와이프 결과를 ViewModel 에 알린다 (편집 대상·정보 패널이 현재 항목을 따라가도록)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect(onPageChanged)
    }
    // 삭제·복원으로 목록이 바뀌어 인덱스가 어긋나면 맞춘다
    LaunchedEffect(uiState.currentIndex) {
        if (uiState.currentIndex != pagerState.currentPage) pagerState.scrollToPage(uiState.currentIndex)
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = motion.enterFromTop(),
                exit = motion.exitToTop(),
            ) {
                ViewerTopBar(
                    item = current,
                    position = uiState.currentIndex + 1,
                    total = uiState.items.size,
                    supportsFavorites = uiState.supportsTrashAndFavorites,
                    isUploaded = uiState.isUploaded,
                    enabled = !uiState.isMutating,
                    onBackClick = onBackClick,
                    onToggleFavorite = onToggleFavorite,
                    onToggleInfo = onToggleInfo,
                    onRenameClick = { showRename = true },
                    onMoveClick = { showMove = true },
                    onUpload = onUpload,
                    onDelete = onDelete,
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = motion.enterFromBottom(),
                exit = motion.exitToBottom(),
            ) {
                ViewerBottomBar(
                    item = current,
                    details = uiState.details,
                    showInfo = uiState.showInfo,
                    supportsTrash = uiState.supportsTrashAndFavorites,
                    enabled = !uiState.isMutating,
                    onTrash = onTrash,
                    onDelete = onDelete,
                    onToggleInfo = onToggleInfo,
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                // 확대 상태에서는 스와이프 대신 팬 제스처를 쓴다
                userScrollEnabled = !zoomState.isZoomed,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = uiState.items.getOrNull(page) ?: return@HorizontalPager
                if (item.isVideo) {
                    VideoPage(
                        item = item,
                        isCurrent = page == pagerState.settledPage,
                        controlsVisible = chromeVisible,
                        onToggleControls = { chromeVisible = !chromeVisible },
                        onPlayingChange = { playing ->
                            if (page == pagerState.settledPage) videoPlaying = playing
                        },
                        settings = VideoSettings(
                            speed = playbackSpeed,
                            volume = volume,
                            landscapeLocked = landscapeLocked,
                            onSpeedChange = { playbackSpeed = it },
                            onVolumeChange = { volume = it },
                            onToggleRotation = { landscapeLocked = !landscapeLocked },
                        ),
                    )
                } else {
                    // 확대 중에는 스와이프가 막혀 다른 페이지가 보이지 않으므로 상태 하나를 공유해도 된다
                    ImagePage(
                        item = item,
                        zoomState = zoomState,
                        onTap = { chromeVisible = !chromeVisible },
                    )
                }
            }
            if (uiState.isMutating) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .padding(innerPadding),
                )
            }
        }
    }

    if (showRename) {
        RenameDialog(
            currentName = current.displayName,
            onDismiss = { showRename = false },
            onConfirm = { name ->
                showRename = false
                onRename(name)
            },
        )
    }
    if (showMove) {
        MoveDialog(
            albums = uiState.albums,
            onDismiss = { showMove = false },
            onConfirm = { path ->
                showMove = false
                onMove(path)
            },
        )
    }
}

/** Compose 의 Context 는 ContextWrapper 로 감싸여 있을 수 있다 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val CONTROLS_AUTO_HIDE_MILLIS = 3_000L
