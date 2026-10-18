package com.jjw.easygallery.feature.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.ui.image.mediaStoreThumbnail
import com.jjw.easygallery.core.ui.media.VideoSeekBar
import com.jjw.easygallery.core.ui.motion.LocalMotion
import com.jjw.easygallery.feature.gallery.formatDuration
import kotlinx.coroutines.delay
import androidx.media3.common.MediaItem as Media3Item

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun VideoPage(
    item: MediaItem,
    isCurrent: Boolean,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    settings: VideoSettings,
    /** Scaffold 하단 바 높이. 컨트롤이 하단 바·시스템 바 뒤에 깔리지 않게 그만큼 띄운다 */
    controlsBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val motion = LocalMotion.current
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

    // 재생/정지 상태는 폴링 대신 플레이어 이벤트로 받는다 (재생 끝나면 컨트롤을 다시 띄우기 위해서도 필요)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                onPlayingChange(playing)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // 위치·길이는 컨트롤이 보일 때만 읽는다. 숨겨진 동안 갱신하던 낭비(매 400ms 재구성)를 없앤다
    LaunchedEffect(player, controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        while (true) {
            durationMillis = player.duration.coerceAtLeast(0L)
            positionMillis = player.currentPosition.coerceAtLeast(0L)
            delay(PROGRESS_POLL_MILLIS)
        }
    }
    LaunchedEffect(player, settings.speed) { player.setPlaybackSpeed(settings.speed) }
    LaunchedEffect(player, settings.volume) { player.volume = settings.volume }

    // 두 번 톡: 왼쪽 반은 10초 뒤로, 오른쪽 반은 10초 앞으로(`docs/plans/video-controls/spec.md`). 한 번 톡은 컨트롤 보이기/숨기기
    val latestToggle by rememberUpdatedState(onToggleControls)
    var seekHint by remember { mutableStateOf<SeekHint?>(null) }
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(SEEK_HINT_MILLIS)
            seekHint = null
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(player) {
                detectTapGestures(
                    onTap = { latestToggle() },
                    onDoubleTap = { offset ->
                        val forward = offset.x > size.width / 2
                        player.seekTo(seekTarget(player.currentPosition, player.duration, forward))
                        positionMillis = player.currentPosition.coerceAtLeast(0L)
                        seekHint = SeekHint(forward, System.nanoTime())
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // ContentFrame 이 영상 크기(PresentationState.videoSizeDp)에 맞춰 표면을 Fit 으로 배치한다 — PlayerSurface 를
        // fillMaxSize 로 두면 영상이 화면 비율로 늘어난다. TextureView 라야 페이저 이동·스와이프 닫기의 graphicsLayer
        // 변형이 표면에도 적용된다(SurfaceView 는 별도 윈도우 레이어). 첫 프레임 전에는 그리드 썸네일을 셔터로 보여준다.
        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            contentScale = ContentScale.Fit,
            keepContentOnReset = false,
            shutter = { VideoShutter(item) },
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = motion.enterScale(),
            exit = motion.exitScale(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            IconButton(
                onClick = { togglePlay(player) },
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

        AnimatedVisibility(
            visible = controlsVisible,
            enter = motion.enterFromBottom(),
            exit = motion.exitToBottom(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val scrub = scrubFraction
            val shownMillis = if (scrub != null) (scrub * durationMillis).toLong() else positionMillis
            // 회색 상자 대신 아래로 갈수록 어두워지는 그림자 — 영상을 덜 가린다
            Column(
                Modifier
                    .padding(bottom = controlsBottomPadding)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = SCRIM_ALPHA))),
                    )
                    // 가로 모드에서 측면 시스템 바·컷아웃을 피한다(하단은 Scaffold 의 하단 바가 이미 소비)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(top = SCRIM_TOP_DP.dp),
            ) {
                VideoSeekBar(
                    fraction = scrub ?: fractionOf(positionMillis, durationMillis),
                    onScrub = { scrubFraction = it },
                    onScrubFinished = {
                        scrubFraction?.let { player.seekTo((it * durationMillis).toLong()) }
                        scrubFraction = null
                    },
                    enabled = durationMillis > 0,
                    scrubLabel = formatDuration(shownMillis),
                    label = stringResource(R.string.viewer_seek_label),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                VideoControlRow(
                    isPlaying = isPlaying,
                    onPlayPause = { togglePlay(player) },
                    time = stringResource(
                        R.string.viewer_time_progress,
                        formatDuration(shownMillis),
                        formatDuration(durationMillis),
                    ),
                    settings = settings,
                )
            }
        }

        SeekHintBubble(seekHint)
    }
}

/** 두 번 톡 직후 그 쪽에 잠깐 뜨는 "−10초"/"+10초". [at] 은 같은 쪽을 연달아 쳐도 다시 뜨게 하는 값 */
private data class SeekHint(val forward: Boolean, val at: Long)

@Composable
private fun BoxScope.SeekHintBubble(hint: SeekHint?) {
    val motion = LocalMotion.current
    // 사라지는 동안에도 마지막 쪽을 보인다
    var last by remember { mutableStateOf(hint) }
    if (hint != null) last = hint
    AnimatedVisibility(
        visible = hint != null,
        enter = motion.enterFade(),
        exit = motion.exitFade(),
        modifier = Modifier
            .align(if (last?.forward == true) Alignment.CenterEnd else Alignment.CenterStart)
            .padding(horizontal = SEEK_HINT_INSET_DP.dp),
    ) {
        Text(
            text = stringResource(
                if (last?.forward == true) R.string.viewer_seek_forward_hint else R.string.viewer_seek_back_hint,
            ),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier
                .background(Color.Black.copy(alpha = OVERLAY_ALPHA), CircleShape)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

private fun togglePlay(player: Player) {
    if (player.isPlaying) {
        player.pause()
    } else {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }
}

/**
 * 두 번 톡의 목표 위치 — [SEEK_STEP_MILLIS] 만큼, 처음과 끝을 넘지 않게. 길이를 아직 모르면(음수·0) 앞쪽 끝만 자른다
 */
internal fun seekTarget(current: Long, duration: Long, forward: Boolean): Long {
    val target = current + if (forward) SEEK_STEP_MILLIS else -SEEK_STEP_MILLIS
    val upper = if (duration > 0) duration else Long.MAX_VALUE
    return target.coerceIn(0L, upper)
}

/** 첫 프레임이 나오기 전까지 보여주는 썸네일. 그리드와 같은 캐시 키라 디코딩 없이 바로 뜬다 */
@Composable
private fun VideoShutter(item: MediaItem) {
    AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(item.uri)
            .memoryCacheKey(thumbnailCacheKey(item))
            .placeholderMemoryCacheKey(thumbnailCacheKey(item))
            .mediaStoreThumbnail()
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
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

/**
 * 재생바 아래 한 줄: ❚❚/▶ · "0:03 / 0:08" ……… 🔊 · 1x · ⟲. 음량 막대는 없다 — 🔊 는 음소거만, 음량은 폰 버튼으로
 * (`docs/plans/video-controls/spec.md`).
 */
@Composable
private fun VideoControlRow(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    time: String,
    settings: VideoSettings,
) {
    val muted = settings.volume <= 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPlayPause) {
            if (isPlaying) {
                Icon(
                    painter = painterResource(R.drawable.ic_pause),
                    contentDescription = stringResource(R.string.viewer_pause),
                    tint = Color.White,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.viewer_play),
                    tint = Color.White,
                )
            }
        }
        Text(text = time, style = MaterialTheme.typography.labelLarge, color = Color.White)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { settings.onVolumeChange(if (muted) 1f else 0f) }) {
            Icon(
                painter = painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                contentDescription = stringResource(if (muted) R.string.viewer_unmute else R.string.viewer_mute),
                tint = Color.White,
            )
        }
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

private fun fractionOf(positionMillis: Long, durationMillis: Long): Float =
    if (durationMillis > 0) (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f) else 0f

private const val PROGRESS_POLL_MILLIS = 250L

private const val SEEK_STEP_MILLIS = 10_000L
private const val SEEK_HINT_MILLIS = 600L
private const val SEEK_HINT_INSET_DP = 48
private const val SCRIM_ALPHA = 0.6f
private const val SCRIM_TOP_DP = 24

private const val PLAY_BUTTON_SIZE_DP = 64

private const val PLAY_ICON_SIZE_DP = 36

private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
