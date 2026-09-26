package com.jjw.easygallery.feature.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.data.remote.MutationProgress
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
    onOpenFolder: (DriveFolder, readOnly: Boolean) -> Unit,
    onUploadFolderSelected: (DriveFolder) -> Unit,
    onBackClick: () -> Unit,
    viewModel: DriveBrowserViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    // Drive 는 "내 드라이브", 다른 저장소는 최상위 폴더라는 뜻의 중립적인 이름
    val driveRootName = stringResource(R.string.drive_root_name)
    val storageRootName = stringResource(R.string.storage_root_name)
    val rootName = if (key.accountId == null) driveRootName else storageRootName

    // 사진·영상은 앱 안에서 본다. 외부 Drive 앱으로 넘기면 파일마다 계정을 고르라고 묻는다
    var preview by remember { mutableStateOf<DriveEntry?>(null) }
    preview?.let { entry ->
        DrivePreview(
            entry = entry,
            imageLoader = viewModel.driveImageLoader,
            dataSourceFactory = viewModel.driveDataSourceFactory,
            onDismiss = { preview = null },
        )
    }

    val authRecoveryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onAuthRecoveryResult(result.data) }

    // 보기 전용 폴더용 읽기 권한 동의(`docs/DRIVE_FILE_SCOPE.md` §10)
    val viewScopeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onViewScopeResult(result.data) }
    var pickingViewFolder by remember { mutableStateOf(false) }
    if (pickingViewFolder) {
        DriveFolderPickerSheet(
            start = DriveFolder(DriveEntry.ROOT_ID, driveRootName),
            excludeFolderId = null,
            // 내 드라이브 최상위 자체는 "폴더" 가 아니라 고를 수 없게 둔다
            currentParentId = DriveEntry.ROOT_ID,
            loadFolders = viewModel::listDriveFolders,
            onDismiss = { pickingViewFolder = false },
            onPick = { folder ->
                pickingViewFolder = false
                viewModel.addViewFolder(folder)
            },
            titleRes = R.string.drive_add_view_folder,
            confirmRes = R.string.drive_add_view_folder_confirm,
        )
    }

    LaunchedEffect(key) {
        viewModel.load(key.accountId, key.folderId, key.folderName, rootName, key.readOnly)
    }
    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            showBrowserEvent(
                event = event,
                snackbarHostState = snackbarHostState,
                resources = resources,
                viewModel = viewModel,
                onUploadFolderSelected = onUploadFolderSelected,
                onViewScopeConsent = { pendingIntent ->
                    viewScopeLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
                },
                onOpenViewFolderPicker = { pickingViewFolder = true },
            )
        }
    }

    DriveBrowserScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onEntryClick = { entry ->
            if (entry.isFolder) {
                // 보기 전용은 안으로 들어가도 계속 보기 전용이다
                onOpenFolder(entry.toFolder(), uiState.isReadOnly || entry.readOnly)
            } else {
                // 사진·영상은 앱 안에서, 그 밖의 형식은 Drive 앱·브라우저에 맡긴다
                if (entry.isImage || entry.isVideo) {
                    preview = entry
                } else {
                    entry.webViewLink?.let { link -> context.openDriveLink(link, uiState.accountEmail) }
                }
            }
        },
        onLoadMore = viewModel::loadMore,
        onRefresh = viewModel::refresh,
        onRecoverAuth = { pendingIntent ->
            authRecoveryLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
        },
        onCreateFolder = viewModel::createFolder,
        onSelectAsUploadFolder = viewModel::selectAsUploadFolder,
        onAddViewFolder = viewModel::startAddViewFolder,
        entryActions = DriveEntryActions(
            onOpen = { entry ->
                if (entry.isImage || entry.isVideo) {
                    preview = entry
                } else {
                    entry.webViewLink?.let { link -> context.openDriveLink(link, uiState.accountEmail) }
                }
            },
            onRename = viewModel::rename,
            onMove = viewModel::move,
            onTrash = viewModel::trash,
            onRemoveFromList = viewModel::removeViewFolder,
            loadFolders = viewModel::listFolders,
            onDownload = viewModel::download,
            onDownloadSelected = viewModel::downloadSelected,
            onStartSearch = viewModel::startSearch,
            onSearch = viewModel::search,
            onExitSearch = viewModel::exitSearch,
            onToggleSelect = viewModel::toggleSelection,
            onSelectAll = viewModel::selectAll,
            onClearSelection = viewModel::clearSelection,
            onTrashSelected = viewModel::trashSelected,
            onMoveSelected = viewModel::moveSelected,
        ),
    )
}

/** 행 ⋮ 메뉴의 콜백 묶음(`docs/DRIVE_FILE_CRUD.md` §4) */
internal data class DriveEntryActions(
    val onOpen: (DriveEntry) -> Unit = {},
    val onRename: (DriveEntry, String) -> Unit = { _, _ -> },
    val onMove: (DriveEntry, DriveFolder) -> Unit = { _, _ -> },
    val onTrash: (DriveEntry) -> Unit = {},
    /** 보기 전용 폴더를 목록에서만 뺀다 */
    val onRemoveFromList: (DriveEntry) -> Unit = {},
    val loadFolders: suspend (parentId: String) -> List<DriveFolder> = { emptyList() },
    // 다운로드
    val onDownload: (DriveEntry) -> Unit = {},
    val onDownloadSelected: () -> Unit = {},
    // 검색
    val onStartSearch: () -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val onExitSearch: () -> Unit = {},
    // 다중 선택
    val onToggleSelect: (DriveEntry) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onTrashSelected: () -> Unit = {},
    val onMoveSelected: (DriveFolder) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveBrowserScreen(
    uiState: DriveBrowserUiState,
    onBackClick: () -> Unit,
    onEntryClick: (DriveEntry) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onRecoverAuth: (PendingIntent) -> Unit,
    onCreateFolder: (String) -> Unit,
    onSelectAsUploadFolder: () -> Unit,
    onAddViewFolder: () -> Unit = {},
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    entryActions: DriveEntryActions = DriveEntryActions(),
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<DriveEntry?>(null) }
    var moving by remember { mutableStateOf<DriveEntry?>(null) }
    var deleting by remember { mutableStateOf<DriveEntry?>(null) }
    var movingSelection by rememberSaveable { mutableStateOf(false) }
    var deletingSelection by rememberSaveable { mutableStateOf(false) }
    val hasTrash = Capability.TRASH in uiState.capabilities
    BrowserBackHandlers(uiState, entryActions)
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
            BrowserTopBar(
                uiState = uiState,
                onBackClick = onBackClick,
                onRefresh = onRefresh,
                onCreateFolder = { showCreateDialog = true },
                onMoveSelection = { movingSelection = true },
                onDeleteSelection = { if (hasTrash) entryActions.onTrashSelected() else deletingSelection = true },
                entryActions = entryActions,
            )
        },
        bottomBar = { BrowserBottomBar(uiState, onSelectAsUploadFolder, onAddViewFolder) },
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
                    // 권한이 끊긴 것이면 새로고침은 같은 오류만 반복한다 — 재동의를 띄워야 빠져나간다(SS-11)
                    val recovery = uiState.authRecovery
                    if (recovery != null) {
                        TextButton(onClick = { onRecoverAuth(recovery) }) {
                            Text(stringResource(R.string.drive_reauthorize))
                        }
                    } else {
                        TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_retry)) }
                    }
                }

                uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                uiState.entries.isEmpty() -> Text(
                    text = stringResource(emptyMessageRes(uiState)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )

                else -> PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(uiState.entries, key = { it.id }) { entry ->
                            DriveEntryRow(
                                entry = entry,
                                onClick = {
                                    if (uiState.isSelecting) entryActions.onToggleSelect(entry) else onEntryClick(entry)
                                },
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
                                onRename = { renaming = entry },
                                onMove = { moving = entry },
                                onTrash = { if (hasTrash) entryActions.onTrash(entry) else deleting = entry },
                                onRemoveFromList = { entryActions.onRemoveFromList(entry) },
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
            }
            if (uiState.isMutating) MutationProgressBar(uiState.mutationProgress)
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
    DriveSelectionDialogs(
        uiState = uiState,
        deletingSelection = deletingSelection,
        movingSelection = movingSelection,
        onDismissDelete = { deletingSelection = false },
        onDismissMove = { movingSelection = false },
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

/** 뒤로 가기: 선택 모드면 선택 해제, 검색 모드면 검색 종료(화면을 나가지 않는다) */
@Composable
private fun BrowserBackHandlers(uiState: DriveBrowserUiState, entryActions: DriveEntryActions) {
    BackHandler(enabled = uiState.isSelecting) { entryActions.onClearSelection() }
    BackHandler(enabled = uiState.isSearching && !uiState.isSelecting) { entryActions.onExitSearch() }
}

/** 하단 "이 폴더를 업로드 폴더로 지정" — 검색 결과에는 폴더 문맥이 없어 숨긴다 */
@Composable
private fun BrowserBottomBar(
    uiState: DriveBrowserUiState,
    onSelectAsUploadFolder: () -> Unit,
    onAddViewFolder: () -> Unit,
) {
    if (uiState.isSearching) return
    Column(
        // 인셋이 없으면 버튼 아래 절반이 시스템 내비게이션 바에 가려 눌리지 않는다(실기기 확인)
        Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Drive 루트에서만. 보기 전용 폴더를 여기 목록에 더한다(`docs/DRIVE_FILE_SCOPE.md` §10)
        if (uiState.isPickedRoot) {
            OutlinedButton(
                onClick = onAddViewFolder,
                enabled = !uiState.isLoading && !uiState.isMutating,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Text(stringResource(R.string.drive_add_view_folder))
            }
        }
        // 읽기 권한뿐인 폴더는 업로드 대상이 될 수 없다
        if (!uiState.isReadOnly) {
            OutlinedButton(
                onClick = onSelectAsUploadFolder,
                enabled = uiState.current != null && !uiState.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Text(stringResource(R.string.drive_use_as_upload_folder))
            }
        }
    }
}

/** Drive 는 전체 검색, 나머지는 현재 폴더 필터 — 힌트 문구가 다르다 */
@StringRes
private fun searchHintRes(uiState: DriveBrowserUiState): Int =
    if (Capability.SEARCH in uiState.capabilities) R.string.drive_search_hint else R.string.drive_filter_hint

@StringRes
private fun emptyMessageRes(uiState: DriveBrowserUiState): Int = when {
    uiState.searchQuery?.isBlank() == true && Capability.SEARCH in uiState.capabilities -> R.string.drive_search_prompt
    uiState.isSearching -> R.string.drive_search_empty
    uiState.isPickedRoot -> R.string.drive_root_empty
    // 보기 전용 폴더는 읽기 권한이라 안이 전부 보인다 — "앱으로 올린 것만 보인다" 는 거짓말이 된다
    uiState.isReadOnly -> R.string.drive_folder_empty
    // drive.file 에서는 남이 넣은 파일이 보이지 않는다 — 고장이 아니라는 것을 화면이 말해야 한다
    Capability.SEARCH in uiState.capabilities -> R.string.drive_folder_empty_scoped
    else -> R.string.drive_folder_empty
}

/** 일반 상단바 / 선택 모드 상단바 / 검색 상단바 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserTopBar(
    uiState: DriveBrowserUiState,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
    onCreateFolder: () -> Unit,
    onMoveSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    entryActions: DriveEntryActions,
) {
    if (uiState.isSelecting) {
        DriveSelectionTopBar(
            count = uiState.selectedIds.size,
            canMove = Capability.MOVE in uiState.capabilities && !uiState.isRemoteSearchResult,
            canDelete = !uiState.isReadOnlyHere,
            canDownload = Capability.DOWNLOAD in uiState.capabilities,
            onDownload = entryActions.onDownloadSelected,
            enabled = !uiState.isMutating,
            onClose = entryActions.onClearSelection,
            onSelectAll = entryActions.onSelectAll,
            onMove = onMoveSelection,
            onDelete = onDeleteSelection,
        )
        return
    }
    if (uiState.searchQuery != null) {
        DriveSearchTopBar(
            query = uiState.searchQuery,
            hintRes = searchHintRes(uiState),
            onQueryChange = entryActions.onSearch,
            onExit = entryActions.onExitSearch,
        )
        return
    }
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
            IconButton(onClick = entryActions.onStartSearch, enabled = !uiState.isMutating && !uiState.isLoading) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(searchHintRes(uiState)))
            }
            IconButton(onClick = onRefresh, enabled = !uiState.isLoading) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
            // 루트 자리는 실제 폴더가 아니라 목록이라 거기에 폴더를 만들 수 없다.
            // 보기 전용 폴더는 읽기 권한뿐이라 만들어도 403 이다
            if (!uiState.isPickedRoot && !uiState.isReadOnly) {
                IconButton(
                    onClick = onCreateFolder,
                    enabled = uiState.current != null && !uiState.isMutating,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_create_new_folder),
                        contentDescription = stringResource(R.string.folder_picker_new_folder),
                    )
                }
            }
        },
    )
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
            onRecoverAuth = {},
            onCreateFolder = {},
            onSelectAsUploadFolder = {},
        )
    }
}

/** 연결된 계정으로 연다. 여러 계정이 로그인돼 있어도 파일마다 고르라고 묻지 않는다 */
private fun Context.openDriveLink(link: String, accountEmail: String?) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(driveLinkForAccount(link, accountEmail)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

/** 변경 중 진행바 — 오브젝트 단위 진행을 아는 제공자(S3)는 "n / total", 아니면 불확정 */
@Composable
private fun MutationProgressBar(progress: MutationProgress?) {
    if (progress == null || progress.total == 0) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    Column(Modifier.fillMaxWidth()) {
        val fraction by animateFloatAsState(
            targetValue = progress.done.toFloat() / progress.total,
            animationSpec = LocalMotion.current.progress(),
            label = "mutationProgress",
        )
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(
            text = stringResource(R.string.drive_mutation_progress, progress.done, progress.total),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
