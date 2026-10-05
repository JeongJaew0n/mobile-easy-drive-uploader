package com.jjw.easygallery.feature.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jjw.easygallery.core.domain.model.Category
import com.jjw.easygallery.core.domain.model.CategoryFilter
import com.jjw.easygallery.core.domain.model.DateRange
import com.jjw.easygallery.feature.categories.CategoryFilterSheet
import com.jjw.easygallery.feature.categories.CategoryPickerSheet

/** 기간 선택·카테고리 필터·카테고리 지정 바텀시트. 적용하면 닫힌다 */
@Composable
@Suppress("LongParameterList") // 시트 3개의 표시 상태·콜백 묶음
internal fun GallerySheets(
    content: GalleryUiState.Content,
    showDateRange: Boolean,
    showFilter: Boolean,
    showPicker: Boolean,
    onDismissDateRange: () -> Unit,
    onDismissFilter: () -> Unit,
    onDismissPicker: () -> Unit,
    onDateRangeChange: (DateRange?) -> Unit,
    onCategoryFilterChange: (CategoryFilter?) -> Unit,
    onManageCategories: () -> Unit,
    onCreateCategory: suspend (String, Int) -> Result<Category>,
    onAssignCategories: (add: Set<Long>, remove: Set<Long>) -> Unit,
) {
    if (showDateRange) {
        DateRangeSheet(
            current = content.dateRange,
            dayCounts = content.dayCounts,
            onDismiss = onDismissDateRange,
            onConfirm = { range ->
                onDismissDateRange()
                onDateRangeChange(range)
            },
        )
    }
    if (showFilter) {
        CategoryFilterSheet(
            categories = content.categories,
            current = content.categoryFilter,
            onApply = { filter ->
                onDismissFilter()
                onCategoryFilterChange(filter)
            },
            onManage = {
                onDismissFilter()
                onManageCategories()
            },
            onDismiss = onDismissFilter,
        )
    }
    if (showPicker) {
        CategoryPickerSheet(
            mediaIds = content.selectedIds,
            categories = content.categories,
            assignments = content.assignments,
            onCreateCategory = onCreateCategory,
            onApply = { add, remove ->
                onDismissPicker()
                onAssignCategories(add, remove)
            },
            onDismiss = onDismissPicker,
        )
    }
}

@Composable
internal fun GalleryDialogs(
    content: GalleryUiState.Content,
    showRename: Boolean,
    showMove: Boolean,
    onDismissRename: () -> Unit,
    onDismissMove: () -> Unit,
    actions: GalleryActionCallbacks,
) {
    if (showRename) {
        val selected = content.sections.asSequence().flatMap { it.items }.firstOrNull { it.id in content.selectedIds }
        if (selected != null) {
            RenameDialog(
                currentName = selected.displayName,
                onDismiss = onDismissRename,
                onConfirm = { name ->
                    onDismissRename()
                    actions.onRename(name)
                },
            )
        }
    }
    if (showMove) {
        MoveDialog(
            albums = content.albums,
            onDismiss = onDismissMove,
            onConfirm = { path ->
                onDismissMove()
                actions.onMove(path)
            },
        )
    }
}
