package com.jjw.easygallery.feature.trash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.ui.media.MediaActionEffect
import com.jjw.easygallery.feature.gallery.GalleryGrid

@Composable
fun TrashRoute(
    onBackClick: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    MediaActionEffect(
        events = viewModel.actionEvents,
        snackbarHostState = snackbarHostState,
        onConsentResult = viewModel::onConsentResult,
        onActionDone = { viewModel.clearSelection() },
    )

    TrashScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onToggleSelection = viewModel::toggleSelection,
        onSelectionChange = viewModel::setSelection,
        onClearSelection = viewModel::clearSelection,
        onRestore = viewModel::restoreSelected,
        onDelete = viewModel::deleteSelected,
        onEmptyTrash = viewModel::emptyTrash,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrashScreen(
    uiState: TrashUiState,
    onBackClick: () -> Unit,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onEmptyTrash: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectionChange: (Set<Long>) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    BackHandler(enabled = uiState.isSelectionMode, onBack = onClearSelection)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (uiState.isSelectionMode) {
                                stringResource(R.string.gallery_selected_count, uiState.selectedIds.size)
                            } else {
                                stringResource(R.string.trash_title)
                            },
                        )
                        if (!uiState.isSelectionMode) {
                            Text(
                                text = stringResource(R.string.trash_subtitle),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (uiState.isSelectionMode) onClearSelection else onBackClick) {
                        if (uiState.isSelectionMode) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_clear_selection),
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                actions = {
                    if (!uiState.isSelectionMode && uiState.itemCount > 0) {
                        TextButton(onClick = onEmptyTrash, enabled = !uiState.isMutating) {
                            Text(stringResource(R.string.trash_empty_all))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (uiState.isSelectionMode) {
                BottomAppBar {
                    IconButton(onClick = onRestore, enabled = !uiState.isMutating) {
                        Icon(
                            painterResource(R.drawable.ic_restore_from_trash),
                            contentDescription = stringResource(R.string.action_restore),
                        )
                    }
                    IconButton(onClick = onDelete, enabled = !uiState.isMutating) {
                        Icon(
                            painterResource(R.drawable.ic_delete_forever),
                            contentDescription = stringResource(R.string.action_delete_forever),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.error != null -> Text(
                    text = uiState.error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
                uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                uiState.sections.isEmpty() -> Text(
                    text = stringResource(R.string.trash_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
                else -> Column(Modifier.fillMaxSize()) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.gallery_media_count,
                            uiState.itemCount,
                            uiState.itemCount,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    GalleryGrid(
                        sections = uiState.sections,
                        selectedIds = uiState.selectedIds,
                        onToggleSelection = onToggleSelection,
                        onSelectionChange = onSelectionChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (uiState.isMutating) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
