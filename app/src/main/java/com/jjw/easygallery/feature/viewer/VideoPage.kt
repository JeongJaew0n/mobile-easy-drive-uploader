package com.jjw.easygallery.feature.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.MediaItem
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

        AnimatedVisibility(
            visible = controlsVisible,
            enter = motion.enterScale(),
            exit = motion.exitScale(),
            modifier = Modifier.align(Alignment.Center),
        ) {
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

        AnimatedVisibility(
            visible = controlsVisible,
            enter = motion.enterFromBottom(),
            exit = motion.exitToBottom(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
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

private fun fractionOf(positionMillis: Long, durationMillis: Long): Float =
    if (durationMillis > 0) (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f) else 0f

private const val PROGRESS_POLL_MILLIS = 250L

private const val PLAY_BUTTON_SIZE_DP = 64

private const val PLAY_ICON_SIZE_DP = 36

private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
