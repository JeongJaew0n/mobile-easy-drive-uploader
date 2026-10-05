package com.jjw.easygallery.feature.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryScreen(
    uiState: GalleryUiState,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onUploadSelected: () -> Unit,
    uploadTargets: List<UploadTargetOption> = emptyList(),
    onUploadSelectedTo: (UploadTargetOption) -> Unit = {},
    onCancelUpload: () -> Unit,
    onUploadQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectionChange: (Set<Long>) -> Unit = {},
    onTabChange: (GalleryTab) -> Unit = {},
    onCellSizeStepChange: (Int) -> Unit = {},
    onFavoritesOnlyChange: (Boolean) -> Unit = {},
    onNotBackedUpOnlyChange: (Boolean) -> Unit = {},
    onDateRangeChange: (DateRange?) -> Unit = {},
    onSelectAllVisible: () -> Unit = {},
    onCategoryFilterChange: (CategoryFilter?) -> Unit = {},
    onManageCategories: () -> Unit = {},
    onCreateCategory: suspend (String, Int) -> Result<Category> = { _, _ ->
        Result.failure(IllegalStateException("createCategory not wired"))
    },
    onAssignCategories: (add: Set<Long>, remove: Set<Long>) -> Unit = { _, _ -> },
    onHideSelected: () -> Unit = {},
    onToggleChosenSelected: () -> Unit = {},
    onOpenItem: (item: MediaItem, filters: ViewerFilters, hero: HeroOrigin?) -> Unit = { _, _, _ -> },
    actions: GalleryActionCallbacks = GalleryActionCallbacks(),
    guest: GuestUploadUi = GuestUploadUi(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    /** 앨범 칸에서 연 화면이면 그 범위 — 출처 탭 대신 ← 와 이름 */
    scope: GalleryScope? = null,
    onBackClick: () -> Unit = {},
    /** 하단 칸 막대. 고르는 동안에는 같은 자리를 선택 하단바가 쓴다 */
    navigationBar: @Composable () -> Unit = {},
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
                    SelectionTopBar(selectedCount = content.selectedIds.size, onClear = onClearSelection)
                } else {
                    // 탭은 상단바와 한 덩어리다 — 선택 모드로 바뀌면 함께 사라진다.
                    // 탭을 누르면 선택이 풀리므로 선택 상단바 옆에 두면 실수로 누르기 쉽다.
                    Column {
                        GalleryTopBar(
                            content = content,
                            onFavoritesOnlyChange = onFavoritesOnlyChange,
                            onNotBackedUpOnlyChange = onNotBackedUpOnlyChange,
                            onPickDateRange = { showDateRange = true },
                            onPickCategory = { showCategoryFilter = true },
                            scope = scope,
                            onBackClick = onBackClick,
                        )
                        // 앨범 하나를 보는 화면에는 출처 탭이 없다 — 이미 범위가 정해져 있다
                        if (scope == null) GallerySourceTabs(tab = content?.tab, onSelect = onTabChange)
                    }
                }
            }
        },
        bottomBar = {
            GalleryBottomBar(
                content = content.takeIf { selectionMode },
                navigationBar = navigationBar,
                uploadTargets = uploadTargets,
                guestAvailable = guest.available,
                actions = SelectionBarActions(
                    onUpload = onUploadSelected,
                    onUploadTo = onUploadSelectedTo,
                    onUploadToGuest = guest.onStart,
                    onToggleChosen = onToggleChosenSelected,
                    onTrash = actions.onTrash,
                    onDelete = actions.onDelete,
                    onToggleFavorite = actions.onToggleFavorite,
                    onHide = onHideSelected,
                    onCategories = { showCategoryPicker = true },
                    onRename = { showRename = true },
                    onMove = { showMove = true },
                ),
            )
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
                    onPickFromAll = { onTabChange(GalleryTab.ALL) },
                    onCellSizeStepChange = onCellSizeStepChange,
                    onToggleSelection = onToggleSelection,
                    onSelectionChange = onSelectionChange,
                    onOpenItem = { item, bounds ->
                        onOpenItem(item, uiState.viewerFilters(), bounds?.toHeroOrigin(item))
                    },
                    onClearDateRange = { onDateRangeChange(null) },
                    onSelectAllVisible = onSelectAllVisible,
                    onClearCategoryFilter = { onCategoryFilterChange(null) },
                    onCancelUpload = onCancelUpload,
                    onUploadQueueClick = onUploadQueueClick,
                    onRequestPermission = onRequestPermission,
                    guest = guest,
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

@Preview(showBackground = true)
@Composable
private fun GalleryScreenPermissionPreview() {
    EasyGalleryTheme {
        GalleryScreen(
            uiState = GalleryUiState.PermissionRequired,
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
    /** 탭(고른 사진·출처). 없으면 좌우로 넘길 때 탭 밖 사진이 나온다 */
    val tab: GalleryTab = GalleryTab.ALL,
    /** 앨범 필터(`relativePath`) */
    val albumPath: String? = null,
)

internal fun GalleryUiState.Content.viewerFilters() =
    ViewerFilters(favoritesOnly, dateRange, categoryFilter, tab, albumFilter?.relativePath)

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
