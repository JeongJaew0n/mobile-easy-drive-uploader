package com.jjw.easygallery.feature.drive

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.jjw.easygallery.core.domain.model.DriveEntry

/**
 * Drive 를 사진으로 본다(`docs/DRIVE_PHOTO_GRID.md`).
 *
 * **원본을 받지 않는다.** Drive 가 서버에서 만든 썸네일(`thumbnailLink`, 한 장 수십 KB)을 보이는 칸만큼만 받는다.
 * 원본은 누른 것 하나만 — 지금의 앱 안 미리보기가 받는다.
 *
 * 폴더는 위에 한 줄씩(전체 폭) 지금의 목록 행으로 두고, 사진·영상만 정사각 칸으로 편다. 칸 폭은 화면에 맞춰 늘어
 * 폴더블·가로 화면에서도 칸이 너무 커지지 않는다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DriveGrid(
    entries: List<DriveEntry>,
    state: LazyGridState,
    imageLoader: ImageLoader,
    selectedIds: Set<String>,
    selecting: Boolean,
    onClick: (DriveEntry) -> Unit,
    onLongClick: (DriveEntry) -> Unit,
    folderRow: @Composable (DriveEntry) -> Unit,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = CELL_MIN_DP.dp),
        state = state,
        modifier = modifier.fillMaxSize(),
    ) {
        items(
            items = entries,
            key = { it.id },
            span = { entry -> if (entry.isFolder) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
        ) { entry ->
            if (entry.isFolder) {
                folderRow(entry)
            } else {
                DriveGridCell(
                    entry = entry,
                    imageLoader = imageLoader,
                    selected = entry.id in selectedIds,
                    selecting = selecting,
                    onClick = { onClick(entry) },
                    onLongClick = { onLongClick(entry) },
                )
            }
        }
        item(key = "grid-footer", span = { GridItemSpan(maxLineSpan) }) { footer() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DriveGridCell(
    entry: DriveEntry,
    imageLoader: ImageLoader,
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
        contentAlignment = Alignment.Center,
    ) {
        val link = entry.thumbnailLink
        if (link != null) {
            val context = LocalContext.current
            // 링크는 몇 시간 뒤 바뀐다. 캐시를 링크로 가르면 다시 열 때마다 새로 받는다 — ID 와 썸네일 버전으로 가른다
            val key = "drive-thumb:${entry.id}:${entry.thumbnailVersion}"
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbnailUrl(link, THUMB_PX))
                    .memoryCacheKey(key)
                    .diskCacheKey(key)
                    .build(),
                contentDescription = entry.name,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 막 올린 파일은 썸네일이 아직 없다 — 목록과 같은 아이콘
            EntryIcon(entry)
        }
        if (entry.isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(20.dp)
                    .background(Color.Black.copy(alpha = BADGE_ALPHA), CircleShape),
            )
        }
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

/**
 * 썸네일 크기를 칸에 맞춘다. Drive 의 링크는 `…=s220` 처럼 끝에 긴 변의 픽셀 수를 단다 — 220 은 3열 칸에 흐릿하다.
 * 그 꼬리가 없으면 링크를 그대로 쓴다(크기 지정 방식이 다른 호스트일 수 있다).
 */
internal fun thumbnailUrl(link: String, sizePx: Int): String =
    SIZE_SUFFIX.find(link)?.let { link.replaceRange(it.range, "=s$sizePx") } ?: link

private val SIZE_SUFFIX = Regex("=s\\d+$")
private const val CELL_MIN_DP = 110
private const val THUMB_PX = 400
private const val BADGE_ALPHA = 0.5f
