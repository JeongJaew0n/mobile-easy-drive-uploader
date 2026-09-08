package com.jjw.easygallery.feature.duplicates

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.DuplicateGroup
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.ui.media.MediaActionEffect

@Composable
fun DuplicatesRoute(
    onBackClick: () -> Unit,
    viewModel: DuplicatesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    MediaActionEffect(
        events = viewModel.actionEvents,
        snackbarHostState = snackbarHostState,
        onConsentResult = viewModel::onConsentResult,
        onActionDone = { viewModel.resetSelection() },
    )

    DuplicatesScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onScan = viewModel::startScan,
        onCancelScan = viewModel::cancelScan,
        onToggle = viewModel::toggle,
        onReset = viewModel::resetSelection,
        onRemoveSelected = { viewModel.removeSelected(uiState.supportsTrash) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DuplicatesScreen(
    uiState: DuplicatesUiState,
    onBackClick: () -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onToggle: (Long) -> Unit,
    onReset: () -> Unit,
    onRemoveSelected: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val context = LocalContext.current
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.duplicates_title))
                        if (uiState.groups.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.duplicates_summary,
                                    uiState.groups.size,
                                    uiState.duplicateCount,
                                    Formatter.formatShortFileSize(context, uiState.wastedBytes),
                                ),
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
                    if (uiState.scan.running) {
                        TextButton(onClick = onCancelScan) { Text(stringResource(R.string.action_cancel)) }
                    } else {
                        IconButton(onClick = onScan) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.duplicates_scan))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (uiState.selectedIds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onReset) { Text(stringResource(R.string.duplicates_reset)) }
                    Button(
                        onClick = onRemoveSelected,
                        enabled = !uiState.isMutating,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Text(
                            text = stringResource(
                                if (uiState.supportsTrash) {
                                    R.string.duplicates_trash_selected
                                } else {
                                    R.string.duplicates_delete_selected
                                },
                                uiState.selectedIds.size,
                                Formatter.formatShortFileSize(context, uiState.selectedBytes),
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.scan.running) {
                ScanProgressBar(done = uiState.scan.done, total = uiState.scan.total)
            }
            when {
                uiState.isLoading -> Unit
                uiState.groups.isEmpty() -> EmptyState(scanning = uiState.scan.running, onScan = onScan)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(uiState.groups, key = { it.sha256 }) { group ->
                        DuplicateGroupCard(group = group, selectedIds = uiState.selectedIds, onToggle = onToggle)
                    }
                }
            }
            if (uiState.isMutating) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ScanProgressBar(done: Int, total: Int) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = if (total > 0) {
                stringResource(R.string.duplicates_scanning_progress, done, total)
            } else {
                stringResource(R.string.duplicates_scanning)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (total > 0) {
            LinearProgressIndicator(
                progress = { done.toFloat() / total },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        } else {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(scanning: Boolean, onScan: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(if (scanning) R.string.duplicates_scanning else R.string.duplicates_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.duplicates_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (!scanning) {
            Button(onClick = onScan, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.duplicates_scan))
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(
                R.string.duplicates_group_title,
                group.items.size,
                Formatter.formatShortFileSize(context, group.items.first().sizeBytes),
            ),
            style = MaterialTheme.typography.titleSmall,
        )
        // LazyColumn 안에서 높이가 정해지지 않는 중첩 그리드 대신 행 단위로 직접 배치한다
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            group.items.chunked(GROUP_COLUMNS).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowItems.forEach { item ->
                        Box(Modifier.weight(1f)) {
                            DuplicateThumbnail(
                                item = item,
                                isKeep = item.id == group.keepId,
                                marked = item.id in selectedIds,
                                onToggle = { onToggle(item.id) },
                            )
                        }
                    }
                    repeat(GROUP_COLUMNS - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun DuplicateThumbnail(
    item: MediaItem,
    isKeep: Boolean,
    marked: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle),
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 제거 대상은 빨간 체크, 유지 항목은 라벨
        if (marked) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.duplicates_marked),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(22.dp)
                    .background(Color.White, CircleShape),
            )
        }
        if (isKeep && !marked) {
            Text(
                text = stringResource(R.string.duplicates_keep),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = LABEL_BG_ALPHA))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Text(
            text = item.relativePath.ifBlank { item.bucketName },
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = LABEL_BG_ALPHA))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        if (item.isVideo) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(14.dp),
            )
        }
    }
}

private const val GROUP_COLUMNS = 3
private const val LABEL_BG_ALPHA = 0.55f
