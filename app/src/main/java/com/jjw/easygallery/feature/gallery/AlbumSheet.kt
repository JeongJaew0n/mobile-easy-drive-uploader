package com.jjw.easygallery.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.Album
import com.jjw.easygallery.core.ui.image.mediaStoreThumbnail
import com.jjw.easygallery.feature.viewer.thumbnailCacheKey

/**
 * 앨범(폴더)을 골라 그 앨범만 본다(`docs/plans/album-view/spec.md`). 카톡의 사진 선택 화면처럼
 * 대표 사진·이름·개수를 격자로 보이고, **최근 사진이 있는 앨범부터** 놓는다 — 이름순이면 방금 찍은 앨범이 묻힌다.
 *
 * 삼성 갤러리에서 만든 앨범도 실제 폴더라 여기 그대로 나온다(`docs/SAMSUNG_GALLERY_INTEROP.md` §4.2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumPickerSheet(
    albums: List<Album>,
    current: String?,
    onPick: (Album) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ordered = remember(albums) { albums.sortedByDescending { it.cover?.dateTakenMillis ?: Long.MIN_VALUE } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.album_picker_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (ordered.isEmpty()) {
                Text(
                    text = stringResource(R.string.album_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = ALBUM_CELL_MIN_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(ordered, key = { it.relativePath }) { album ->
                    AlbumCell(album = album, selected = album.relativePath == current, onClick = { onPick(album) })
                }
            }
        }
    }
}

@Composable
private fun AlbumCell(album: Album, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val cover = album.cover
            if (cover != null) {
                AsyncImage(
                    // 갤러리 격자와 같은 캐시 키 — 방금 본 썸네일을 다시 읽지 않는다
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(cover.uri)
                        .memoryCacheKey(thumbnailCacheKey(cover))
                        .mediaStoreThumbnail()
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(painterResource(R.drawable.ic_folder), contentDescription = null)
            }
        }
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = pluralStringResource(R.plurals.gallery_media_count, album.itemCount, album.itemCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 앨범 필터가 켜져 있을 때 기간·카테고리 막대와 나란히 붙는 막대. ✕ 로 푼다 */
@Composable
internal fun AlbumFilterBar(album: Album, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_folder),
                contentDescription = null,
                modifier = Modifier.size(ALBUM_BAR_ICON_DP.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                // 경로를 함께 — 삼성은 같은 이름의 앨범을 여러 곳에 만들 수 있다(DCIM/여행, Pictures/여행)
                text = stringResource(R.string.album_filter_label, album.name, album.relativePath.trimEnd('/')),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.album_filter_clear))
            }
        }
    }
}

private const val ALBUM_CELL_MIN_DP = 96
private const val ALBUM_BAR_ICON_DP = 16
