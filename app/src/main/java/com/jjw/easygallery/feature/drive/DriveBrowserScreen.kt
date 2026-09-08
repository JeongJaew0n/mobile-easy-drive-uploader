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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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

    LaunchedEffect(key) { viewModel.load(DriveFolder(key.folderId, key.folderName ?: rootName)) }
    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is DriveBrowserEvent.FolderCreated -> {
                    val message = resources.getString(R.string.drive_folder_created, event.folder.name)
                    snackbarHostState.showSnackbar(message)
                }
                is DriveBrowserEvent.UploadFolderSelected -> onUploadFolderSelected(event.folder)
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
    )
}

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
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
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
                title = {
                    Column {
                        Text(uiState.current?.name ?: stringResource(R.string.drive_title))
                        Text(
                            text = stringResource(R.string.drive_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
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

    if (showCreateDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                onCreateFolder(name)
            },
        )
    }
}

@Composable
private fun DriveEntryRow(
    entry: DriveEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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
    }
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
