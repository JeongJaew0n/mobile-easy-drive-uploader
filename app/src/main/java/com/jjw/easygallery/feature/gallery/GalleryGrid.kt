package com.jjw.easygallery.feature.gallery

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.ui.motion.LocalMotion
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun GalleryGrid(
    sections: List<GallerySection>,
    selectedIds: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
    /** 탭한 항목과 그 썸네일의 윈도우 좌표(히어로 연출 시작점). 배치되기 전이면 null */
    onOpenItem: (MediaItem, Rect?) -> Unit = { _, _ -> },
    /** false 면 항목 이동·등장 애니메이션 생략(필터 전환처럼 목록이 통째로 바뀔 때) */
    animateChanges: Boolean = true,
    /** 썸네일 왼쪽 아래 백업 배지(`docs/plans/bottom-navigation/spec.md`). 기본은 없음 */
    backupBadgeOf: (Long) -> BackupBadge = { BackupBadge.NONE },
    /** 항목 ID → 카테고리 색 인덱스(최대 3). 빈 목록이면 배지 없음 */
    categoryColorsOf: (Long) -> List<Int> = { emptyList() },
    /** 칸 크기 단계([CELL_SIZE_STEPS_DP]). null 이면 고정 크기에 핀치도 없다(숨긴 사진·휴지통) */
    cellSizeStep: Int? = null,
    onCellSizeStepChange: (Int) -> Unit = {},
    /** 오른쪽 날짜 손잡이 — 갤러리에서만 */
    showDateScroller: Boolean = false,
) {
    val selectionMode = selectedIds.isNotEmpty()
    val gridState = rememberLazyGridState()
    val motion = LocalMotion.current
    val placementSpec = if (animateChanges) motion.settle<androidx.compose.ui.unit.IntOffset>() else null
    val fadeSpec = if (animateChanges) motion.quick<Float>() else null
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
    val cellMinDp = cellSizeStep?.let { CELL_SIZE_STEPS_DP.getOrNull(it) } ?: MIN_CELL_SIZE_DP
    // 핀치는 두 손가락일 때만 가로챈다 — dragSelect(한 손가락 길게 눌러 끌기)보다 바깥에 둬야 먼저 본다
    val pinch = cellSizePinch(cellSizeStep, onCellSizeStepChange)

    Box(modifier) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = cellMinDp.dp),
            modifier = Modifier
                .fillMaxSize()
                .then(pinch)
                .dragSelect(
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
                    key = "header-${section.header}",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "header",
                ) {
                    val sectionIds = section.items.map { it.id }
                    SectionHeaderRow(
                        header = section.header,
                        count = section.items.size,
                        formatter = dateFormatter,
                        allSelected = sectionIds.isNotEmpty() && sectionIds.all { it in selectedIds },
                        anySelected = sectionIds.any { it in selectedIds },
                        onToggleSection = { onSelectionChange(selectedIds.toggleSection(sectionIds)) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = fadeSpec,
                            placementSpec = placementSpec,
                            fadeOutSpec = fadeSpec,
                        ),
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
                        backupBadge = backupBadgeOf(item.id),
                        categoryColors = categoryColorsOf(item.id),
                        selectionMode = selectionMode,
                        onToggleSelection = { onToggleSelection(item.id) },
                        onOpen = { bounds -> onOpenItem(item, bounds) },
                        // 삭제·이동 후 남은 항목이 미끄러져 빈자리를 채운다
                        modifier = Modifier.animateItem(
                            fadeInSpec = fadeSpec,
                            placementSpec = placementSpec,
                            fadeOutSpec = fadeSpec,
                        ),
                    )
                }
            }
        }
        if (showDateScroller) {
            GalleryDateScroller(sections, entryIds, gridState, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** 칸 크기 단계가 있을 때만 핀치를 듣는다(숨긴 사진·휴지통 격자는 고정 크기) */
private fun cellSizePinch(step: Int?, onChange: (Int) -> Unit): Modifier =
    if (step == null) {
        Modifier
    } else {
        Modifier.pinchToZoom { zoom ->
            val next = nextCellStep(step, zoom)
            if (next != step) onChange(next)
        }
    }

/** 오른쪽 날짜 손잡이 — 머리글 자리와 그 이름(날짜 묶음은 연·월, 앱 묶음은 앱 이름)을 넘긴다 */
@Composable
private fun GalleryDateScroller(
    sections: List<GallerySection>,
    entryIds: List<Long?>,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val headerIndexes = remember(entryIds) { entryIds.indices.filter { entryIds[it] == null } }
    val headers = remember(sections, headerIndexes) { headerIndexes.zip(sections.map { it.header }).toMap() }
    val yearMonth = rememberYearMonthFormatter()
    val resources = LocalResources.current
    DateScrollHandle(
        state = gridState,
        totalItems = entryIds.size,
        headerIndexes = headerIndexes,
        labelOf = { index ->
            when (val header = headers[index]) {
                is SectionHeader.ByDate -> yearMonth.format(header.date)
                is SectionHeader.ByUploadDate -> yearMonth.format(header.date)
                is SectionHeader.ByApp ->
                    AppFolders.displayNameRes(header.folder)?.let { resources.getString(it) } ?: header.folder
                is SectionHeader.ByUploadStatus -> resources.getString(header.labelRes())
                null -> ""
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun SectionHeaderRow(
    header: SectionHeader,
    count: Int,
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
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (header) {
                    is SectionHeader.ByDate -> remember(header, formatter) { formatter.format(header.date) }
                    is SectionHeader.ByApp -> appName(header.folder)
                    is SectionHeader.ByUploadDate -> stringResource(
                        R.string.backed_up_section_uploaded,
                        remember(header, formatter) { formatter.format(header.date) },
                    )
                    is SectionHeader.ByUploadStatus -> stringResource(header.labelRes())
                },
                style = MaterialTheme.typography.titleSmall,
            )
            // 앱별·까닭별 묶음은 개수가 바로 보여야 한다 — 무엇이 목록을 채우고 있는지가 그 화면의 핵심이다
            if (header is SectionHeader.ByApp || header is SectionHeader.ByUploadStatus) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.gallery_app_section_count, count),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        SectionSelectButton(
            kind = when (header) {
                is SectionHeader.ByDate, is SectionHeader.ByUploadDate -> SectionKind.DATE
                is SectionHeader.ByApp -> SectionKind.APP
                is SectionHeader.ByUploadStatus -> SectionKind.GROUP
            },
            allSelected = allSelected,
            anySelected = anySelected,
            onClick = onToggleSection,
        )
    }
}

/** 표에 있는 폴더만 한국어로, 나머지는 폴더 이름 그대로 */
@Composable
private fun appName(folder: String): String =
    AppFolders.displayNameRes(folder)?.let { stringResource(it) } ?: folder

/** 묶음 전체 선택 토글. 일부만 선택된 상태는 테두리를 굵게 해서 구분한다. */
@Composable
private fun SectionSelectButton(
    kind: SectionKind,
    allSelected: Boolean,
    anySelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 앱 묶음에 "이 날짜 전체 선택" 이라고 읽어주면 안 된다
    val selectAllLabel = stringResource(
        when (kind) {
            SectionKind.DATE -> R.string.gallery_section_select_all
            SectionKind.APP -> R.string.gallery_app_section_select_all
            SectionKind.GROUP -> R.string.gallery_group_select_all
        },
    )
    IconButton(onClick = onClick, modifier = modifier) {
        if (allSelected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = stringResource(
                    when (kind) {
                        SectionKind.DATE -> R.string.gallery_section_deselect_all
                        SectionKind.APP -> R.string.gallery_app_section_deselect_all
                        SectionKind.GROUP -> R.string.gallery_group_deselect_all
                    },
                ),
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

/** 묶음 전체 선택 단추가 읽어 줄 말 — 날짜 묶음·앱 묶음·그 밖의 묶음(백업 까닭) */
private enum class SectionKind { DATE, APP, GROUP }

/** 대기·실패 묶음의 이름 */
internal fun SectionHeader.ByUploadStatus.labelRes(): Int =
    if (pending) R.string.backed_up_section_pending else failureRes

private const val MIN_CELL_SIZE_DP = 100
private const val CELL_SPACING_DP = 2
private const val SECTION_CIRCLE_SIZE_DP = 22
private const val SELECTED_BORDER_DP = 3
private const val UNSELECTED_BORDER_DP = 2
