package com.jjw.easygallery.feature.autotag

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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.data.autotag.AutoTagScheduler
import java.text.DateFormat
import java.util.Date

@Composable
fun AutoTagRoute(
    onBackClick: () -> Unit,
    viewModel: AutoTagViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            val message = when (event) {
                is AutoTagEvent.CategoryCreated ->
                    resources.getString(R.string.auto_tag_to_category_done, event.name, event.count)
                is AutoTagEvent.Error -> event.message
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    AutoTagScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onEnabledChange = viewModel::setEnabled,
        onTimeChange = viewModel::setTime,
        onScanNow = viewModel::scanNow,
        onStopScan = viewModel::stopScan,
        onHideLabel = viewModel::hideLabel,
        onUnhideLabel = viewModel::unhideLabel,
        onToggleShowHidden = viewModel::toggleShowHidden,
        onCopyToCategory = viewModel::copyToCategory,
        onClearAll = viewModel::clearAll,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoTagScreen(
    uiState: AutoTagUiState,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onTimeChange: (Int, Int) -> Unit,
    onScanNow: () -> Unit,
    onStopScan: () -> Unit,
    onHideLabel: (String) -> Unit,
    onUnhideLabel: (String) -> Unit,
    onToggleShowHidden: () -> Unit,
    onCopyToCategory: (String, String) -> Unit,
    onClearAll: () -> Unit,
) {
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.auto_tag_title)) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item(key = "enable") {
                AutoTagSwitchRow(
                    title = stringResource(R.string.auto_tag_enable),
                    description = stringResource(R.string.auto_tag_enable_description),
                    checked = uiState.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }
            item(key = "time") {
                AutoTagTimeRow(
                    minuteOfDay = uiState.minuteOfDay,
                    enabled = uiState.enabled,
                    onClick = { showTimePicker = true },
                )
            }
            item(key = "status") {
                AutoTagStatusRow(
                    uiState = uiState,
                    onScanNow = onScanNow,
                    onStopScan = onStopScan,
                    onClear = { confirmClear = true },
                )
            }
            item(key = "divider") { HorizontalDivider() }
            if (uiState.labels.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.auto_tag_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(uiState.labels, key = { it.label }) { row ->
                AutoTagLabelRowItem(
                    row = row,
                    onToggleHidden = { if (row.hidden) onUnhideLabel(row.label) else onHideLabel(row.label) },
                    onCopy = { display -> onCopyToCategory(row.label, display) },
                )
            }
            if (uiState.hiddenCount > 0) {
                item(key = "hidden") {
                    TextButton(onClick = onToggleShowHidden, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(
                            if (uiState.showHidden) {
                                stringResource(R.string.auto_tag_hidden_notice, uiState.hiddenCount)
                            } else {
                                stringResource(R.string.auto_tag_show_hidden)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showTimePicker) {
        AutoTagTimePickerDialog(
            minuteOfDay = uiState.minuteOfDay,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                showTimePicker = false
                onTimeChange(hour, minute)
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.auto_tag_clear)) },
            text = { Text(stringResource(R.string.auto_tag_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearAll()
                    },
                ) {
                    Text(stringResource(R.string.action_delete_forever))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun AutoTagSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AutoTagTimeRow(minuteOfDay: Int, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.auto_tag_time), style = MaterialTheme.typography.bodyLarge, color = color)
            Text(
                text = stringResource(R.string.auto_tag_time_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = "%02d:%02d".format(AutoTagScheduler.hourOf(minuteOfDay), AutoTagScheduler.minuteOf(minuteOfDay)),
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}

@Composable
private fun AutoTagStatusRow(
    uiState: AutoTagUiState,
    onScanNow: () -> Unit,
    onStopScan: () -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        val progress = uiState.progress
        Text(
            text = when {
                progress.running && progress.total > 0 ->
                    stringResource(R.string.auto_tag_scanning, progress.done, progress.total)
                // 배터리 제약이나 모델 대기로 큐에만 들어가 있는 동안. 안 그러면
                // 진행 바도 문구도 없이 "분석 중지" 버튼만 뜬 멈춘 화면이 된다
                progress.running -> stringResource(R.string.auto_tag_scan_waiting)
                uiState.lastRunMillis > 0 -> stringResource(
                    R.string.auto_tag_last_run,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(uiState.lastRunMillis)),
                )
                else -> stringResource(R.string.auto_tag_never_run)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.auto_tag_scanned_count, uiState.scannedCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress.running && progress.total > 0) {
            Spacer(Modifier.width(8.dp))
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (progress.running) {
                OutlinedButton(onClick = onStopScan) { Text(stringResource(R.string.auto_tag_scan_stop)) }
            } else {
                Button(onClick = onScanNow, enabled = !uiState.isBusy) {
                    Text(stringResource(R.string.auto_tag_scan_now))
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClear, enabled = !uiState.isBusy && uiState.scannedCount > 0) {
                Text(stringResource(R.string.auto_tag_clear))
            }
        }
    }
}

@Composable
private fun AutoTagLabelRowItem(row: AutoTagLabelRow, onToggleHidden: () -> Unit, onCopy: (String) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    val displayName = AutoTagLabels.displayNameRes(row.label)?.let { stringResource(it) } ?: row.label
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.auto_tag_label_count, row.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.auto_tag_to_category)) },
                    onClick = {
                        menuExpanded = false
                        onCopy(displayName)
                    },
                )
                DropdownMenuItem(
                    // 숨김이 저장되므로 되돌릴 자리가 필요하다 — "숨긴 라벨 보기" 상태에서 이 항목으로 푼다
                    text = {
                        Text(
                            stringResource(
                                if (row.hidden) R.string.auto_tag_unhide_label else R.string.auto_tag_hide_label,
                            ),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onToggleHidden()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoTagTimePickerDialog(minuteOfDay: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = AutoTagScheduler.hourOf(minuteOfDay),
        initialMinute = AutoTagScheduler.minuteOf(minuteOfDay),
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_tag_time)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
