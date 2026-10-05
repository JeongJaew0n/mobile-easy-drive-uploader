package com.jjw.easygallery.feature.gallery

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.ui.image.mediaStoreThumbnail
import com.jjw.easygallery.core.ui.motion.LocalMotion
import com.jjw.easygallery.core.ui.theme.categoryColor
import com.jjw.easygallery.feature.viewer.thumbnailCacheKey
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
internal fun MediaThumbnail(
    item: MediaItem,
    selected: Boolean,
    backupBadge: BackupBadge,
    categoryColors: List<Int>,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onOpen: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    // 배치될 때마다 갱신되는 윈도우 좌표. 상태(State)가 아니라 재구성을 일으키지 않는다
    val bounds = remember { arrayOfNulls<Rect>(1) }
    // 선택 시 살짝 축소 — padding 대신 graphicsLayer 라 레이아웃 재측정이 없다
    val imageScale by animateFloatAsState(
        targetValue = if (selected) SELECTED_SCALE else 1f,
        animationSpec = motion.settle(),
        label = "thumbScale",
    )
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onPlaced { bounds[0] = it.boundsInWindow() }
            // 길게 누르기·드래그 선택은 그리드(dragSelect)가 처리.
            // 선택 모드에서는 탭으로 토글, 아니면 상세보기로 진입
            .clickable { if (selectionMode) onToggleSelection() else onOpen(bounds[0]) },
    ) {
        AsyncImage(
            // 상세보기가 같은 키로 플레이스홀더를 꺼내 쓴다(썸네일 → 원본 2단계 로드)
            model = ImageRequest.Builder(LocalPlatformContext.current)
                .data(item.uri)
                .memoryCacheKey(thumbnailCacheKey(item))
                .mediaStoreThumbnail()
                .build(),
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = imageScale
                    scaleY = imageScale
                },
        )
        AnimatedVisibility(
            visible = selectionMode,
            enter = motion.enterScale(),
            exit = motion.exitScale(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp),
        ) {
            // 선택 ↔ 미선택 표시는 제자리에서 커지며 교차
            AnimatedContent(
                targetState = selected,
                transitionSpec = { motion.enterScale() togetherWith motion.exitScale() },
                label = "selectionIndicator",
            ) { isSelected -> SelectionIndicator(selected = isSelected) }
        }
        // 오른쪽 위: 카테고리 색 점(최대 3) + 즐겨찾기 별. 점은 배경만 있는 Box — 애니메이션 없음
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            categoryColors.forEach { colorIndex ->
                Box(
                    Modifier
                        .size(CATEGORY_DOT_DP.dp)
                        .background(Color.Black.copy(alpha = BADGE_ALPHA), CircleShape)
                        .padding(1.dp)
                        .background(categoryColor(colorIndex), CircleShape),
                )
            }
            if (item.isFavorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (item.isVideo) {
            VideoBadge(
                durationMillis = item.durationMillis ?: 0L,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            )
        }
        if (backupBadge != BackupBadge.NONE) {
            Icon(
                painter = painterResource(backupBadge.iconRes),
                contentDescription = stringResource(backupBadge.labelRes),
                tint = if (backupBadge == BackupBadge.FAILED) FAILED_TINT else Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = BADGE_ALPHA), CircleShape)
                    .padding(2.dp)
                    .size(12.dp),
            )
        }
    }
}

@Composable
private fun SelectionIndicator(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier
                .size(22.dp)
                .background(Color.White, CircleShape),
        )
    } else {
        Box(
            modifier = modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = BADGE_ALPHA * 0.5f))
                .border(2.dp, Color.White, CircleShape),
        )
    }
}

@Composable
private fun VideoBadge(
    durationMillis: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = BADGE_ALPHA), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = formatDuration(durationMillis),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

internal fun formatDuration(millis: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.ROOT, minutes, seconds)
    }
}

private const val BADGE_ALPHA = 0.6f
private const val SELECTED_SCALE = 0.88f
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val CATEGORY_DOT_DP = 10

/**
 * 썸네일 백업 배지(`docs/plans/bottom-navigation/spec.md`). 올라감 ✓ 은 설정으로 끌 수 있고, 대기·실패는 늘 보인다 —
 * 백업 앱에서 할 일은 "아직 안 올라간 것" 이다.
 */
enum class BackupBadge(
    @param:DrawableRes @get:DrawableRes val iconRes: Int,
    @param:StringRes @get:StringRes val labelRes: Int,
) {
    NONE(0, 0),
    DONE(R.drawable.ic_cloud_done, R.string.gallery_uploaded_badge),
    PENDING(R.drawable.ic_cloud_upload, R.string.gallery_pending_badge),
    FAILED(R.drawable.ic_cloud_off, R.string.gallery_failed_badge),
}

/** 올라간 것(원장)이 실패보다 앞선다 — 예전 배치의 실패 줄이 남아 있어도 원장에 있으면 올라간 것이다 */
internal fun GalleryUiState.Content.backupBadgeOf(id: Long): BackupBadge = when {
    id in uploadedIds -> if (showBackedUpBadge) BackupBadge.DONE else BackupBadge.NONE
    id in pendingIds -> BackupBadge.PENDING
    id in failedIds -> BackupBadge.FAILED
    else -> BackupBadge.NONE
}

/** 실패 배지는 흰색 대신 눈에 띄는 주황 — 검은 반투명 동그라미 위에서 읽힌다 */
private val FAILED_TINT = Color(0xFFFFB27A)
