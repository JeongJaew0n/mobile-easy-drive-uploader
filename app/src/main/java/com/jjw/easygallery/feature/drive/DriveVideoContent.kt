package com.jjw.easygallery.feature.drive

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.feature.gallery.formatDuration
import kotlinx.coroutines.delay
import timber.log.Timber
import androidx.media3.common.MediaItem as Media3Item

/**
 * Drive 영상을 앱 안에서 재생한다. 통째로 내려받지 않고 **스트리밍**한다 —
 * ExoPlayer 가 Range 요청으로 필요한 만큼만 당겨 가고, 인증은 [dataSourceFactory] 에
 * 이미 붙어 있다(`DriveModule.providesDriveDataSourceFactory`).
 *
 * 기기 영상 재생기(`feature/viewer/VideoPage`)와 따로 둔다. 저쪽은 배속·음량·회전까지
 * 다루는 본격 뷰어고, 여기는 "Drive 에 뭐가 들었는지 확인" 이 목적이라 재생·탐색이면 충분하다.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun DriveVideoContent(entry: DriveEntry, dataSourceFactory: DataSource.Factory) {
    val context = LocalContext.current
    var error by remember(entry.id) { mutableStateOf(false) }

    val player = remember(entry.id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(Media3Item.fromUri(driveMediaUrl(entry.id)))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // 앱을 벗어나거나 화면이 꺼지면 소리가 계속 나지 않도록 멈춘다
    LifecycleResumeEffect(player) { onPauseOrDispose { player.pause() } }

    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var positionMillis by remember { mutableLongStateOf(0L) }
    var durationMillis by remember { mutableLongStateOf(0L) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
            }

            /**
             * Drive 는 원본을 그대로 주므로 기기가 못 여는 코덱이면 여기로 온다.
             * 되풀이해봐야 같은 결과라 안내로 바꾸고 멈춘다.
             */
            override fun onPlayerError(e: PlaybackException) {
                Timber.w(e, "drive video playback failed: %s", entry.name)
                error = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        while (true) {
            durationMillis = player.duration.coerceAtLeast(0L)
            positionMillis = player.currentPosition.coerceAtLeast(0L)
            delay(PROGRESS_POLL_MILLIS)
        }
    }

    if (error) {
        PreviewMessage(stringResource(R.string.drive_video_failed))
        return
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            contentScale = ContentScale.Fit,
            keepContentOnReset = false,
        )
        // 네트워크로 당겨 오는 중이라 기기 영상보다 첫 프레임이 늦다 — 빈 검은 화면만 두면 고장으로 보인다
        if (buffering) CircularProgressIndicator()

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
                .align(Alignment.Center)
                .size(PLAY_BUTTON_SIZE_DP.dp)
                .background(Color.Black.copy(alpha = OVERLAY_ALPHA), CircleShape),
        ) {
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

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = OVERLAY_ALPHA))
                .navigationBarsPadding(),
        ) {
            val scrub = scrubFraction
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shown = if (scrub != null) (scrub * durationMillis).toLong() else positionMillis
                Text(formatDuration(shown), style = MaterialTheme.typography.labelSmall, color = Color.White)
                Slider(
                    value = scrub ?: fractionOf(positionMillis, durationMillis),
                    onValueChange = { scrubFraction = it },
                    onValueChangeFinished = {
                        scrubFraction?.let { player.seekTo((it * durationMillis).toLong()) }
                        scrubFraction = null
                    },
                    enabled = durationMillis > 0,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(formatDuration(durationMillis), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

private fun fractionOf(positionMillis: Long, durationMillis: Long): Float =
    if (durationMillis > 0) (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f) else 0f

private const val PROGRESS_POLL_MILLIS = 250L
private const val PLAY_BUTTON_SIZE_DP = 64
private const val OVERLAY_ALPHA = 0.4f
