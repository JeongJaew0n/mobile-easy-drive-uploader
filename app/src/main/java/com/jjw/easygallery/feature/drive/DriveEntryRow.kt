package com.jjw.easygallery.feature.drive

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.jjw.easygallery.core.domain.model.DriveEntry

@Composable
internal fun DriveEntryRow(
    entry: DriveEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean,
    selecting: Boolean,
    enabled: Boolean,
    uploadedFromDevice: Boolean,
    menu: EntryMenu,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) Checkbox(checked = selected, onCheckedChange = { onLongClick() }) else EntryIcon(entry)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val uploadedLabel = stringResource(R.string.drive_uploaded_from_device)
            val details = buildList {
                entry.sizeBytes?.let { add(Formatter.formatShortFileSize(context, it)) }
                entry.modifiedTimeMillis?.let {
                    val now = System.currentTimeMillis()
                    add(DateUtils.getRelativeTimeSpanString(it, now, DateUtils.DAY_IN_MILLIS).toString())
                }
                if (uploadedFromDevice) add(uploadedLabel)
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (entry.isFolder) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        if (selecting) return@Row
        Box {
            IconButton(onClick = { menuExpanded = true }, enabled = enabled) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                if (menu.open) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.drive_menu_open)) },
                        onClick = {
                            menuExpanded = false
                            onOpen()
                        },
                    )
                }
                if (menu.download) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.drive_menu_download)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_file_download), contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDownload()
                        },
                    )
                }
                if (menu.rename) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                }
                if (menu.move) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.drive_menu_move)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_drive_file_move), contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onMove()
                        },
                    )
                }
                DropdownMenuItem(
                    text = {
                        val label = if (menu.deleteIsTrash) R.string.action_trash else R.string.action_delete_forever
                        Text(stringResource(label))
                    },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onTrash()
                    },
                )
            }
        }
    }
}

@Composable
internal fun EntryIcon(entry: DriveEntry) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        entry.isFolder -> Icon(painterResource(R.drawable.ic_folder), contentDescription = null, tint = tint)
        entry.isVideo -> Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = tint)
        entry.isImage -> Icon(painterResource(R.drawable.ic_image), contentDescription = null, tint = tint)
        else -> Icon(painterResource(R.drawable.ic_insert_drive_file), contentDescription = null, tint = tint)
    }
}
