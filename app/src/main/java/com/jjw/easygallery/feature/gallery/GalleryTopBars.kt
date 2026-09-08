package com.jjw.easygallery.feature.gallery

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.jjw.easygallery.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryTopBar(
    itemCount: Int?,
    uploadedCount: Int,
    favoritesOnly: Boolean,
    notBackedUpOnly: Boolean,
    supportsTrashAndFavorites: Boolean,
    onSettingsClick: () -> Unit,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onNotBackedUpOnlyChange: (Boolean) -> Unit,
    onTrashClick: () -> Unit,
    onDriveClick: () -> Unit,
    onPickDateRange: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    stringResource(
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
                onPickDateRange = onPickDateRange,
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
) {
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
        },
    )
}
