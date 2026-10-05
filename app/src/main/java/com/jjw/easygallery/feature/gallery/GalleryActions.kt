package com.jjw.easygallery.feature.gallery

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.data.media.MediaAction
import com.jjw.easygallery.core.domain.model.Album

/**
 * 사진 칸의 ⋮ — **지금 보는 목록을 거르는 것만** 남았다(`docs/plans/bottom-navigation/spec.md`).
 * 다른 화면으로 가는 것(앨범·숨긴 사진·중복·Drive·휴지통 등)은 하단 칸으로 옮겼다.
 */
@Composable
internal fun GalleryOverflowMenu(
    favoritesOnly: Boolean,
    notBackedUpOnly: Boolean,
    /** 즐겨찾기 앨범을 보고 있으면 "즐겨찾기만 보기" 는 뜻이 없다 */
    showFavoritesToggle: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onNotBackedUpOnlyChange: (Boolean) -> Unit,
    onPickCategory: () -> Unit,
    showNotBackedUpToggle: Boolean = true,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
    }
    // 기간 선택은 자주 쓰는 것이라 여기 두지 않고 상단바 아이콘으로 뺐다(GalleryTopBar)
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        if (showFavoritesToggle) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (favoritesOnly) R.string.gallery_menu_show_all else R.string.gallery_menu_favorites_only,
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                onClick = {
                    expanded = false
                    onFavoritesOnlyChange(!favoritesOnly)
                },
            )
        }
        if (showNotBackedUpToggle) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (notBackedUpOnly) {
                                R.string.gallery_menu_show_all
                            } else {
                                R.string.gallery_menu_not_backed_up_only
                            },
                        ),
                    )
                },
                leadingIcon = { Icon(painterResource(R.drawable.ic_cloud_done), contentDescription = null) },
                onClick = {
                    expanded = false
                    onNotBackedUpOnlyChange(!notBackedUpOnly)
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.gallery_menu_category)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_label), contentDescription = null) },
            onClick = {
                expanded = false
                onPickCategory()
            },
        )
    }
}

@Composable
internal fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentName.substringBeforeLast('.', currentName)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.gallery_rename_label)) },
                )
                Text(
                    text = stringResource(R.string.gallery_rename_hint, currentName.substringAfterLast('.', "")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * 기존 앨범 중 선택하거나 새 앨범 이름을 입력 → RELATIVE_PATH 로 변환.
 * 목록이 있는 입력이라 다이얼로그 대신 바텀시트(M3 스프링 슬라이드) 를 쓴다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoveDialog(
    albums: List<Album>,
    onDismiss: () -> Unit,
    onConfirm: (relativePath: String) -> Unit,
) {
    var selectedPath by rememberSaveable { mutableStateOf(albums.firstOrNull()?.relativePath ?: "") }
    var newAlbum by rememberSaveable { mutableStateOf("") }
    val target = if (newAlbum.isNotBlank()) newAlbumPath(newAlbum) else selectedPath

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.action_move),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Column {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(albums, key = { it.relativePath }) { album ->
                        ListItem(
                            headlineContent = { Text(album.name) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.gallery_move_album_subtitle,
                                        album.relativePath,
                                        album.itemCount,
                                    ),
                                )
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = newAlbum.isBlank() && selectedPath == album.relativePath,
                                    onClick = {
                                        newAlbum = ""
                                        selectedPath = album.relativePath
                                    },
                                )
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = newAlbum,
                    onValueChange = { newAlbum = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.gallery_move_new_album)) },
                    supportingText = {
                        if (newAlbum.isNotBlank()) Text(newAlbumPath(newAlbum))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = { onConfirm(target) }, enabled = target.isNotBlank()) {
                    Text(stringResource(R.string.action_move))
                }
            }
        }
    }
}

/** 새 앨범은 Pictures/ 아래에 만든다 (사진·영상 모두 허용되는 경로). */
internal fun newAlbumPath(name: String): String = "Pictures/${name.trim().replace('/', '_')}/"

/** 편집 완료 스낵바 문구 */
internal fun actionDoneMessage(resources: Resources, action: MediaAction, affected: Int): String {
    val count = if (affected > 0) affected else action.items.size
    return when (action) {
        is MediaAction.Delete -> resources.getQuantityString(R.plurals.gallery_deleted, count, count)
        is MediaAction.Trash -> if (action.trashed) {
            resources.getQuantityString(R.plurals.gallery_trashed, count, count)
        } else {
            resources.getQuantityString(R.plurals.gallery_restored, count, count)
        }
        is MediaAction.Favorite -> if (action.favorite) {
            resources.getQuantityString(R.plurals.gallery_favorited, count, count)
        } else {
            resources.getQuantityString(R.plurals.gallery_unfavorited, count, count)
        }
        is MediaAction.Rename -> resources.getString(R.string.gallery_renamed, action.newDisplayName)
        is MediaAction.Move ->
            resources.getQuantityString(R.plurals.gallery_moved, count, count, action.relativePath)
    }
}
