package com.jjw.easygallery.feature.drive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.ui.motion.LocalMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Drive 화면 본문 — 목록 또는 격자(`docs/DRIVE_PHOTO_GRID.md`). 두 모양이 같은 행([DriveEntryRow])과
 * 같은 "다음 쪽 읽기" 를 쓴다. 격자는 폴더만 행으로 두고 사진·영상을 썸네일 칸으로 편다.
 */
@Composable
internal fun DriveEntries(
    uiState: DriveBrowserUiState,
    gridView: Boolean,
    imageLoader: ImageLoader?,
    onEntryClick: (DriveEntry) -> Unit,
    onLoadMore: () -> Unit,
    entryActions: DriveEntryActions,
    onRename: (DriveEntry) -> Unit,
    onMove: (DriveEntry) -> Unit,
    onTrash: (DriveEntry) -> Unit,
) {
    val click: (DriveEntry) -> Unit = { entry ->
        if (uiState.isSelecting) entryActions.onToggleSelect(entry) else onEntryClick(entry)
    }
    val row: @Composable (DriveEntry, Modifier) -> Unit = { entry, modifier ->
        DriveEntryRow(
            entry = entry,
            onClick = { click(entry) },
            onLongClick = { entryActions.onToggleSelect(entry) },
            selected = entry.id in uiState.selectedIds,
            selecting = uiState.isSelecting,
            enabled = !uiState.isMutating,
            menu = entryMenu(
                entry,
                uiState.capabilities,
                allowMove = !uiState.isRemoteSearchResult,
                isPickedRoot = uiState.isPickedRoot,
                isReadOnly = uiState.isReadOnlyHere,
            ),
            uploadedFromDevice = entry.id in uiState.uploadedFromDeviceIds,
            onOpen = { entryActions.onOpen(entry) },
            onDownload = { entryActions.onDownload(entry) },
            onRename = { onRename(entry) },
            onMove = { onMove(entry) },
            onTrash = { onTrash(entry) },
            onRemoveFromList = { entryActions.onRemoveFromList(entry) },
            // 루트는 서로 다른 계정의 폴더가 섞이는 유일한 자리다
            ownerLabel = if (uiState.isPickedRoot) ownerLabelOf(entry.ownerEmail, uiState.accountEmail) else null,
            modifier = modifier,
        )
    }
    if (gridView && imageLoader != null) {
        val gridState = rememberLazyGridState()
        LoadMoreWhenNearEnd(uiState.entries.size, onLoadMore) {
            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        }
        DriveGrid(
            entries = uiState.entries,
            state = gridState,
            imageLoader = imageLoader,
            selectedIds = uiState.selectedIds,
            selecting = uiState.isSelecting,
            onClick = click,
            onLongClick = { entryActions.onToggleSelect(it) },
            folderRow = { entry -> row(entry, Modifier) },
            footer = { if (uiState.isLoadingMore) LoadingMore() },
        )
    } else {
        val listState = rememberLazyListState()
        val motion = LocalMotion.current
        LoadMoreWhenNearEnd(uiState.entries.size, onLoadMore) {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(uiState.entries, key = { it.id }) { entry ->
                row(
                    entry,
                    Modifier.animateItem(
                        fadeInSpec = motion.quick(),
                        placementSpec = motion.settle(),
                        fadeOutSpec = motion.quick(),
                    ),
                )
            }
            if (uiState.isLoadingMore) item(key = "loading-more") { LoadingMore() }
        }
    }
}

/** 마지막 항목 근처가 보이면 다음 쪽을 부른다. 목록·격자 둘 다 */
@Composable
private fun LoadMoreWhenNearEnd(count: Int, onLoadMore: () -> Unit, lastVisible: () -> Int?) {
    LaunchedEffect(count) {
        snapshotFlow(lastVisible)
            .distinctUntilChanged()
            .filter { last -> last != null && last >= count - LOAD_MORE_THRESHOLD }
            .collect { onLoadMore() }
    }
}

@Composable
private fun LoadingMore() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
}

private const val LOAD_MORE_THRESHOLD = 5
