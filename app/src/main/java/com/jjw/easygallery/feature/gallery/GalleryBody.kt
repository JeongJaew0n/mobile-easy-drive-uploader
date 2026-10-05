package com.jjw.easygallery.feature.gallery

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.ui.motion.LocalMotion

@Composable
internal fun GalleryContent(
    uiState: GalleryUiState.Content,
    onToggleSelection: (Long) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
    onOpenItem: (MediaItem, Rect?) -> Unit,
    onClearDateRange: () -> Unit,
    onSelectAllVisible: () -> Unit,
    onClearCategoryFilter: () -> Unit,
    onCancelUpload: () -> Unit,
    onUploadQueueClick: () -> Unit,
    onRequestPermission: () -> Unit,
    guest: GuestUploadUi = GuestUploadUi(),
    /** 빈 "고른 사진" 의 버튼 — 전체 탭으로 */
    onPickFromAll: () -> Unit = {},
    onCellSizeStepChange: (Int) -> Unit = {},
) {
    val motion = LocalMotion.current
    // 배너는 펴지며 등장해 그리드를 밀어내고, 접히며 사라진다 (그리드 점프 방지). 짧게(150ms) 유지
    Column(Modifier.fillMaxSize()) {
        // 다른 계정 업로드가 끝났다 — 기기에서 그 계정을 지우라고(앱은 직접 못 지운다)
        AnimatedVisibility(
            visible = guest.cleanupEmail != null,
            enter = motion.enterExpand(),
            exit = motion.exitShrink(),
        ) {
            GuestCleanupBanner(
                email = guest.cleanupEmail.orEmpty(),
                onOpenSettings = guest.onOpenAccountSettings,
                onDismiss = guest.onDismissCleanup,
            )
        }
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
            UploadFailedBanner(
                failed = uiState.upload.failed,
                onClick = onUploadQueueClick,
                reason = uiState.upload.failureReason,
            )
        }
        AnimatedVisibility(
            visible = uiState.isPartialAccess,
            enter = motion.enterExpand(),
            exit = motion.exitShrink(),
        ) {
            PartialAccessBanner(onManageSelection = onRequestPermission)
        }
        GalleryFilterBars(
            uiState = uiState,
            onClearDateRange = onClearDateRange,
            onSelectAllVisible = onSelectAllVisible,
            onClearCategoryFilter = onClearCategoryFilter,
        )
        if (uiState.sections.isEmpty()) {
            val messageRes = uiState.emptyMessageRes()
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(messageRes),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                // 튜토리얼 대신 빈 화면에서 바로 갈 곳을 준다(docs/plans/ux-round2/spec.md §2)
                if (messageRes == R.string.gallery_chosen_empty) {
                    FilledTonalButton(onClick = onPickFromAll, modifier = Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.gallery_chosen_pick_from_all))
                    }
                }
            }
        } else {
            GalleryGrid(
                sections = uiState.sections,
                selectedIds = uiState.selectedIds,
                onToggleSelection = onToggleSelection,
                onSelectionChange = onSelectionChange,
                onOpenItem = onOpenItem,
                animateChanges = uiState.animateItemChanges,
                backupBadgeOf = uiState::backupBadgeOf,
                cellSizeStep = uiState.cellSizeStep,
                onCellSizeStepChange = onCellSizeStepChange,
                showDateScroller = true,
                categoryColorsOf = categoryBadgeColors(uiState),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 항목별 카테고리 배지 색(최대 3개). 설정이 꺼져 있으면 항상 빈 목록 */
@Composable
private fun categoryBadgeColors(content: GalleryUiState.Content): (Long) -> List<Int> {
    if (!content.showCategoryBadges || content.assignments.isEmpty()) return { emptyList() }
    val colorById = remember(content.categories) { content.categories.associate { it.id to it.colorIndex } }
    val ordered = remember(content.categories) { content.categories.map { it.id } }
    val assignments = content.assignments
    return { mediaId ->
        val assigned = assignments[mediaId]
        if (assigned.isNullOrEmpty()) {
            emptyList()
        } else {
            ordered.asSequence().filter { it in assigned }.mapNotNull { colorById[it] }.take(MAX_BADGE_DOTS).toList()
        }
    }
}

private const val MAX_BADGE_DOTS = 3

/**
 * 선택 하단바와 하단 칸 막대가 같은 자리를 나눠 쓴다 — 아래에서 올라오고 내려가며 바뀐다.
 * [content] 가 있으면(고르는 중) 선택 하단바, 없으면 칸 막대.
 */
@Composable
internal fun GalleryBottomBar(
    content: GalleryUiState.Content?,
    navigationBar: @Composable () -> Unit,
    uploadTargets: List<UploadTargetOption>,
    guestAvailable: Boolean,
    actions: SelectionBarActions,
) {
    val motion = LocalMotion.current
    AnimatedContent(
        targetState = content,
        contentKey = { it != null },
        transitionSpec = { motion.enterFromBottom() togetherWith motion.exitToBottom() },
        label = "galleryBottomBar",
    ) { selecting ->
        if (selecting == null) {
            navigationBar()
        } else {
            SelectionBottomBar(
                selectedCount = selecting.selectedIds.size,
                allFavorite = selecting.selectedAllFavorite,
                allChosen = selecting.selectedAllChosen,
                supportsTrashAndFavorites = selecting.supportsTrashAndFavorites,
                enabled = !selecting.isMutating,
                uploadTargets = uploadTargets,
                guestAvailable = guestAvailable,
                actions = actions,
            )
        }
    }
}
