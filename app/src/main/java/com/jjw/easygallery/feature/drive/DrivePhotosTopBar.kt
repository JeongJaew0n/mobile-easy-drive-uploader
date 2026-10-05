package com.jjw.easygallery.feature.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.DriveMediaOrder
import com.jjw.easygallery.core.domain.model.DriveMediaScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrivePhotosTopBar(state: DrivePhotosUiState, actions: DrivePhotosActions) {
    val content = state as? DrivePhotosUiState.Content
    if (content?.isSelecting == true) {
        SelectionTopBar(content, actions)
        return
    }
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.drive_photos_title))
                if (content != null) {
                    Text(
                        text = stringResource(
                            R.string.drive_photos_subtitle,
                            scopeLabel(content.scope),
                            stringResource(orderLabel(content.order)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = actions.onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = {
            IconButton(onClick = actions.onOpenFolders) {
                Icon(
                    painterResource(R.drawable.ic_folder),
                    contentDescription = stringResource(R.string.drive_photos_open_folders),
                )
            }
            if (content != null) OverflowMenu(content.order, actions)
        },
    )
}

@Composable
private fun OverflowMenu(order: DriveMediaOrder, actions: DrivePhotosActions) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DriveMediaOrder.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(stringResource(orderLabel(option))) },
                leadingIcon = { if (option == order) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onOrder(option)
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_refresh)) },
            onClick = {
                open = false
                actions.onRefresh()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(state: DrivePhotosUiState.Content, actions: DrivePhotosActions) {
    TopAppBar(
        title = { Text(stringResource(R.string.gallery_selected_count, state.selectedIds.size)) },
        navigationIcon = {
            IconButton(onClick = actions.onClearSelection) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_selection))
            }
        },
        actions = {
            IconButton(onClick = actions.onSelectAll) {
                Icon(
                    painterResource(R.drawable.ic_select_all),
                    contentDescription = stringResource(R.string.action_select_all),
                )
            }
            IconButton(onClick = actions.onDownloadSelected) {
                Icon(
                    painterResource(R.drawable.ic_file_download),
                    contentDescription = stringResource(R.string.drive_menu_download),
                )
            }
            if (state.canTrash) {
                IconButton(onClick = actions.onTrashSelected, enabled = !state.isMutating) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.drive_photos_trash))
                }
            }
        },
    )
}

/** 거르기(전체·기기에 없음·영상)와 범위 칩(앱이 올린 것·폴더·Drive 전체) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrivePhotosFilterBar(state: DrivePhotosUiState.Content, actions: DrivePhotosActions) {
    Column(Modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            DrivePhotoFilter.entries.forEachIndexed { index, filter ->
                SegmentedButton(
                    selected = state.filter == filter,
                    onClick = { actions.onFilter(filter) },
                    shape = SegmentedButtonDefaults.itemShape(index, DrivePhotoFilter.entries.size),
                    label = { Text(stringResource(filterLabel(filter)), maxLines = 1) },
                )
            }
        }
        val scopes = buildList {
            add(DriveMediaScope.AppUploads)
            addAll(state.folders)
            if (state.viewScopeGranted) add(DriveMediaScope.WholeDrive)
        }
        // 고를 것이 "앱이 올린 것" 하나뿐이면 칩 줄을 두지 않는다
        if (scopes.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(scopes, key = { scopeKey(it) }) { scope ->
                    FilterChip(
                        selected = state.scope == scope,
                        onClick = { actions.onScope(scope) },
                        label = { Text(scopeLabel(scope), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

private fun scopeKey(scope: DriveMediaScope): String = when (scope) {
    DriveMediaScope.AppUploads -> "app"
    is DriveMediaScope.Folder -> "folder:${scope.id}"
    DriveMediaScope.WholeDrive -> "whole"
}

@Composable
internal fun scopeLabel(scope: DriveMediaScope): String = when (scope) {
    DriveMediaScope.AppUploads -> stringResource(R.string.drive_photos_scope_app)
    is DriveMediaScope.Folder ->
        if (scope.readOnly) stringResource(R.string.drive_photos_scope_view_only, scope.name) else scope.name
    DriveMediaScope.WholeDrive -> stringResource(R.string.drive_photos_scope_whole)
}

private fun orderLabel(order: DriveMediaOrder): Int = when (order) {
    DriveMediaOrder.UPLOADED -> R.string.drive_photos_order_uploaded
    DriveMediaOrder.TAKEN -> R.string.drive_photos_order_taken
}

private fun filterLabel(filter: DrivePhotoFilter): Int = when (filter) {
    DrivePhotoFilter.ALL -> R.string.drive_photos_filter_all
    DrivePhotoFilter.NOT_ON_DEVICE -> R.string.drive_photos_filter_not_on_device
    DrivePhotoFilter.VIDEOS -> R.string.drive_photos_filter_videos
}
