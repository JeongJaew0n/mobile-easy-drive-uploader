package com.jjw.easygallery.feature.gallery

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.jjw.easygallery.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryTopBar(
    /** 아직 목록이 없으면(로딩·권한 요청) null */
    content: GalleryUiState.Content?,
    onSettingsClick: () -> Unit,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onNotBackedUpOnlyChange: (Boolean) -> Unit,
    onTrashClick: () -> Unit,
    onDriveClick: () -> Unit,
    onDuplicatesClick: () -> Unit,
    onPickDateRange: () -> Unit,
    onPickCategory: () -> Unit = {},
) {
    val itemCount = content?.itemCount
    val uploadedCount = content?.uploadedCount ?: 0
    val favoritesOnly = content?.favoritesOnly == true
    val notBackedUpOnly = content?.notBackedUpOnly == true
    val supportsTrashAndFavorites = content?.supportsTrashAndFavorites == true
    val categoryTitle = content?.let { categoryTitle(it.categoryFilter, it.categories) }
    TopAppBar(
        title = {
            Column {
                Text(
                    categoryTitle ?: stringResource(
                        when {
                            notBackedUpOnly -> R.string.gallery_title_not_backed_up
                            favoritesOnly -> R.string.gallery_title_favorites
                            else -> R.string.gallery_title
                        },
                    ),
                )
                if (itemCount != null) {
                    Text(
                        text = if (uploadedCount > 0 && !notBackedUpOnly) {
                            stringResource(R.string.gallery_media_count_with_backup, itemCount, uploadedCount)
                        } else {
                            pluralStringResource(R.plurals.gallery_media_count, itemCount, itemCount)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onPickDateRange) {
                Icon(
                    painterResource(R.drawable.ic_date_range),
                    contentDescription = stringResource(R.string.gallery_menu_date_range),
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
            }
            GalleryOverflowMenu(
                favoritesOnly = favoritesOnly,
                notBackedUpOnly = notBackedUpOnly,
                supportsTrashAndFavorites = supportsTrashAndFavorites,
                onFavoritesOnlyChange = onFavoritesOnlyChange,
                onNotBackedUpOnlyChange = onNotBackedUpOnlyChange,
                onOpenTrash = onTrashClick,
                onOpenDrive = onDriveClick,
                onOpenDuplicates = onDuplicatesClick,
                onPickCategory = onPickCategory,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    selectedCount: Int,
    onClear: () -> Unit,
    onUpload: () -> Unit,
    uploadTargets: List<UploadTargetOption> = emptyList(),
    onUploadTo: (UploadTargetOption) -> Unit = {},
) {
    var targetMenuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.gallery_selected_count, selectedCount)) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_selection))
            }
        },
        actions = {
            IconButton(onClick = onUpload) {
                Icon(
                    painterResource(R.drawable.ic_cloud_upload),
                    contentDescription = stringResource(R.string.action_upload_to_drive),
                )
            }
            // 저장소가 둘 이상이면 이번만 다른 곳으로 올릴 수 있다(설정은 그대로)
            if (uploadTargets.size > 1) {
                Box {
                    IconButton(onClick = { targetMenuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.gallery_upload_to))
                    }
                    DropdownMenu(expanded = targetMenuExpanded, onDismissRequest = { targetMenuExpanded = false }) {
                        uploadTargets.forEach { target ->
                            DropdownMenuItem(
                                text = {
                                    val label = stringResource(R.string.gallery_upload_to_item, target.name)
                                    Text(if (target.isDefault) "$label ✓" else label)
                                },
                                onClick = {
                                    targetMenuExpanded = false
                                    onUploadTo(target)
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

/**
 * 출처 탭(`docs/plans/gallery-source-tabs`). 카메라 사진 사이에 카카오톡 이미지가 섞여 들어오는 것을 가른다.
 *
 * 항목이 없는 탭도 계속 보여준다 — 데이터에 따라 탭이 생겼다 사라지면 위치가 흔들린다.
 * 개수는 라벨에 넣지 않는다. 세려면 전체 목록을 네 번 훑어야 하고, 지금 탭의 개수는 상단바에 이미 있다.
 */
@Composable
internal fun GallerySourceTabs(tab: GalleryTab?, onSelect: (GalleryTab) -> Unit) {
    val selected = tab ?: GalleryTab.ALL
    PrimaryTabRow(selectedTabIndex = selected.ordinal) {
        GalleryTab.entries.forEach { entry ->
            Tab(
                selected = entry == selected,
                onClick = { onSelect(entry) },
                text = { Text(stringResource(entry.labelRes())) },
            )
        }
    }
}

private fun GalleryTab.labelRes(): Int = when (this) {
    GalleryTab.ALL -> R.string.gallery_tab_all
    GalleryTab.CAMERA -> R.string.gallery_tab_camera
    GalleryTab.SCREENSHOT -> R.string.gallery_tab_screenshot
    GalleryTab.OTHER -> R.string.gallery_tab_other
}
