package com.jjw.easygallery.feature.uploads

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.jjw.easygallery.core.domain.model.RemoteAccount
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
        onClearFailed = { viewModel.clearFailed() },
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
    onClearFailed: () -> Unit = {},
) {
    val motion = LocalMotion.current
    var confirmClearFailed by rememberSaveable { mutableStateOf(false) }
    if (confirmClearFailed) {
        ClearFailedDialog(
            count = uiState.summary.failed,
            onConfirm = {
                confirmClearFailed = false
                onClearFailed()
            },
            onDismiss = { confirmClearFailed = false },
        )
    }
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
                onClearFailed = { confirmClearFailed = true },
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
                            accountName = task.accountId?.let { id ->
                                queueAccountLabel(id, uiState.accountNames)
                                    ?: stringResource(R.string.upload_account_removed)
                            },
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

/**
 * 목록 위 버튼들. 넷이 한꺼번에 나올 수 있어(실패 둘·완료·진행) 한 줄에 안 들어가면 다음 줄로 넘긴다 —
 * Row 로 두면 좁은 화면에서 마지막 버튼이 잘린다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueActions(
    summary: UploadSummary,
    onRetryFailed: () -> Unit,
    onClearCompleted: () -> Unit,
    onCancelAll: () -> Unit,
    onClearFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        if (summary.failed > 0) {
            TextButton(onClick = onRetryFailed) { Text(stringResource(R.string.upload_queue_retry_failed)) }
            TextButton(onClick = onClearFailed) { Text(stringResource(R.string.upload_queue_clear_failed)) }
        }
        if (summary.completed > 0) {
            TextButton(onClick = onClearCompleted) { Text(stringResource(R.string.upload_queue_clear_completed)) }
        }
        if (summary.hasActive) {
            TextButton(onClick = onCancelAll) { Text(stringResource(R.string.upload_queue_cancel_all)) }
        }
    }
}

/** 수천 건을 한 번에 지우므로 한 번 묻는다. 무엇이 남는지(사진·기록)를 같이 말해 겁주지 않는다 */
@Composable
private fun ClearFailedDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.upload_queue_clear_failed_title, count)) },
        text = { Text(stringResource(R.string.upload_queue_clear_failed_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.upload_queue_clear_failed)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun UploadTaskRow(
    task: UploadTask,
    accountName: String?,
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
                UploadState.FAILED -> failureText(task)
            }.let { text -> if (accountName != null) "$accountName · $text" else text }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (task.state == UploadState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                // 실패 사유는 두 줄로 잘리면 무슨 일인지 알 수 없다. 서버 원문이 섞여도 읽히게 넉넉히 준다
                maxLines = if (task.state == UploadState.FAILED) FAILURE_MAX_LINES else 2,
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

/**
 * 실패 한 줄. **서버가 준 원문을 그대로 쓰지 않는다** — 예전에는 Drive 의 오류 JSON 이
 * 통째로 들어가 `업로드 실패 (403): {  "error": {…` 로 잘렸다(2026-09-26 기기에서 확인).
 *
 * 서버가 준 코드(`errorReason`)를 아는 것이면 우리 문장으로 바꾼다. 모르는 코드면
 * 그때만 원문을 보여준다 — 영어라도 없는 것보다는 낫다.
 */
@Composable
private fun failureText(task: UploadTask): String {
    val known = when (task.errorReason) {
        "storageQuotaExceeded" -> R.string.upload_error_storage_full
        "rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded" -> R.string.upload_error_rate_limited
        "insufficientFilePermissions", "forbidden" -> R.string.upload_error_no_permission
        "notFound" -> R.string.upload_error_folder_missing
        "authError", "unauthorized" -> R.string.upload_error_sign_in
        "guestAccountUnavailable" -> R.string.upload_error_guest_unavailable
        else -> null
    }
    if (known != null) return stringResource(known)
    return task.errorMessage ?: stringResource(R.string.upload_state_failed)
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

/**
 * 목록 한 줄에 붙일 "어디로 가는지". 모르면 null — 화면이 "연결 해제된 저장소" 로 쓴다.
 *
 * 다른 계정 업로드(`google:<이메일>`)는 등록된 저장소가 아니다 — 앱이 B 를 기억하지 않도록 일부러 등록하지
 * 않았다(`docs/plans/guest-account-upload/spec.md` §5). 그래서 등록 목록에서만 찾으면 늘 못 찾아
 * "연결 해제된 저장소" 로 떨어졌다. 올리는 중인데 끊겼다고 말하는 셈이었다(2026-09-27 Flip 4).
 */
internal fun queueAccountLabel(accountId: String, registeredNames: Map<String, String>): String? =
    RemoteAccount.guestEmailOf(accountId) ?: registeredNames[accountId]

/** 실패 사유가 잘리지 않도록 넉넉히. 우리 문장은 한 줄이지만 서버 원문은 길 수 있다 */
private const val FAILURE_MAX_LINES = 4
