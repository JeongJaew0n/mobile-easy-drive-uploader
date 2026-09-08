package com.jjw.easygallery.feature.drive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.DriveFolder
import kotlinx.coroutines.CancellationException

/**
 * 이동 대상 폴더 선택(`docs/DRIVE_FILE_CRUD.md` §4). 내 드라이브부터 폴더만 나열하고 탭하면 들어간다.
 * 상단 경로(breadcrumb)를 탭하면 그 단계로 돌아온다. 옮기는 항목이 폴더면 자기 자신은 목록에서 뺀다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveFolderPickerSheet(
    start: DriveFolder,
    excludeFolderId: String?,
    currentParentId: String,
    loadFolders: suspend (parentId: String) -> List<DriveFolder>,
    onDismiss: () -> Unit,
    onPick: (DriveFolder) -> Unit,
) {
    var path by remember { mutableStateOf(listOf(DriveFolder(ROOT_ID, ""), start).distinctBy { it.id }) }
    var folders by remember { mutableStateOf<List<DriveFolder>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val here = path.last()
    val rootName = stringResource(R.string.drive_root_name)

    LaunchedEffect(here.id) {
        folders = null
        error = null
        try {
            folders = loadFolders(here.id).filter { it.id != excludeFolderId }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            error = e.message ?: e.toString()
            folders = emptyList()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.drive_move_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                path.forEachIndexed { index, folder ->
                    if (index > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    TextButton(onClick = { path = path.take(index + 1) }) {
                        Text(if (folder.id == ROOT_ID) rootName else folder.name)
                    }
                }
            }
            Box(Modifier.height(LIST_HEIGHT_DP.dp)) {
                val list = folders
                when {
                    list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error != null -> Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    )
                    list.isEmpty() -> Text(
                        text = stringResource(R.string.drive_move_no_folders),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    )
                    else -> LazyColumn {
                        items(list, key = { it.id }) { folder ->
                            ListItem(
                                headlineContent = { Text(folder.name) },
                                leadingContent = {
                                    Icon(
                                        painterResource(R.drawable.ic_folder),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                trailingContent = {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                },
                                modifier = Modifier.clickable { path = path + folder },
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(
                    onClick = { onPick(if (here.id == ROOT_ID) DriveFolder(ROOT_ID, rootName) else here) },
                    // 원래 있던 폴더로는 이동 불가
                    enabled = here.id != currentParentId,
                ) {
                    Text(stringResource(R.string.drive_move_here))
                }
            }
        }
    }
}

private const val ROOT_ID = "root"
private const val LIST_HEIGHT_DP = 320
