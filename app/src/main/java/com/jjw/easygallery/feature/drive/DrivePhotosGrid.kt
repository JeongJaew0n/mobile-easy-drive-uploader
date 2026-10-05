package com.jjw.easygallery.feature.drive

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.feature.gallery.DateScrollHandle
import com.jjw.easygallery.feature.gallery.formatDuration
import com.jjw.easygallery.feature.gallery.rememberYearMonthFormatter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Drive 사진 격자 — 달 머리글 + 썸네일 칸(`docs/plans/drive-photos/spec.md` §3.2). ☁ 는 이 기기에 없는 것.
 * 오른쪽 날짜 손잡이는 갤러리의 것을 그대로 쓴다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DrivePhotosGrid(
    state: DrivePhotosUiState.Content,
    imageLoader: ImageLoader,
    onClick: (DriveEntry) -> Unit,
    onLongClick: (DriveEntry) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells = state.cells
    val gridState = rememberLazyGridState()
    LaunchedEffect(cells.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .filter { last -> last != null && last >= cells.size - LOAD_MORE_THRESHOLD }
            .collect { onLoadMore() }
    }
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = CELL_MIN_DP.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                items = cells,
                key = { it.key },
                span = { cell -> if (cell is DrivePhotoCell.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { it::class },
            ) { cell ->
                when (cell) {
                    is DrivePhotoCell.Header -> MonthHeader(cell)
                    is DrivePhotoCell.Photo -> PhotoCell(
                        entry = cell.entry,
                        imageLoader = imageLoader,
                        onDevice = cell.entry.id in state.onDeviceIds,
                        selected = cell.entry.id in state.selectedIds,
                        selecting = state.isSelecting,
                        onClick = { onClick(cell.entry) },
                        onLongClick = { onLongClick(cell.entry) },
                    )
                }
            }
            if (state.isLoadingMore) {
                item(key = "loading-more", span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                }
            }
        }
        val headerIndexes = remember(cells) { cells.indices.filter { cells[it] is DrivePhotoCell.Header } }
        val yearMonth = rememberYearMonthFormatter()
        val undated = stringResource(R.string.drive_photos_undated)
        DateScrollHandle(
            state = gridState,
            totalItems = cells.size,
            headerIndexes = headerIndexes,
            labelOf = { index ->
                (cells.getOrNull(index) as? DrivePhotoCell.Header)?.month?.let { yearMonth.format(it) } ?: undated
            },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun MonthHeader(header: DrivePhotoCell.Header) {
    val yearMonth = rememberYearMonthFormatter()
    Text(
        text = header.month?.let { yearMonth.format(it) } ?: stringResource(R.string.drive_photos_undated),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    entry: DriveEntry,
    imageLoader: ImageLoader,
    onDevice: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(1.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        DriveThumbnail(entry, imageLoader, Modifier.fillMaxSize())
        if (!onDevice) {
            Icon(
                painterResource(R.drawable.ic_cloud),
                contentDescription = stringResource(R.string.drive_photos_not_on_device),
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = BADGE_ALPHA), RoundedCornerShape(4.dp))
                    .padding(2.dp)
                    .size(14.dp),
            )
        }
        if (entry.isVideo) VideoBadge(entry, Modifier.align(Alignment.BottomStart))
        if (selecting) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = BADGE_ALPHA),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(22.dp),
            )
        }
    }
}

/** 영상 표시 — 길이를 알면 길이, 아직 모르면(막 올린 것) 재생 표시만 */
@Composable
private fun VideoBadge(entry: DriveEntry, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(4.dp)
            .background(Color.Black.copy(alpha = BADGE_ALPHA), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        entry.durationMillis?.let {
            Text(text = formatDuration(it), style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
}

private const val CELL_MIN_DP = 96
private const val BADGE_ALPHA = 0.5f
private const val LOAD_MORE_THRESHOLD = 12
