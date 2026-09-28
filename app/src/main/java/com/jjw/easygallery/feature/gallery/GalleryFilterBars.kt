package com.jjw.easygallery.feature.gallery

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.jjw.easygallery.R
import com.jjw.easygallery.core.ui.motion.LocalMotion

/**
 * 걸어 둔 필터를 알려 주는 막대들(기간·카테고리·앨범). 그리드 위에 펴지며 붙고 ✕ 로 푼다.
 * 사라지는 동안에도 마지막 값을 보여 주려고 각 막대가 non-null 값을 기억한다.
 */
@Composable
internal fun GalleryFilterBars(
    uiState: GalleryUiState.Content,
    onClearDateRange: () -> Unit,
    onSelectAllVisible: () -> Unit,
    onClearCategoryFilter: () -> Unit,
    onClearAlbumFilter: () -> Unit,
) {
    val motion = LocalMotion.current
    val lastRange = remember { mutableStateOf(uiState.dateRange) }
    if (uiState.dateRange != null) lastRange.value = uiState.dateRange
    AnimatedVisibility(
        visible = uiState.dateRange != null,
        enter = motion.enterExpand(),
        exit = motion.exitShrink(),
    ) {
        lastRange.value?.let { range ->
            DateRangeBar(
                range = range,
                onClear = onClearDateRange,
                onSelectAll = onSelectAllVisible,
                allSelected = uiState.itemCount > 0 && uiState.selectedIds.size >= uiState.itemCount,
            )
        }
    }
    val lastCategory = remember { mutableStateOf(uiState.categoryFilter) }
    if (uiState.categoryFilter != null) lastCategory.value = uiState.categoryFilter
    AnimatedVisibility(
        visible = uiState.categoryFilter != null,
        enter = motion.enterExpand(),
        exit = motion.exitShrink(),
    ) {
        lastCategory.value?.let { filter ->
            CategoryFilterBar(filter = filter, categories = uiState.categories, onClear = onClearCategoryFilter)
        }
    }
    val lastAlbum = remember { mutableStateOf(uiState.albumFilter) }
    if (uiState.albumFilter != null) lastAlbum.value = uiState.albumFilter
    AnimatedVisibility(
        visible = uiState.albumFilter != null,
        enter = motion.enterExpand(),
        exit = motion.exitShrink(),
    ) {
        lastAlbum.value?.let { album -> AlbumFilterBar(album = album, onClear = onClearAlbumFilter) }
    }
}

/** 목록이 비었을 때의 문구. 가장 좁은 조건부터 — 그게 비게 만든 이유일 가능성이 가장 크다 */
@StringRes
internal fun GalleryUiState.Content.emptyMessageRes(): Int = when {
    notBackedUpOnly -> R.string.gallery_not_backed_up_empty
    albumFilter != null -> R.string.gallery_album_empty
    categoryFilter != null -> R.string.gallery_category_empty
    dateRange != null -> R.string.gallery_date_empty
    favoritesOnly -> R.string.gallery_favorites_empty
    tab == GalleryTab.CHOSEN -> R.string.gallery_chosen_empty
    else -> R.string.gallery_empty
}
