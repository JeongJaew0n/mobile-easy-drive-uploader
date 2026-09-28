package com.jjw.easygallery.feature.drive

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.common.text.displayMessage
import com.jjw.easygallery.core.domain.model.DriveFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 폴더 선택 시트. 내 드라이브부터 폴더만 나열하고 탭하면 들어간다.
 * 상단 경로(breadcrumb)를 탭하면 그 단계로 돌아온다.
 *
 * 두 곳에서 쓴다 — 이동 대상 고르기(`docs/DRIVE_FILE_CRUD.md` §4)와 보기 전용 폴더
 * 추가(`docs/DRIVE_FILE_SCOPE.md` §10). 문구만 [titleRes]·[confirmRes] 로 갈아끼운다.
 *
 * [currentParentId] 는 고를 수 없는 폴더다 — 이동에서는 "원래 있던 곳", 보기 폴더 추가에서는
 * 내 드라이브 루트(그건 폴더가 아니라 최상위라 목록에 넣을 것이 못 된다).
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
    @StringRes titleRes: Int = R.string.drive_move_title,
    @StringRes confirmRes: Int = R.string.drive_move_here,
    /** [currentParentId] 말고도 고를 수 없는 것 — "공유 문서함" 처럼 폴더가 아니라 목록인 자리 */
    unpickableIds: Set<String> = emptySet(),
    /** 한 줄 아래에 붙일 설명(소유자 등). 이름이 같은 폴더를 가른다 */
    supportingText: @Composable (DriveFolder) -> String? = { null },
    /**
     * 주면 "새 폴더" 가 생긴다. 지금 보고 있는 자리에 만들고 곧장 그 안으로 들어간다 — 만든 폴더에
     * 올리려는 것이 대부분이라 한 번 더 찾아 누르게 하지 않는다. 다른 계정 업로드가 쓴다(guest spec §7).
     */
    onCreateFolder: (suspend (name: String, parentId: String) -> DriveFolder)? = null,
    /** 경로 맨 앞 이름. 기본은 "내 드라이브" — 남의 드라이브를 훑을 때는 누구 것인지 적는다 */
    rootLabel: String? = null,
) {
    var path by remember { mutableStateOf(listOf(DriveFolder(ROOT_ID, ""), start).distinctBy { it.id }) }
    var folders by remember { mutableStateOf<List<DriveFolder>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val here = path.last()
    val rootName = rootLabel ?: stringResource(R.string.drive_root_name)
    // LaunchedEffect 안에서 문장을 만든다 — Composable 밖이라 리소스를 미리 잡아 둔다
    val resources = LocalResources.current

    LaunchedEffect(here.id) {
        folders = null
        error = null
        try {
            folders = loadFolders(here.id).filter { it.id != excludeFolderId }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            error = e.displayMessage(resources)
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
                text = stringResource(titleRes),
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
            FolderList(
                folders = folders,
                error = error,
                supportingText = supportingText,
                onOpen = { folder -> path = path + folder },
            )
            PickerActions(
                canCreate = onCreateFolder != null,
                canPick = here.id != currentParentId && here.id !in unpickableIds,
                confirmRes = confirmRes,
                onCreate = { creating = true },
                onDismiss = onDismiss,
                onPick = { onPick(if (here.id == ROOT_ID) DriveFolder(ROOT_ID, rootName) else here) },
            )
        }
    }

    if (creating && onCreateFolder != null) {
        CreateFolderDialog(
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                scope.launch {
                    try {
                        val made = onCreateFolder(name.trim(), here.id)
                        path = path + made
                    } catch (e: CancellationException) {
                        throw e
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        error = e.displayMessage(resources)
                    }
                }
            },
        )
    }
}

/** 폴더 목록 칸 — 읽는 중 / 오류 / 빈 폴더 / 목록 */
@Composable
private fun FolderList(
    folders: List<DriveFolder>?,
    error: String?,
    supportingText: @Composable (DriveFolder) -> String?,
    onOpen: (DriveFolder) -> Unit,
) {
    Box(Modifier.height(LIST_HEIGHT_DP.dp)) {
        when {
            folders == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            error != null -> Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
            )
            folders.isEmpty() -> Text(
                text = stringResource(R.string.drive_move_no_folders),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
            )
            else -> LazyColumn {
                items(folders, key = { it.id }) { folder ->
                    ListItem(
                        headlineContent = { Text(folder.name) },
                        supportingContent = supportingText(folder)?.let { text -> { Text(text) } },
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
                        modifier = Modifier.clickable { onOpen(folder) },
                    )
                }
            }
        }
    }
}

/** 하단 버튼 줄. "새 폴더" 는 왼쪽 끝, 취소·확인은 오른쪽 */
@Composable
private fun PickerActions(
    canCreate: Boolean,
    canPick: Boolean,
    @StringRes confirmRes: Int,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        if (canCreate) {
            TextButton(onClick = onCreate) { Text(stringResource(R.string.folder_picker_new_folder)) }
            Spacer(Modifier.weight(1f))
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        // 원래 있던 폴더로는 이동 불가. 목록 자리(공유 문서함 등)도 고를 수 없다
        TextButton(onClick = onPick, enabled = canPick) { Text(stringResource(confirmRes)) }
    }
}

private const val ROOT_ID = "root"
private const val LIST_HEIGHT_DP = 320
