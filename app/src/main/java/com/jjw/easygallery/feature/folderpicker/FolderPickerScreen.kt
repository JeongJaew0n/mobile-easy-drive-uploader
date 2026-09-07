package com.jjw.easygallery.feature.folderpicker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.navigation.FolderPickerKey
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme

@Composable
fun FolderPickerRoute(
    key: FolderPickerKey,
    onOpenFolder: (DriveFolder) -> Unit,
    onFolderSelected: (DriveFolder) -> Unit,
    onBackClick: () -> Unit,
    viewModel: FolderPickerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(key) { viewModel.load(key.parentId, key.parentName) }
    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is FolderPickerEvent.Selected -> onFolderSelected(event.folder)
                is FolderPickerEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    FolderPickerScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onFolderClick = onOpenFolder,
        onCreateFolder = viewModel::createFolder,
        onSelectCurrent = viewModel::selectCurrent,
        onRetry = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderPickerScreen(
    uiState: FolderPickerUiState,
    onBackClick: () -> Unit,
    onFolderClick: (DriveFolder) -> Unit,
    onCreateFolder: (String) -> Unit,
    onSelectCurrent: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.folder_picker_title))
                        uiState.current?.let {
                            Text(
                                text = it.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                    IconButton(onClick = { showCreateDialog = true }, enabled = uiState.current != null) {
                        Icon(
                            painterResource(R.drawable.ic_create_new_folder),
                            contentDescription = stringResource(R.string.folder_picker_new_folder),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = onSelectCurrent,
                enabled = uiState.current != null && !uiState.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(stringResource(R.string.folder_picker_select_here))
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
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }

                uiState.isLoading && uiState.current == null ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                uiState.folders.isEmpty() -> Text(
                    text = stringResource(R.string.folder_picker_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(uiState.folders, key = { it.id }) { folder ->
                        FolderRow(folder = folder, onClick = { onFolderClick(folder) })
                    }
                }
            }
            if (uiState.isLoading && uiState.current != null) {
                CircularProgressIndicator(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp),
                )
            }
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
private fun FolderRow(
    folder: DriveFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_folder), contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Text(folder.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
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
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun FolderPickerScreenPreview() {
    EasyGalleryTheme {
        FolderPickerScreen(
            uiState = FolderPickerUiState(
                current = DriveFolder("root", "Easy Gallery"),
                folders = listOf(DriveFolder("1", "2026 여행"), DriveFolder("2", "가족")),
                isLoading = false,
            ),
            onBackClick = {},
            onFolderClick = {},
            onCreateFolder = {},
            onSelectCurrent = {},
            onRetry = {},
        )
    }
}
