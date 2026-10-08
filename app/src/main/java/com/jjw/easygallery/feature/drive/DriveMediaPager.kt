package com.jjw.easygallery.feature.drive

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.datasource.DataSource
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.ui.motion.LocalMotion

/**
 * Drive 사진·영상을 **좌우로 넘겨** 본다(`docs/plans/drive-photos/spec.md` §3.3). Drive 사진 화면과 폴더 보기가 함께 쓴다.
 *
 * - 사진: 큰 썸네일을 먼저 깔고 원본을 위에 덮는다 — 원본(수 MB)이 오는 동안 빈 화면이 아니다.
 * - 영상: 멈춰 선 쪽에만 플레이어를 만든다. 옆 쪽까지 만들면 넘기는 사이 소리가 겹치고 데이터를 쓴다.
 * - 누르면 위 막대를 숨기고 보인다. 단추는 위에 모은다 — 아래는 영상 조작 막대 자리다.
 *
 * [onDeviceIds] 가 null 이면 "이 기기에 있는지" 를 보이지 않는다(폴더 보기는 모른다).
 */
@Composable
internal fun DriveMediaPager(
    entries: List<DriveEntry>,
    initialId: String,
    imageLoader: ImageLoader,
    dataSourceFactory: DataSource.Factory,
    onDismiss: () -> Unit,
    actions: DriveViewerActions = DriveViewerActions(),
    onDeviceIds: Set<String>? = null,
    onNearEnd: () -> Unit = {},
    /**
     * 받기·휴지통의 결과를 **이 창 안에** 띄운다. 화면(Scaffold)의 스낵바는 이 전체 화면 창에 가려 보이지 않는다
     * (2026-10-08 기기: 받았는지도, 실행 취소가 있는지도 몰랐다 — `docs/plans/drive-feedback/spec.md` §1)
     */
    snackbarHostState: SnackbarHostState? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        if (entries.isEmpty()) {
            // 마지막 한 장을 휴지통으로 보냈다
            LaunchedEffect(Unit) { onDismiss() }
            return@Dialog
        }
        val start = remember { entries.indexOfFirst { it.id == initialId }.coerceAtLeast(0) }
        val pagerState = rememberPagerState(initialPage = start) { entries.size }
        var chrome by remember { mutableStateOf(true) }
        val currentIndex = pagerState.currentPage.coerceAtMost(entries.lastIndex)
        LaunchedEffect(currentIndex, entries.size) {
            if (currentIndex >= entries.size - NEAR_END) onNearEnd()
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                key = { entries[it].id },
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val entry = entries[page]
                when {
                    entry.isVideo && page == pagerState.settledPage ->
                        DriveVideoContent(entry = entry, dataSourceFactory = dataSourceFactory)
                    entry.isVideo -> VideoPoster(entry, imageLoader)
                    else -> DriveImagePage(entry, imageLoader, onTap = { chrome = !chrome })
                }
            }
            val motion = LocalMotion.current
            AnimatedVisibility(
                visible = chrome,
                enter = motion.enterFade(),
                exit = motion.exitFade(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ViewerTopBar(
                    entry = entries[currentIndex],
                    onDevice = onDeviceIds?.let { entries[currentIndex].id in it },
                    actions = actions,
                    onDismiss = onDismiss,
                )
            }
            // 영상 조작 막대 위로 올린다
            snackbarHostState?.let { state ->
                SnackbarHost(
                    hostState = state,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = SNACKBAR_BOTTOM_DP.dp),
                )
            }
        }
    }
}

/** 넘겨 보기에서 할 수 있는 것. null 이면 그 단추가 없다(보기 전용 폴더 등) */
internal data class DriveViewerActions(
    val onDownload: ((DriveEntry) -> Unit)? = null,
    val onTrash: ((DriveEntry) -> Unit)? = null,
    val onOpenInDrive: ((DriveEntry) -> Unit)? = null,
)

@Composable
private fun DriveImagePage(entry: DriveEntry, imageLoader: ImageLoader, onTap: () -> Unit) {
    var loading by remember(entry.id) { mutableStateOf(true) }
    var failed by remember(entry.id) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        entry.thumbnailLink?.let { link ->
            AsyncImage(
                model = thumbnailUrl(link, PREVIEW_PX),
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!failed) {
            AsyncImage(
                model = driveMediaUrl(entry.id),
                contentDescription = entry.name,
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    loading = state is AsyncImagePainter.State.Loading
                    if (state is AsyncImagePainter.State.Error) failed = true
                },
            )
        }
        when {
            // 썸네일이라도 보이면 원본을 못 받았다고 덮지 않는다
            failed && entry.thumbnailLink == null -> PreviewMessage(stringResource(R.string.drive_preview_failed))
            loading && !failed -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/** 옆 쪽의 영상 — 플레이어 대신 썸네일과 재생 표시 */
@Composable
private fun VideoPoster(entry: DriveEntry, imageLoader: ImageLoader) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        entry.thumbnailLink?.let { link ->
            AsyncImage(
                model = thumbnailUrl(link, PREVIEW_PX),
                contentDescription = entry.name,
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(56.dp)
                .background(Color.Black.copy(alpha = SCRIM_ALPHA), CircleShape),
        )
    }
}

@Composable
private fun ViewerTopBar(
    entry: DriveEntry,
    onDevice: Boolean?,
    actions: DriveViewerActions,
    onDismiss: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = SCRIM_ALPHA), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = viewerSubtitle(entry, onDevice),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = SUBTITLE_ALPHA),
                // 단추 셋에 밀려 한 줄이면 "이 기기에는 없음" 이 잘린다(2026-10-08 기기) — 그게 가장 중요한 말이다
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions.onDownload?.let { download ->
            IconButton(onClick = { download(entry) }) {
                Icon(
                    painterResource(R.drawable.ic_file_download),
                    contentDescription = stringResource(R.string.drive_menu_download),
                    tint = Color.White,
                )
            }
        }
        actions.onTrash?.let { trash ->
            IconButton(onClick = { trash(entry) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.drive_photos_trash),
                    tint = Color.White,
                )
            }
        }
        actions.onOpenInDrive?.takeIf { entry.webViewLink != null }?.let { open ->
            IconButton(onClick = { open(entry) }) {
                Icon(
                    painterResource(R.drawable.ic_insert_drive_file),
                    contentDescription = stringResource(R.string.drive_photos_open_in_drive),
                    tint = Color.White,
                )
            }
        }
    }
}

/** "2026. 9. 12. 18:15 · 3.1MB · 이 기기에는 없음" — 찍은 시각이 없으면 올린 시각 */
@Composable
private fun viewerSubtitle(entry: DriveEntry, onDevice: Boolean?): String {
    val context = LocalContext.current
    val deviceLabel = when (onDevice) {
        true -> stringResource(R.string.drive_photos_on_device)
        false -> stringResource(R.string.drive_photos_not_on_device)
        null -> null
    }
    val time = entry.takenTimeMillis ?: entry.createdTimeMillis ?: entry.modifiedTimeMillis
    return listOfNotNull(
        time?.let {
            DateFormat.getMediumDateFormat(context).format(it) + " " + DateFormat.getTimeFormat(context).format(it)
        },
        entry.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
        deviceLabel,
    ).joinToString(" · ")
}

/** 넘겨 보기에서 까는 썸네일 크기 — 화면을 덮을 만큼(수백 KB) */
private const val PREVIEW_PX = 1_600
private const val NEAR_END = 5
private const val SNACKBAR_BOTTOM_DP = 96
private const val SCRIM_ALPHA = 0.55f
private const val SUBTITLE_ALPHA = 0.8f
