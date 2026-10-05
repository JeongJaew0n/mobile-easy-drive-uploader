package com.jjw.easygallery.feature.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.jjw.easygallery.R
import com.jjw.easygallery.feature.drive.DriveThumbnail

/**
 * 백업 칸의 "Google Drive 사진"(`docs/plans/drive-photos/spec.md` §3.1). 최근 사진 썸네일 띠 — 하나를 누르면 그 사진이,
 * 카드나 "모두 보기" 를 누르면 Drive 사진 화면이 열린다.
 */
@Composable
internal fun DrivePhotosCard(
    preview: DrivePhotosPreview,
    imageLoader: ImageLoader?,
    onOpen: (openFileId: String?) -> Unit,
) {
    Surface(
        onClick = { onOpen(null) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.drive_photos_card_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.drive_photos_card_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onOpen(null) }) { Text(stringResource(R.string.drive_photos_see_all)) }
            }
            when {
                preview.isLoading -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(THUMB_DP.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                preview.entries.isNotEmpty() && imageLoader != null -> LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(preview.entries, key = { it.id }) { entry ->
                        DriveThumbnail(
                            entry = entry,
                            imageLoader = imageLoader,
                            showNameWhenMissing = false,
                            modifier = Modifier
                                .size(THUMB_DP.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpen(entry.id) },
                        )
                    }
                }
                // 못 읽었거나 아직 없다 — 띠 없이 카드만. 들어가면 화면이 이유를 보인다
                else -> Unit
            }
        }
    }
}

private const val THUMB_DP = 72
