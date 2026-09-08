package com.jjw.easygallery.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import coil3.compose.AsyncImage
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.MediaItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
internal fun GalleryGrid(
    sections: List<GallerySection>,
    selectedIds: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
    onOpenItem: (Long) -> Unit = {},
) {
    val selectionMode = selectedIds.isNotEmpty()
    val gridState = rememberLazyGridState()
    // 그리드 인덱스 → 항목 ID (헤더는 null). 드래그 범위 선택에서 화면 밖 항목까지 포함하기 위해 필요
    val entryIds = remember(sections) {
        buildList<Long?> {
            sections.forEach { section ->
                add(null)
                section.items.forEach { add(it.id) }
            }
        }
    }
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
    }
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = MIN_CELL_SIZE_DP.dp),
        modifier = modifier.dragSelect(
            state = gridState,
            entryIds = entryIds,
            selectedIds = selectedIds,
            onSelectionChange = onSelectionChange,
        ),
        horizontalArrangement = Arrangement.spacedBy(CELL_SPACING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(CELL_SPACING_DP.dp),
    ) {
        sections.forEach { section ->
            item(
                key = "header-${section.date}",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "header",
            ) {
                val sectionIds = section.items.map { it.id }
                DateHeader(
                    date = section.date,
                    formatter = dateFormatter,
                    allSelected = sectionIds.isNotEmpty() && sectionIds.all { it in selectedIds },
                    anySelected = sectionIds.any { it in selectedIds },
                    onToggleSection = { onSelectionChange(selectedIds.toggleSection(sectionIds)) },
                )
            }
            items(
                items = section.items,
                key = { it.id },
                contentType = { "media" },
            ) { item ->
                MediaThumbnail(
                    item = item,
                    selected = item.id in selectedIds,
                    selectionMode = selectionMode,
                    onToggleSelection = { onToggleSelection(item.id) },
                    onOpen = { onOpenItem(item.id) },
                )
            }
        }
    }
}

@Composable
private fun DateHeader(
    date: LocalDate,
    formatter: DateTimeFormatter,
    allSelected: Boolean,
    anySelected: Boolean,
    onToggleSection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = remember(date, formatter) { formatter.format(date) },
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        )
        Spacer(Modifier.width(8.dp))
        SectionSelectButton(
            allSelected = allSelected,
            anySelected = anySelected,
            onClick = onToggleSection,
        )
    }
}

/** 날짜별 전체 선택 토글. 일부만 선택된 상태는 테두리를 굵게 해서 구분한다. */
@Composable
private fun SectionSelectButton(
    allSelected: Boolean,
    anySelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectAllLabel = stringResource(R.string.gallery_section_select_all)
    IconButton(onClick = onClick, modifier = modifier) {
        if (allSelected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.gallery_section_deselect_all),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(SECTION_CIRCLE_SIZE_DP.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (anySelected) SELECTED_BORDER_DP.dp else UNSELECTED_BORDER_DP.dp,
                        color = if (anySelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape,
                    )
                    .semantics { contentDescription = selectAllLabel },
            )
        }
    }
}

@Composable
private fun MediaThumbnail(
    item: MediaItem,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // 길게 누르기·드래그 선택은 그리드(dragSelect)가 처리.
            // 선택 모드에서는 탭으로 토글, 아니면 상세보기로 진입
            .clickable { if (selectionMode) onToggleSelection() else onOpen() },
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(if (selected) Modifier.padding(SELECTED_INSET_DP.dp) else Modifier),
        )
        if (selectionMode) {
            SelectionIndicator(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            )
        }
        if (item.isFavorite) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp),
            )
        }
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

private const val MIN_CELL_SIZE_DP = 100
private const val CELL_SPACING_DP = 2
private const val BADGE_ALPHA = 0.6f
private const val SECTION_CIRCLE_SIZE_DP = 22
private const val SELECTED_BORDER_DP = 3
private const val UNSELECTED_BORDER_DP = 2
private const val SELECTED_INSET_DP = 10
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
