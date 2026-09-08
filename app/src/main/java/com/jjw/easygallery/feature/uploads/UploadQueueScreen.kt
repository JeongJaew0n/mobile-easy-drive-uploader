package com.jjw.easygallery.feature.uploads

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.UploadState
import com.jjw.easygallery.core.domain.model.UploadSummary
import com.jjw.easygallery.core.domain.model.UploadTask
import com.jjw.easygallery.core.ui.motion.LocalMotion
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme

@Composable
fun UploadQueueRoute(
    onBackClick: () -> Unit,
    viewModel: UploadQueueViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    UploadQueueScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onRetryFailed = { viewModel.retryFailed() },
        onClearCompleted = { viewModel.clearCompleted() },
        onCancelAll = { viewModel.cancelAll() },
        onRemove = { viewModel.remove(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UploadQueueScreen(
    uiState: UploadQueueUiState,
    onBackClick: () -> Unit,
    onRetryFailed: () -> Unit,
    onClearCompleted: () -> Unit,
    onCancelAll: () -> Unit,
    onRemove: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.upload_queue_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            QueueActions(
                summary = uiState.summary,
                onRetryFailed = onRetryFailed,
                onClearCompleted = onClearCompleted,
                onCancelAll = onCancelAll,
            )
            if (uiState.tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.upload_queue_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(uiState.tasks, key = { it.id }) { task ->
                        UploadTaskRow(
                            task = task,
                            onRemove = { onRemove(task.id) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = motion.quick(),
                                placementSpec = motion.settle(),
                                fadeOutSpec = motion.quick(),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueActions(
    summary: UploadSummary,
    onRetryFailed: () -> Unit,
    onClearCompleted: () -> Unit,
    onCancelAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        if (summary.failed > 0) {
            TextButton(onClick = onRetryFailed) { Text(stringResource(R.string.upload_queue_retry_failed)) }
        }
        if (summary.completed > 0) {
            TextButton(onClick = onClearCompleted) { Text(stringResource(R.string.upload_queue_clear_completed)) }
        }
        if (summary.hasActive) {
            TextButton(onClick = onCancelAll) { Text(stringResource(R.string.upload_queue_cancel_all)) }
        }
    }
}

@Composable
private fun UploadTaskRow(
    task: UploadTask,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateIcon(task.state)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = task.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when (task.state) {
                UploadState.PENDING -> stringResource(R.string.upload_state_pending)
                UploadState.RUNNING -> stringResource(
                    R.string.upload_state_running,
                    Formatter.formatShortFileSize(context, task.bytesUploaded),
                    Formatter.formatShortFileSize(context, task.sizeBytes),
                )
                UploadState.COMPLETED -> stringResource(R.string.upload_state_completed, task.folderName.orEmpty())
                UploadState.FAILED -> task.errorMessage ?: stringResource(R.string.upload_state_failed)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (task.state == UploadState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.state == UploadState.RUNNING) {
                Spacer(Modifier.height(6.dp))
                val fraction by animateFloatAsState(
                    targetValue = task.fraction,
                    animationSpec = LocalMotion.current.progress(),
                    label = "taskProgress",
                )
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_remove))
        }
    }
}

@Composable
private fun StateIcon(state: UploadState) {
    when (state) {
        UploadState.RUNNING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        UploadState.COMPLETED -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        UploadState.FAILED -> Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        UploadState.PENDING -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun UploadQueueScreenPreview() {
    val base = UploadTask(
        id = 1,
        mediaId = 1,
        uri = android.net.Uri.EMPTY,
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 4_000_000,
        folderId = "f",
        folderName = "Easy Gallery",
        state = UploadState.RUNNING,
        sessionUri = null,
        bytesUploaded = 1_500_000,
        driveFileId = null,
        errorMessage = null,
        attemptCount = 0,
        createdAt = 0,
    )
    EasyGalleryTheme {
        UploadQueueScreen(
            uiState = UploadQueueUiState(
                tasks = listOf(
                    base,
                    base.copy(id = 2, displayName = "VID_0002.mp4", state = UploadState.PENDING),
                    base.copy(id = 3, displayName = "IMG_0003.jpg", state = UploadState.COMPLETED),
                    base.copy(id = 4, displayName = "IMG_0004.jpg", state = UploadState.FAILED, errorMessage = "403"),
                ),
                summary = UploadSummary(total = 4, active = 2, completed = 1, failed = 1, current = base),
            ),
            onBackClick = {},
            onRetryFailed = {},
            onClearCompleted = {},
            onCancelAll = {},
            onRemove = {},
        )
    }
}
