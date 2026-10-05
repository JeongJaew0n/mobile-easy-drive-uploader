package com.jjw.easygallery.feature.albums

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.Album
import com.jjw.easygallery.core.ui.image.mediaStoreThumbnail
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme
import com.jjw.easygallery.feature.gallery.AppFolders
import com.jjw.easygallery.feature.viewer.thumbnailCacheKey

@Composable
fun AlbumsRoute(
    onOpenAlbum: (Album) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenTrash: () -> Unit,
    navigationBar: @Composable () -> Unit,
    viewModel: AlbumsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AlbumsScreen(
        uiState = uiState,
        onOpenAlbum = onOpenAlbum,
        onOpenFavorites = onOpenFavorites,
        onOpenTrash = onOpenTrash,
        navigationBar = navigationBar,
    )
}

/**
 * 하단 "앨범" 칸. 맨 위에 즐겨찾기·휴지통 바로가기(Google 포토 "컬렉션" 의 자리), 아래로 앨범 격자 — 대표 사진·이름·개수.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumsScreen(
    uiState: AlbumsUiState,
    onOpenAlbum: (Album) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenTrash: () -> Unit,
    navigationBar: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_albums)) }) },
        bottomBar = navigationBar,
    ) { innerPadding ->
        when (uiState) {
            AlbumsUiState.Loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is AlbumsUiState.Content -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = ALBUM_CELL_MIN_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
            ) {
                if (uiState.supportsTrashAndFavorites) {
                    item(key = "shortcuts", span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Shortcut(
                                icon = Icons.Filled.Star,
                                label = stringResource(R.string.gallery_title_favorites),
                                count = uiState.favoriteCount,
                                onClick = onOpenFavorites,
                                modifier = Modifier.weight(1f),
                            )
                            Shortcut(
                                icon = Icons.Filled.Delete,
                                label = stringResource(R.string.trash_title),
                                count = null,
                                onClick = onOpenTrash,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (uiState.albums.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(R.string.album_picker_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(uiState.albums, key = { it.relativePath }) { album ->
                    AlbumCell(album = album, onClick = { onOpenAlbum(album) })
                }
                item(key = "bottom-space", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.padding(4.dp)) }
            }
        }
    }
}

@Composable
private fun Shortcut(
    icon: ImageVector,
    label: String,
    count: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AlbumCell(album: Album, onClick: () -> Unit) {
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
            text = albumDisplayName(album),
            style = MaterialTheme.typography.bodyMedium,
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

/** 카카오톡·다운로드 같은 잘 알려진 폴더는 지금 언어의 이름으로, 나머지는 폴더 이름 그대로 */
@Composable
internal fun albumDisplayName(album: Album): String =
    AppFolders.displayNameRes(album.name)?.let { stringResource(it) } ?: album.name

private const val ALBUM_CELL_MIN_DP = 104

@Preview
@Composable
private fun AlbumsScreenPreview() {
    EasyGalleryTheme {
        AlbumsScreen(
            uiState = AlbumsUiState.Content(
                albums = listOf(Album("Camera", "DCIM/Camera/", 4022), Album("행복이", "DCIM/행복이/", 6)),
                favoriteCount = 83,
            ),
            onOpenAlbum = {},
            onOpenFavorites = {},
            onOpenTrash = {},
        )
    }
}
