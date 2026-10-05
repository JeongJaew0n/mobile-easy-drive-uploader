package com.jjw.easygallery.feature.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.jjw.easygallery.R
import com.jjw.easygallery.core.ui.image.mediaStoreThumbnail
import com.jjw.easygallery.feature.gallery.destinationLabel
import com.jjw.easygallery.feature.viewer.thumbnailCacheKey

/**
 * 백업 칸 맨 위 — 이 기기의 사진이 얼마나·어디에 올라갔나(`docs/plans/backed-up-photos/spec.md` §2.1).
 * 카드·띠·"모두 보기" 어디를 눌러도 백업된 사진 화면이다.
 */
@Composable
internal fun BackupSummaryCard(overview: BackupOverview, failed: Int, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = pluralStringResource(
                        R.plurals.backed_up_summary_of,
                        overview.deviceCount,
                        overview.deviceCount,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.backed_up_summary_count, overview.backedUpCount, overview.percent),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            DestinationBars(overview, failed)
            RecentStrip(overview, onOpen)
        }
    }
}

/**
 * 저장소마다 한 줄 막대. 한 사진이 두 곳에 올라갔으면 두 줄 모두에 세므로 합이 100% 를 넘을 수 있다 —
 * 그래서 한 막대에 겹쳐 그리지 않는다.
 */
@Composable
private fun DestinationBars(overview: BackupOverview, failed: Int) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        overview.perDestination.forEach { (destination, count) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = destinationLabel(destination),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.backed_up_destination_count, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { if (overview.deviceCount == 0) 0f else count.toFloat() / overview.deviceCount },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                )
            }
        }
        if (failed > 0) {
            Text(
                text = stringResource(R.string.backed_up_failed_count, failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // 썸네일 ✓ 와 "올린 사진 기기에서 삭제" 는 지금 업로드 대상 한 곳만 센다 — 저장소가 여럿이면 숫자가 다르다
        if (overview.perDestination.size > 1) {
            Text(
                text = stringResource(R.string.backed_up_target_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentStrip(overview: BackupOverview, onOpen: () -> Unit) {
    if (overview.recent.isEmpty()) return
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.backed_up_recent),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpen) { Text(stringResource(R.string.drive_photos_see_all)) }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(overview.recent, key = { it.id }) { item ->
                AsyncImage(
                    // 갤러리 격자와 같은 캐시 키 — 방금 본 썸네일을 다시 읽지 않는다
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(item.uri)
                        .memoryCacheKey(thumbnailCacheKey(item))
                        .mediaStoreThumbnail()
                        .build(),
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(THUMB_DP.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onOpen),
                )
            }
        }
    }
}

private const val THUMB_DP = 72
