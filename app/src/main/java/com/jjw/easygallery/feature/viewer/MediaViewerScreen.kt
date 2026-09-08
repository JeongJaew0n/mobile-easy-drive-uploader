package com.jjw.easygallery.feature.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import coil3.compose.AsyncImage
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.MediaDetails
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.navigation.MediaViewerKey
import com.jjw.easygallery.core.ui.media.MediaActionEffect
import com.jjw.easygallery.feature.gallery.MoveDialog
import com.jjw.easygallery.feature.gallery.RenameDialog
import com.jjw.easygallery.feature.gallery.formatDuration
import kotlinx.coroutines.delay
import androidx.media3.common.MediaItem as Media3Item

@Composable
fun MediaViewerRoute(
    key: MediaViewerKey,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: MediaViewerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(key) { viewModel.load(key.mediaId, key.favoritesOnly) }

    MediaActionEffect(
        events = viewModel.actionEvents,
        snackbarHostState = snackbarHostState,
        onConsentResult = viewModel::onConsentResult,
    )

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is MediaViewerEvent.Enqueued -> snackbarHostState.showSnackbar(
                    resources.getQuantityString(R.plurals.gallery_upload_enqueued, event.added, event.added),
                )
                MediaViewerEvent.SignInRequired -> {
                    snackbarHostState.showSnackbar(resources.getString(R.string.gallery_sign_in_required))
                    onSettingsClick()
                }
                is MediaViewerEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // 마지막 항목까지 삭제하면 볼 것이 없으므로 닫는다
    LaunchedEffect(uiState.isLoading, uiState.items.size) {
        if (!uiState.isLoading && uiState.items.isEmpty()) onBackClick()
    }

    MediaViewerScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onPageChanged = viewModel::onPageChanged,
        onToggleFavorite = viewModel::toggleFavorite,
        onTrash = viewModel::trash,
        onDelete = viewModel::delete,
        onRename = viewModel::rename,
        onMove = viewModel::move,
        onUpload = viewModel::upload,
        onToggleInfo = viewModel::toggleInfo,
    )
}

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
            AnimatedVisibility(visible = chromeVisible) {
                ViewerTopBar(
                    item = current,
                    position = uiState.currentIndex + 1,
                    total = uiState.items.size,
                    supportsFavorites = uiState.supportsTrashAndFavorites,
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
            AnimatedVisibility(visible = chromeVisible) {
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

@Composable
private fun ImagePage(
    item: MediaItem,
    zoomState: ZoomState,
    onTap: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .zoomable(zoomState, onTap),
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPage(
    item: MediaItem,
    isCurrent: Boolean,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    settings: VideoSettings,
) {
    val context = LocalContext.current
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(Media3Item.fromUri(item.uri))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // 다른 페이지로 넘어가면 재생을 멈춘다
    LaunchedEffect(isCurrent) { if (!isCurrent) player.pause() }
    // 앱을 벗어나거나 화면이 꺼지면 소리가 계속 나지 않도록 멈춘다
    LifecycleResumeEffect(player) { onPauseOrDispose { player.pause() } }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMillis by remember { mutableLongStateOf(0L) }
    var durationMillis by remember { mutableLongStateOf(0L) }
    // 드래그 중에는 재생 위치 대신 손가락 위치를 보여준다
    var scrubFraction by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(player) {
        while (true) {
            isPlaying = player.isPlaying
            durationMillis = player.duration.coerceAtLeast(0L)
            positionMillis = player.currentPosition.coerceAtLeast(0L)
            delay(PROGRESS_POLL_MILLIS)
        }
    }
    LaunchedEffect(isPlaying) { onPlayingChange(isPlaying) }
    LaunchedEffect(player, settings.speed) { player.setPlaybackSpeed(settings.speed) }
    LaunchedEffect(player, settings.volume) { player.volume = settings.volume }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleControls,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PlayerSurface(player = player, modifier = Modifier.fillMaxSize())

        AnimatedVisibility(visible = controlsVisible, modifier = Modifier.align(Alignment.Center)) {
            IconButton(
                onClick = {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                },
                modifier = Modifier
                    .size(PLAY_BUTTON_SIZE_DP.dp)
                    .background(Color.Black.copy(alpha = OVERLAY_ALPHA), CircleShape),
            ) {
                if (isPlaying) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pause),
                        contentDescription = stringResource(R.string.viewer_pause),
                        tint = Color.White,
                        modifier = Modifier.size(PLAY_ICON_SIZE_DP.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.viewer_play),
                        tint = Color.White,
                        modifier = Modifier.size(PLAY_ICON_SIZE_DP.dp),
                    )
                }
            }
        }

        AnimatedVisibility(visible = controlsVisible, modifier = Modifier.align(Alignment.BottomCenter)) {
            val scrub = scrubFraction
            Column(Modifier.background(Color.Black.copy(alpha = OVERLAY_ALPHA))) {
                VideoSettingsRow(settings = settings)
                VideoSeekBar(
                    positionMillis = if (scrub != null) (scrub * durationMillis).toLong() else positionMillis,
                    durationMillis = durationMillis,
                    fraction = scrub ?: fractionOf(positionMillis, durationMillis),
                    onScrub = { scrubFraction = it },
                    onScrubFinished = {
                        scrubFraction?.let { player.seekTo((it * durationMillis).toLong()) }
                        scrubFraction = null
                    },
                )
            }
        }
    }
}

@Composable
private fun VideoSeekBar(
    positionMillis: Long,
    durationMillis: Long,
    fraction: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatDuration(positionMillis),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
        Slider(
            value = fraction,
            onValueChange = onScrub,
            onValueChangeFinished = onScrubFinished,
            enabled = durationMillis > 0,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Text(
            text = formatDuration(durationMillis),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

/** 영상 컨트롤 중 화면을 넘겨도 유지되는 설정 묶음 */
internal data class VideoSettings(
    val speed: Float,
    val volume: Float,
    val landscapeLocked: Boolean,
    val onSpeedChange: (Float) -> Unit,
    val onVolumeChange: (Float) -> Unit,
    val onToggleRotation: () -> Unit,
)

@Composable
private fun VideoSettingsRow(settings: VideoSettings) {
    val muted = settings.volume <= 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { settings.onVolumeChange(if (muted) 1f else 0f) }) {
            Icon(
                painter = painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                contentDescription = stringResource(if (muted) R.string.viewer_unmute else R.string.viewer_mute),
                tint = Color.White,
            )
        }
        Slider(
            value = settings.volume,
            onValueChange = settings.onVolumeChange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )
        SpeedMenuButton(speed = settings.speed, onSpeedChange = settings.onSpeedChange)
        IconButton(onClick = settings.onToggleRotation) {
            Icon(
                painter = painterResource(R.drawable.ic_screen_rotation),
                contentDescription = stringResource(
                    if (settings.landscapeLocked) R.string.viewer_rotate_auto else R.string.viewer_rotate_landscape,
                ),
                tint = if (settings.landscapeLocked) MaterialTheme.colorScheme.primary else Color.White,
            )
        }
    }
}

@Composable
private fun SpeedMenuButton(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = stringResource(R.string.viewer_speed_value, formatSpeed(speed)),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PLAYBACK_SPEEDS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.viewer_speed_value, formatSpeed(option))) },
                    trailingIcon = {
                        if (option == speed) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onSpeedChange(option)
                    },
                )
            }
        }
    }
}

/** 1.0 → "1", 1.25 → "1.25" */
internal fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0').trimEnd('.')

/** Compose 의 Context 는 ContextWrapper 로 감싸여 있을 수 있다 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun fractionOf(positionMillis: Long, durationMillis: Long): Float =
    if (durationMillis > 0) (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f) else 0f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerTopBar(
    item: MediaItem,
    position: Int,
    total: Int,
    supportsFavorites: Boolean,
    enabled: Boolean,
    onBackClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleInfo: () -> Unit,
    onRenameClick: () -> Unit,
    onMoveClick: () -> Unit,
    onUpload: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black.copy(alpha = OVERLAY_ALPHA),
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White,
        ),
        title = {
            Column {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.viewer_position, position, total),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = {
            if (supportsFavorites) {
                IconButton(onClick = onToggleFavorite, enabled = enabled) {
                    Icon(
                        imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = stringResource(
                            if (item.isFavorite) R.string.action_unfavorite else R.string.action_favorite,
                        ),
                    )
                }
            }
            IconButton(onClick = onUpload, enabled = enabled) {
                Icon(
                    painterResource(R.drawable.ic_cloud_upload),
                    contentDescription = stringResource(R.string.action_upload_to_drive),
                )
            }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onRenameClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_move)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_drive_file_move), contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onMoveClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.viewer_info)) },
                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onToggleInfo()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete_forever)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_delete_forever), contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        },
    )
}

@Composable
private fun ViewerBottomBar(
    item: MediaItem,
    details: MediaDetails?,
    showInfo: Boolean,
    supportsTrash: Boolean,
    enabled: Boolean,
    onTrash: () -> Unit,
    onDelete: () -> Unit,
    onToggleInfo: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = OVERLAY_ALPHA)),
    ) {
        if (showInfo) {
            InfoPanel(item = item, details = details)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleInfo) {
                Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.viewer_info), tint = Color.White)
            }
            IconButton(onClick = if (supportsTrash) onTrash else onDelete, enabled = enabled) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(
                        if (supportsTrash) R.string.action_trash else R.string.action_delete_forever,
                    ),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun InfoPanel(
    item: MediaItem,
    details: MediaDetails?,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        InfoRow(stringResource(R.string.viewer_info_name), item.displayName)
        InfoRow(
            stringResource(R.string.viewer_info_size),
            Formatter.formatShortFileSize(context, item.sizeBytes),
        )
        if (item.width > 0 && item.height > 0) {
            InfoRow(
                stringResource(R.string.viewer_info_dimensions),
                stringResource(R.string.viewer_info_dimensions_value, item.width, item.height),
            )
        }
        InfoRow(stringResource(R.string.viewer_info_path), item.relativePath.ifBlank { item.bucketName })
        InfoRow(stringResource(R.string.viewer_info_mime), item.mimeType)
        if (details?.hasCameraInfo == true) {
            val camera = listOfNotNull(details.cameraMake, details.cameraModel).joinToString(" ")
            if (camera.isNotBlank()) InfoRow(stringResource(R.string.viewer_info_camera), camera)
            val exposure = listOfNotNull(
                details.aperture?.let { "f/$it" },
                details.exposureTime?.let { stringResource(R.string.viewer_info_exposure_value, it) },
                details.isoSensitivity?.let { "ISO $it" },
                details.focalLength?.let { stringResource(R.string.viewer_info_focal_value, it) },
            ).joinToString(" · ")
            if (exposure.isNotBlank()) InfoRow(stringResource(R.string.viewer_info_exposure), exposure)
        }
        if (details?.hasLocation == true) {
            InfoRow(
                stringResource(R.string.viewer_info_location),
                stringResource(R.string.viewer_info_location_value, details.latitude!!, details.longitude!!),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = LABEL_ALPHA),
            modifier = Modifier.size(width = INFO_LABEL_WIDTH_DP.dp, height = INFO_ROW_HEIGHT_DP.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val OVERLAY_ALPHA = 0.55f
private const val LABEL_ALPHA = 0.7f
private const val PROGRESS_POLL_MILLIS = 400L
private const val CONTROLS_AUTO_HIDE_MILLIS = 3_000L
private const val PLAY_BUTTON_SIZE_DP = 64
private const val PLAY_ICON_SIZE_DP = 36
private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private const val INFO_LABEL_WIDTH_DP = 92
private const val INFO_ROW_HEIGHT_DP = 20
