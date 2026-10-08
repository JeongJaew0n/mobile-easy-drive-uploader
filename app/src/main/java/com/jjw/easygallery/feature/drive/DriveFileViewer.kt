package com.jjw.easygallery.feature.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.common.text.displayMessage
import com.jjw.easygallery.core.domain.model.DriveEntry

/**
 * 기기 사진 상세보기의 "Drive 에서 보기" — 그 사진의 Drive 사본을 **앱 안에서** 연다(`docs/plans/drive-feedback/spec.md` §3, 결정 A).
 * Drive 앱으로 넘기면 계정 선택이 뜨고 엉뚱한 계정이 골라져 있었다(2026-10-08 기기).
 *
 * 기기에 이미 있는 사진의 사본이라 받기 단추는 없다. Drive 휴지통(실행 취소 있음)과, 그래도 Drive 앱으로 가고 싶을 때의 단추만 둔다.
 * 지금 계정이 읽을 수 없으면(다른 계정으로 올린 것) 까닭을 말하고 Drive 앱을 대신 연다.
 */
@Composable
fun DriveFileViewer(
    fileId: String,
    /** Drive 앱으로 열 때 고를 계정 */
    openAs: String?,
    onDismiss: () -> Unit,
    viewModel: DriveFileViewModel = hiltViewModel(key = "drive-file:$fileId"),
) {
    LaunchedEffect(fileId) { viewModel.load(fileId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val openInDriveApp = { context.openDriveLink(driveFileLink(fileId), openAs) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DriveFileEvent.Trashed -> {
                    val result = snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.drive_trashed, event.name),
                        actionLabel = resources.getString(R.string.action_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.restore() else onDismiss()
                }
                DriveFileEvent.Restored -> snackbarHostState.showSnackbar(resources.getString(R.string.drive_restored))
                is DriveFileEvent.Error -> snackbarHostState.showSnackbar(event.error.displayMessage(resources))
            }
        }
    }
    when (val s = state) {
        DriveFileUiState.Loading -> Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Color.White) }
        }
        DriveFileUiState.Unreadable -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(stringResource(R.string.drive_file_unreadable)) },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    openInDriveApp()
                }) { Text(stringResource(R.string.drive_file_open_in_app)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        )
        is DriveFileUiState.Loaded -> DriveMediaPager(
            entries = listOf(s.entry),
            initialId = s.entry.id,
            imageLoader = viewModel.driveImageLoader,
            dataSourceFactory = viewModel.driveDataSourceFactory,
            onDismiss = onDismiss,
            actions = DriveViewerActions(
                onTrash = { _: DriveEntry -> viewModel.trash() }.takeIf { s.canTrash && !s.trashed },
                onOpenInDrive = { _: DriveEntry -> openInDriveApp() },
            ),
            // 기기 사진에서 왔으니 이 기기에도 있다
            onDeviceIds = setOf(s.entry.id),
            snackbarHostState = snackbarHostState,
        )
    }
}
