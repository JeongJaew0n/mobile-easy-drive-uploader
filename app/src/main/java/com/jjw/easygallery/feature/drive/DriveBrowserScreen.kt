package com.jjw.easygallery.feature.drive

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.Capability
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.navigation.DriveBrowserKey
import com.jjw.easygallery.core.ui.motion.LocalMotion
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun DriveBrowserRoute(
    key: DriveBrowserKey,
    onOpenFolder: (DriveFolder) -> Unit,
    onUploadFolderSelected: (DriveFolder) -> Unit,
    onBackClick: () -> Unit,
    viewModel: DriveBrowserViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    val rootName = stringResource(R.string.drive_root_name)

    LaunchedEffect(key) { viewModel.load(key.accountId, key.folderId, key.folderName, rootName) }
    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is DriveBrowserEvent.FolderCreated -> {
                    val message = resources.getString(R.string.drive_folder_created, event.folder.name)
                    snackbarHostState.showSnackbar(message)
                }
                is DriveBrowserEvent.UploadFolderSelected -> onUploadFolderSelected(event.folder)
                is DriveBrowserEvent.Renamed ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.drive_renamed, event.name))
                is DriveBrowserEvent.Moved -> snackbarHostState.showSnackbar(
                    resources.getString(R.string.drive_moved, event.entry.name, event.target.name),
                )
                is DriveBrowserEvent.Trashed -> {
                    val result = snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.drive_trashed, event.entry.name),
                        actionLabel = resources.getString(R.string.action_undo),
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.restore(event.entry)
                }
                is DriveBrowserEvent.Deleted ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.drive_deleted, event.entry.name))
                DriveBrowserEvent.Restored ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.drive_restored))
                is DriveBrowserEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    DriveBrowserScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onEntryClick = { entry ->
            if (entry.isFolder) {
                onOpenFolder(entry.toFolder())
            } else {
                // 파일은 Drive 앱(설치돼 있으면) 또는 브라우저에서 연다
                entry.webViewLink?.let { link ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
        },
        onLoadMore = viewModel::loadMore,
        onRefresh = viewModel::refresh,
        onCreateFolder = viewModel::createFolder,
        onSelectAsUploadFolder = viewModel::selectAsUploadFolder,
        entryActions = DriveEntryActions(
            onOpen = { entry ->
                entry.webViewLink?.let { link ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            },
            onRename = viewModel::rename,
            onMove = viewModel::move,
            onTrash = viewModel::trash,
            loadFolders = viewModel::listFolders,
        ),
    )
}

/** 행 ⋮ 메뉴의 콜백 묶음(`docs/DRIVE_FILE_CRUD.md` §4) */
internal data class DriveEntryActions(
    val onOpen: (DriveEntry) -> Unit = {},
    val onRename: (DriveEntry, String) -> Unit = { _, _ -> },
    val onMove: (DriveEntry, DriveFolder) -> Unit = { _, _ -> },
    val onTrash: (DriveEntry) -> Unit = {},
    val loadFolders: suspend (parentId: String) -> List<DriveFolder> = { emptyList() },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveBrowserScreen(
    uiState: DriveBrowserUiState,
    onBackClick: () -> Unit,
    onEntryClick: (DriveEntry) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onSelectAsUploadFolder: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    entryActions: DriveEntryActions = DriveEntryActions(),
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<DriveEntry?>(null) }
    var moving by remember { mutableStateOf<DriveEntry?>(null) }
    var deleting by remember { mutableStateOf<DriveEntry?>(null) }
    val hasTrash = Capability.TRASH in uiState.capabilities
    val listState = rememberLazyListState()
    val motion = LocalMotion.current

    // 마지막 항목 근처에 오면 다음 페이지 요청
    LaunchedEffect(listState, uiState.entries.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .filter { last -> last != null && last >= uiState.entries.size - LOAD_MORE_THRESHOLD }
            .collect { onLoadMore() }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { BrowserTitle(folderName = uiState.current?.name, accountName = uiState.accountName) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !uiState.isLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    IconButton(
                        onClick = { showCreateDialog = true },
                        enabled = uiState.current != null && !uiState.isMutating,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_create_new_folder),
                            contentDescription = stringResource(R.string.folder_picker_new_folder),
                        )
                    }
                },
            )
        },
        bottomBar = {
            OutlinedButton(
                onClick = onSelectAsUploadFolder,
                enabled = uiState.current != null && !uiState.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(stringResource(R.string.drive_use_as_upload_folder))
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.error != null -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(uiState.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_retry)) }
                }

                uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                uiState.entries.isEmpty() -> Text(
                    text = stringResource(R.string.drive_folder_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )

                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(uiState.entries, key = { it.id }) { entry ->
                        DriveEntryRow(
                            entry = entry,
                            onClick = { onEntryClick(entry) },
                            enabled = !uiState.isMutating,
                            menu = entryMenu(entry, uiState.capabilities),
                            onOpen = { entryActions.onOpen(entry) },
                            onRename = { renaming = entry },
                            onMove = { moving = entry },
                            onTrash = { if (hasTrash) entryActions.onTrash(entry) else deleting = entry },
                            modifier = Modifier.animateItem(
                                fadeInSpec = motion.quick(),
                                placementSpec = motion.settle(),
                                fadeOutSpec = motion.quick(),
                            ),
                        )
                    }
                    if (uiState.isLoadingMore) {
                        item(key = "loading-more") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                        }
                    }
                }
            }
            if (uiState.isMutating) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    DriveBrowserDialogs(
        uiState = uiState,
        showCreateDialog = showCreateDialog,
        renaming = renaming,
        moving = moving,
        onDismissCreate = { showCreateDialog = false },
        onDismissRename = { renaming = null },
        onDismissMove = { moving = null },
        onCreateFolder = onCreateFolder,
        entryActions = entryActions,
    )
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.drive_delete_title, entry.name)) },
            text = { Text(stringResource(R.string.drive_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        entryActions.onTrash(entry)
                    },
                ) {
                    Text(stringResource(R.string.action_delete_forever))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** 제목: 현재 폴더 이름, 부제: 저장소(계정) 이름 */
@Composable
private fun BrowserTitle(folderName: String?, accountName: String?) {
    val storageName = accountName ?: stringResource(R.string.drive_title)
    Column {
        Text(folderName ?: storageName)
        Text(
            text = storageName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 행 ⋮ 메뉴에 무엇을 보일지 — 저장소 능력과 항목 종류로 결정 */
internal data class EntryMenu(
    val open: Boolean,
    val rename: Boolean,
    val move: Boolean,
    val delete: Boolean,
    val deleteIsTrash: Boolean,
)

internal fun entryMenu(entry: DriveEntry, capabilities: Set<Capability>): EntryMenu {
    val folderOk = !entry.isFolder || Capability.FOLDER_MUTATION in capabilities
    return EntryMenu(
        open = !entry.isFolder && Capability.WEB_LINK in capabilities && entry.webViewLink != null,
        rename = Capability.RENAME in capabilities && folderOk,
        move = Capability.MOVE in capabilities && folderOk,
        delete = true,
        deleteIsTrash = Capability.TRASH in capabilities,
    )
}

@Composable
private fun DriveBrowserDialogs(
    uiState: DriveBrowserUiState,
    showCreateDialog: Boolean,
    renaming: DriveEntry?,
    moving: DriveEntry?,
    onDismissCreate: () -> Unit,
    onDismissRename: () -> Unit,
    onDismissMove: () -> Unit,
    onCreateFolder: (String) -> Unit,
    entryActions: DriveEntryActions,
) {
    if (showCreateDialog) {
        CreateFolderDialog(
            onDismiss = onDismissCreate,
            onConfirm = { name ->
                onDismissCreate()
                onCreateFolder(name)
            },
        )
    }
    if (renaming != null) {
        DriveRenameDialog(
            currentName = renaming.name,
            onDismiss = onDismissRename,
            onConfirm = { name ->
                onDismissRename()
                entryActions.onRename(renaming, name)
            },
        )
    }
    val current = uiState.current
    if (moving != null && current != null) {
        DriveFolderPickerSheet(
            start = current,
            excludeFolderId = moving.takeIf { it.isFolder }?.id,
            currentParentId = current.id,
            loadFolders = entryActions.loadFolders,
            onDismiss = onDismissMove,
            onPick = { target ->
                onDismissMove()
                entryActions.onMove(moving, target)
            },
        )
    }
}

@Composable
private fun DriveEntryRow(
    entry: DriveEntry,
    onClick: () -> Unit,
    enabled: Boolean,
    menu: EntryMenu,
    onOpen: () -> Unit,
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
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryIcon(entry)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = buildList {
                entry.sizeBytes?.let { add(Formatter.formatShortFileSize(context, it)) }
                entry.modifiedTimeMillis?.let {
                    val now = System.currentTimeMillis()
                    add(DateUtils.getRelativeTimeSpanString(it, now, DateUtils.DAY_IN_MILLIS).toString())
                }
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
private fun DriveRenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.drive_rename_label)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank() && name.trim() != currentName) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun EntryIcon(entry: DriveEntry) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        entry.isFolder -> Icon(painterResource(R.drawable.ic_folder), contentDescription = null, tint = tint)
        entry.isVideo -> Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = tint)
        entry.isImage -> Icon(painterResource(R.drawable.ic_image), contentDescription = null, tint = tint)
        else -> Icon(painterResource(R.drawable.ic_insert_drive_file), contentDescription = null, tint = tint)
    }
}

@Composable
private fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_picker_new_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.folder_picker_folder_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val LOAD_MORE_THRESHOLD = 5

@Preview(showBackground = true)
@Composable
private fun DriveBrowserScreenPreview() {
    EasyGalleryTheme {
        DriveBrowserScreen(
            uiState = DriveBrowserUiState(
                current = DriveFolder("root", "내 드라이브"),
                entries = listOf(
                    DriveEntry("1", "Easy Gallery", DriveEntry.FOLDER_MIME_TYPE, null, 1_757_000_000_000, null),
                    DriveEntry("2", "IMG_0001.jpg", "image/jpeg", 3_400_000, 1_757_000_000_000, null),
                    DriveEntry("3", "회의록.docx", "application/vnd.google-apps.document", null, null, null),
                ),
                isLoading = false,
            ),
            onBackClick = {},
            onEntryClick = {},
            onLoadMore = {},
            onRefresh = {},
            onCreateFolder = {},
            onSelectAsUploadFolder = {},
        )
    }
}
