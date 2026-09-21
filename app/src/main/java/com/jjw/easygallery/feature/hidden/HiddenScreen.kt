package com.jjw.easygallery.feature.hidden

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.feature.gallery.GalleryGrid

/**
 * 숨긴 사진 화면. PIN 을 통과하기 전에는 [HiddenPinGate] 만 보여준다 —
 * 잠긴 동안에는 목록을 만들지도 않는다(`HiddenViewModel`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HiddenScreen(
    uiState: HiddenUiState,
    snackbarHostState: SnackbarHostState,
    pinError: String?,
    onBackClick: () -> Unit,
    onSetPin: (pin: String, confirm: String) -> Unit,
    onVerify: (String) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
    onClearSelection: () -> Unit,
    onUnhideSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unlocked = uiState as? HiddenUiState.Unlocked
    val selectionMode = unlocked?.isSelectionMode == true
    BackHandler(enabled = selectionMode, onBack = onClearSelection)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (selectionMode) {
                                stringResource(R.string.gallery_selected_count, unlocked.selectedIds.size)
                            } else {
                                stringResource(R.string.hidden_title)
                            },
                        )
                        if (unlocked != null && !selectionMode) {
                            Text(
                                text = stringResource(R.string.hidden_count, unlocked.itemCount),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (selectionMode) onClearSelection else onBackClick) {
                        Icon(
                            if (selectionMode) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                if (selectionMode) R.string.action_clear_selection else R.string.action_back,
                            ),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (selectionMode) {
                BottomAppBar {
                    TextButton(onClick = onUnhideSelected, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.action_unhide))
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (uiState) {
                HiddenUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is HiddenUiState.Locked -> HiddenPinGate(
                    state = uiState,
                    onSetPin = onSetPin,
                    onVerify = onVerify,
                    errorText = pinError,
                )
                is HiddenUiState.Unlocked -> HiddenList(
                    uiState = uiState,
                    onToggleSelection = onToggleSelection,
                    onSelectionChange = onSelectionChange,
                )
            }
        }
    }
}

@Composable
private fun HiddenList(
    uiState: HiddenUiState.Unlocked,
    onToggleSelection: (Long) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
) {
    if (uiState.sections.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.hidden_empty))
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        // 거짓으로 안심시키지 않는다 — 다른 갤러리 앱에서는 그대로 보인다
        Text(
            text = stringResource(R.string.hidden_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
