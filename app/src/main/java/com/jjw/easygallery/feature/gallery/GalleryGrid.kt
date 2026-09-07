package com.jjw.easygallery.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import coil3.compose.AsyncImage
import com.jjw.easygallery.core.domain.model.MediaItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
internal fun GalleryGrid(
    sections: List<GallerySection>,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
    }
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = MIN_CELL_SIZE_DP.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CELL_SPACING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(CELL_SPACING_DP.dp),
    ) {
        sections.forEach { section ->
            item(
                key = "header-${section.date}",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "header",
            ) {
                DateHeader(date = section.date, formatter = dateFormatter)
            }
            items(
                items = section.items,
                key = { it.uri.toString() },
                contentType = { "media" },
            ) { item ->
                MediaThumbnail(item = item)
            }
        }
    }
}

@Composable
private fun DateHeader(
    date: LocalDate,
    formatter: DateTimeFormatter,
    modifier: Modifier = Modifier,
) {
    Text(
        text = remember(date, formatter) { formatter.format(date) },
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun MediaThumbnail(
    item: MediaItem,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo) {
            VideoBadge(
                durationMillis = item.durationMillis ?: 0L,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            )
        }
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

private const val MIN_CELL_SIZE_DP = 100
private const val CELL_SPACING_DP = 2
private const val BADGE_ALPHA = 0.6f
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
