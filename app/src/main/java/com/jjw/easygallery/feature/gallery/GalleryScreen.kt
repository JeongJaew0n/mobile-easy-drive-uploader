package com.jjw.easygallery.feature.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.Category
import com.jjw.easygallery.core.domain.model.CategoryFilter
import com.jjw.easygallery.core.domain.model.DateRange
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.UploadSummary
import com.jjw.easygallery.core.navigation.HeroOrigin
import com.jjw.easygallery.core.ui.motion.LocalMotion
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme
import com.jjw.easygallery.feature.categories.CategoryFilterSheet
import com.jjw.easygallery.feature.categories.CategoryPickerSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryScreen(
    uiState: GalleryUiState,
    onSettingsClick: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onUploadSelected: () -> Unit,
    onCancelUpload: () -> Unit,
    onUploadQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectionChange: (Set<Long>) -> Unit = {},
    onFavoritesOnlyChange: (Boolean) -> Unit = {},
    onNotBackedUpOnlyChange: (Boolean) -> Unit = {},
    onDateRangeChange: (DateRange?) -> Unit = {},
    onCategoryFilterChange: (CategoryFilter?) -> Unit = {},
    onManageCategories: () -> Unit = {},
    onCreateCategory: suspend (String, Int) -> Result<Category> = { _, _ ->
        Result.failure(IllegalStateException("카테고리 생성이 연결되지 않았습니다"))
    },
    onAssignCategories: (add: Set<Long>, remove: Set<Long>) -> Unit = { _, _ -> },
    onTrashClick: () -> Unit = {},
    onDriveClick: () -> Unit = {},
    onDuplicatesClick: () -> Unit = {},
    onOpenItem: (item: MediaItem, filters: ViewerFilters, hero: HeroOrigin?) -> Unit = { _, _, _ -> },
    actions: GalleryActionCallbacks = GalleryActionCallbacks(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val content = uiState as? GalleryUiState.Content
    val selectionMode = content?.isSelectionMode == true
    val motion = LocalMotion.current
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showMove by rememberSaveable { mutableStateOf(false) }
    var showDateRange by rememberSaveable { mutableStateOf(false) }
    var showCategoryFilter by rememberSaveable { mutableStateOf(false) }
    var showCategoryPicker by rememberSaveable { mutableStateOf(false) }

    // 선택 모드에서 뒤로가기는 선택 해제
    BackHandler(enabled = selectionMode, onBack = onClearSelection)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // 일반 ↔ 선택 상단바를 페이드로 교차 (순간 교체 방지)
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = { motion.enterFade() togetherWith motion.exitFade() },
                label = "galleryTopBar",
            ) { selecting ->
                if (selecting && content != null) {
                    SelectionTopBar(
                        selectedCount = content.selectedIds.size,
                        onClear = onClearSelection,
                        onUpload = onUploadSelected,
                    )
                } else {
                    GalleryTopBar(
                        itemCount = content?.itemCount,
                        uploadedCount = content?.uploadedCount ?: 0,
                        favoritesOnly = content?.favoritesOnly == true,
                        notBackedUpOnly = content?.notBackedUpOnly == true,
                        supportsTrashAndFavorites = content?.supportsTrashAndFavorites == true,
                        onSettingsClick = onSettingsClick,
                        onFavoritesOnlyChange = onFavoritesOnlyChange,
                        onNotBackedUpOnlyChange = onNotBackedUpOnlyChange,
                        onTrashClick = onTrashClick,
                        onDriveClick = onDriveClick,
                        onDuplicatesClick = onDuplicatesClick,
                        onPickDateRange = { showDateRange = true },
                        onPickCategory = { showCategoryFilter = true },
                        categoryTitle = content?.let { categoryTitle(it.categoryFilter, it.categories) },
                    )
                }
            }
        },
        bottomBar = {
            // 하단 액션 바는 아래에서 올라오고 내려간다. 사라지는 동안에도 마지막 내용을 유지
            AnimatedVisibility(
                visible = selectionMode,
                enter = motion.enterFromBottom(),
                exit = motion.exitToBottom(),
            ) {
                if (content != null) {
                    SelectionBottomBar(
                        selectedCount = content.selectedIds.size,
                        allFavorite = content.selectedAllFavorite,
                        supportsTrashAndFavorites = content.supportsTrashAndFavorites,
                        enabled = !content.isMutating,
                        onTrash = actions.onTrash,
                        onDelete = actions.onDelete,
                        onToggleFavorite = actions.onToggleFavorite,
                        onRename = { showRename = true },
                        onMove = { showMove = true },
                        onCategories = { showCategoryPicker = true },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (uiState) {
                GalleryUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                GalleryUiState.PermissionRequired -> PermissionRequiredContent(
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                    modifier = Modifier.align(Alignment.Center),
                )

                is GalleryUiState.Error -> Text(
                    text = uiState.throwable.localizedMessage ?: uiState.throwable.toString(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )

                is GalleryUiState.Content -> GalleryContent(
                    uiState = uiState,
                    onToggleSelection = onToggleSelection,
                    onSelectionChange = onSelectionChange,
                    onOpenItem = { item, bounds ->
                        onOpenItem(item, uiState.viewerFilters(), bounds?.toHeroOrigin(item))
                    },
                    onClearDateRange = { onDateRangeChange(null) },
                    onClearCategoryFilter = { onCategoryFilterChange(null) },
                    onCancelUpload = onCancelUpload,
                    onUploadQueueClick = onUploadQueueClick,
                    onRequestPermission = onRequestPermission,
                )
            }
            if (content?.isMutating == true) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    if (content != null) {
        GallerySheets(
            content = content,
            showDateRange = showDateRange,
            showFilter = showCategoryFilter,
            showPicker = showCategoryPicker,
            onDismissDateRange = { showDateRange = false },
            onDismissFilter = { showCategoryFilter = false },
            onDismissPicker = { showCategoryPicker = false },
            onDateRangeChange = onDateRangeChange,
            onCategoryFilterChange = onCategoryFilterChange,
            onManageCategories = onManageCategories,
            onCreateCategory = onCreateCategory,
            onAssignCategories = onAssignCategories,
        )
    }
    if (content != null) {
        GalleryDialogs(
            content = content,
            showRename = showRename,
            showMove = showMove,
            onDismissRename = { showRename = false },
            onDismissMove = { showMove = false },
            actions = actions,
        )
    }
}

/** 기간 선택·카테고리 필터·카테고리 지정 바텀시트. 적용하면 닫힌다 */
@Composable
@Suppress("LongParameterList") // 시트 3개의 표시 상태·콜백 묶음
private fun GallerySheets(
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
private fun GalleryDialogs(
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

@Composable
private fun GalleryContent(
    uiState: GalleryUiState.Content,
    onToggleSelection: (Long) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
    onOpenItem: (MediaItem, Rect?) -> Unit,
    onClearDateRange: () -> Unit,
    onClearCategoryFilter: () -> Unit,
    onCancelUpload: () -> Unit,
    onUploadQueueClick: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val motion = LocalMotion.current
    // 배너는 펴지며 등장해 그리드를 밀어내고, 접히며 사라진다 (그리드 점프 방지). 짧게(150ms) 유지
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = uiState.upload.hasActive,
            enter = motion.enterExpand(),
            exit = motion.exitShrink(),
        ) {
            UploadProgressBanner(summary = uiState.upload, onCancel = onCancelUpload, onClick = onUploadQueueClick)
        }
        AnimatedVisibility(
            visible = !uiState.upload.hasActive && uiState.upload.failed > 0,
            enter = motion.enterExpand(),
            exit = motion.exitShrink(),
        ) {
            UploadFailedBanner(failed = uiState.upload.failed, onClick = onUploadQueueClick)
        }
        AnimatedVisibility(
            visible = uiState.isPartialAccess,
            enter = motion.enterExpand(),
            exit = motion.exitShrink(),
        ) {
            PartialAccessBanner(onManageSelection = onRequestPermission)
        }
        // 사라지는 동안에도 마지막 기간을 보여주기 위해 non-null 값을 기억
        val lastRange = remember { mutableStateOf(uiState.dateRange) }
        if (uiState.dateRange != null) lastRange.value = uiState.dateRange
        AnimatedVisibility(
            visible = uiState.dateRange != null,
            enter = motion.enterExpand(),
            exit = motion.exitShrink(),
        ) {
            lastRange.value?.let { range -> DateRangeBar(range = range, onClear = onClearDateRange) }
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
        if (uiState.sections.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(
                        when {
                            uiState.notBackedUpOnly -> R.string.gallery_not_backed_up_empty
                            uiState.categoryFilter != null -> R.string.gallery_date_empty
                            uiState.dateRange != null -> R.string.gallery_date_empty
                            uiState.favoritesOnly -> R.string.gallery_favorites_empty
                            else -> R.string.gallery_empty
                        },
                    ),
                )
            }
        } else {
            GalleryGrid(
                sections = uiState.sections,
                selectedIds = uiState.selectedIds,
                onToggleSelection = onToggleSelection,
                onSelectionChange = onSelectionChange,
                onOpenItem = onOpenItem,
                animateChanges = uiState.animateItemChanges,
                uploadedIds = uiState.uploadedIds,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GalleryScreenPermissionPreview() {
    EasyGalleryTheme {
        GalleryScreen(
            uiState = GalleryUiState.PermissionRequired,
            onSettingsClick = {},
            onRequestPermission = {},
            onOpenAppSettings = {},
            onToggleSelection = {},
            onClearSelection = {},
            onUploadSelected = {},
            onCancelUpload = {},
            onUploadQueueClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun GalleryScreenUploadingPreview() {
    EasyGalleryTheme {
        GalleryScreen(
            uiState = GalleryUiState.Content(
                sections = emptyList(),
                itemCount = 0,
                isPartialAccess = false,
                upload = UploadSummary(total = 5, active = 3, completed = 2),
            ),
            onSettingsClick = {},
            onRequestPermission = {},
            onOpenAppSettings = {},
            onToggleSelection = {},
            onClearSelection = {},
            onUploadSelected = {},
            onCancelUpload = {},
            onUploadQueueClick = {},
        )
    }
}

/** 썸네일 윈도우 좌표 + 원본 정보 → 상세보기 히어로 원점 */
private fun Rect.toHeroOrigin(item: MediaItem) = HeroOrigin(
    left = left.toInt(),
    top = top.toInt(),
    width = width.toInt(),
    height = height.toInt(),
    uri = item.uri.toString(),
    imageWidth = item.width,
    imageHeight = item.height,
)

/** 상세보기가 갤러리와 같은 범위를 보도록 넘기는 필터 묶음 */
data class ViewerFilters(
    val favoritesOnly: Boolean,
    val range: DateRange?,
    val category: CategoryFilter?,
)

internal fun GalleryUiState.Content.viewerFilters() = ViewerFilters(favoritesOnly, dateRange, categoryFilter)

/** 카테고리 필터가 켜져 있을 때의 상단 제목. 하나면 그 이름, 여럿이면 개수, 미분류면 전용 문구 */
@Composable
internal fun categoryTitle(filter: CategoryFilter?, categories: List<Category>): String? = when (filter) {
    null -> null
    CategoryFilter.Uncategorized -> stringResource(R.string.gallery_title_uncategorized)
    is CategoryFilter.Any -> {
        val selected = categories.filter { it.id in filter.ids }
        selected.singleOrNull()?.name ?: stringResource(R.string.gallery_title_category_count, selected.size)
    }
}
