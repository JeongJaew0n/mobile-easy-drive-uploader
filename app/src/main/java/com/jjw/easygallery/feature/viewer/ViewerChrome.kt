package com.jjw.easygallery.feature.viewer

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.MediaDetails
import com.jjw.easygallery.core.domain.model.MediaItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerTopBar(
    item: MediaItem,
    position: Int,
    total: Int,
    supportsFavorites: Boolean,
    enabled: Boolean,
    onBackClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleInfo: () -> Unit,
    onRenameClick: () -> Unit,
    onMoveClick: () -> Unit,
    onUpload: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black.copy(alpha = OVERLAY_ALPHA),
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White,
        ),
        title = {
            Column {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.viewer_position, position, total),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = {
            if (supportsFavorites) {
                IconButton(onClick = onToggleFavorite, enabled = enabled) {
                    Icon(
                        imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = stringResource(
                            if (item.isFavorite) R.string.action_unfavorite else R.string.action_favorite,
                        ),
                    )
                }
            }
            IconButton(onClick = onUpload, enabled = enabled) {
                Icon(
                    painterResource(R.drawable.ic_cloud_upload),
                    contentDescription = stringResource(R.string.action_upload_to_drive),
                )
            }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onRenameClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_move)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_drive_file_move), contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onMoveClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.viewer_info)) },
                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onToggleInfo()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete_forever)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_delete_forever), contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        },
    )
}

@Composable
internal fun ViewerBottomBar(
    item: MediaItem,
    details: MediaDetails?,
    showInfo: Boolean,
    supportsTrash: Boolean,
    enabled: Boolean,
    onTrash: () -> Unit,
    onDelete: () -> Unit,
    onToggleInfo: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = OVERLAY_ALPHA)),
    ) {
        if (showInfo) {
            InfoPanel(item = item, details = details)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleInfo) {
                Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.viewer_info), tint = Color.White)
            }
            IconButton(onClick = if (supportsTrash) onTrash else onDelete, enabled = enabled) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(
                        if (supportsTrash) R.string.action_trash else R.string.action_delete_forever,
                    ),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun InfoPanel(
    item: MediaItem,
    details: MediaDetails?,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        InfoRow(stringResource(R.string.viewer_info_name), item.displayName)
        InfoRow(
            stringResource(R.string.viewer_info_size),
            Formatter.formatShortFileSize(context, item.sizeBytes),
        )
        if (item.width > 0 && item.height > 0) {
            InfoRow(
                stringResource(R.string.viewer_info_dimensions),
                stringResource(R.string.viewer_info_dimensions_value, item.width, item.height),
            )
        }
        InfoRow(stringResource(R.string.viewer_info_path), item.relativePath.ifBlank { item.bucketName })
        InfoRow(stringResource(R.string.viewer_info_mime), item.mimeType)
        if (details?.hasCameraInfo == true) {
            val camera = listOfNotNull(details.cameraMake, details.cameraModel).joinToString(" ")
            if (camera.isNotBlank()) InfoRow(stringResource(R.string.viewer_info_camera), camera)
            val exposure = listOfNotNull(
                details.aperture?.let { "f/$it" },
                details.exposureTime?.let { stringResource(R.string.viewer_info_exposure_value, it) },
                details.isoSensitivity?.let { "ISO $it" },
                details.focalLength?.let { stringResource(R.string.viewer_info_focal_value, it) },
            ).joinToString(" · ")
            if (exposure.isNotBlank()) InfoRow(stringResource(R.string.viewer_info_exposure), exposure)
        }
        if (details?.hasLocation == true) {
            InfoRow(
                stringResource(R.string.viewer_info_location),
                stringResource(R.string.viewer_info_location_value, details.latitude!!, details.longitude!!),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = LABEL_ALPHA),
            modifier = Modifier.size(width = INFO_LABEL_WIDTH_DP.dp, height = INFO_ROW_HEIGHT_DP.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal const val OVERLAY_ALPHA = 0.55f

private const val LABEL_ALPHA = 0.7f

private const val INFO_LABEL_WIDTH_DP = 92

private const val INFO_ROW_HEIGHT_DP = 20
