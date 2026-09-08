package com.jjw.easygallery.feature.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.data.remote.toFingerprintDisplay
import com.jjw.easygallery.core.domain.model.RemoteAccountKind

@Composable
fun AddRemoteAccountRoute(
    onBackClick: () -> Unit,
    viewModel: AddRemoteAccountViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(event) {
        when (val e = event) {
            AddRemoteAccountEvent.Saved -> {
                viewModel.consumeEvent()
                onBackClick()
            }
            is AddRemoteAccountEvent.Error -> {
                snackbarHostState.showSnackbar(e.message)
                viewModel.consumeEvent()
            }
            null -> Unit
        }
    }
    LaunchedEffect(uiState.testResult) {
        uiState.testResult?.let { result ->
            snackbarHostState.showSnackbar(
                result.fold(
                    onSuccess = { resources.getString(R.string.remote_test_ok) },
                    onFailure = { resources.getString(R.string.remote_test_failed, it.message ?: it.toString()) },
                ),
            )
        }
    }

    AddRemoteAccountScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onKindChange = viewModel::setKind,
        onPresetChange = viewModel::setPreset,
        onUpdate = viewModel::update,
        onTest = viewModel::testConnection,
        onSave = viewModel::save,
    )
    uiState.pendingCertSha256?.let { fingerprint ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPendingCertificate,
            title = { Text(stringResource(R.string.remote_cert_title)) },
            text = {
                Text(
                    stringResource(R.string.remote_cert_message, uiState.endpoint, fingerprint.toFingerprintDisplay()),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::trustPendingCertificate) {
                    Text(stringResource(R.string.remote_cert_trust))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPendingCertificate) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** 저장소 추가 폼(`docs/MULTI_CLOUD.md` §5, `NAS_STORAGE.md` §3) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddRemoteAccountScreen(
    uiState: AddRemoteAccountUiState,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onKindChange: (RemoteAccountKind) -> Unit,
    onPresetChange: (S3Preset) -> Unit,
    onUpdate: (AddRemoteAccountUiState.() -> AddRemoteAccountUiState) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_add)) },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.kind == RemoteAccountKind.S3,
                    onClick = { onKindChange(RemoteAccountKind.S3) },
                    label = { Text(stringResource(R.string.remote_kind_s3)) },
                )
                FilterChip(
                    selected = uiState.kind == RemoteAccountKind.WEBDAV,
                    onClick = { onKindChange(RemoteAccountKind.WEBDAV) },
                    label = { Text(stringResource(R.string.remote_kind_webdav)) },
                )
            }
            if (uiState.kind == RemoteAccountKind.S3) PresetDropdown(uiState.preset, onPresetChange)
            OutlinedTextField(
                value = uiState.displayName,
                onValueChange = { v -> onUpdate { copy(displayName = v) } },
                label = { Text(stringResource(R.string.remote_display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.endpoint,
                onValueChange = { v -> onUpdate { copy(endpoint = v) } },
                label = { Text(stringResource(R.string.remote_endpoint)) },
                supportingText = {
                    when {
                        uiState.endpoint.isNotBlank() && !uiState.endpoint.startsWith("https://") ->
                            Text(stringResource(R.string.remote_https_warning), color = MaterialTheme.colorScheme.error)
                        uiState.kind == RemoteAccountKind.WEBDAV -> Text(stringResource(R.string.remote_webdav_hint))
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (uiState.kind == RemoteAccountKind.S3) {
                OutlinedTextField(
                    value = uiState.region,
                    onValueChange = { v -> onUpdate { copy(region = v) } },
                    label = { Text(stringResource(R.string.remote_region)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = uiState.bucketOrRoot,
                    onValueChange = { v -> onUpdate { copy(bucketOrRoot = v) } },
                    label = { Text(stringResource(R.string.remote_bucket)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = uiState.username,
                onValueChange = { v -> onUpdate { copy(username = v) } },
                label = {
                    val isS3 = uiState.kind == RemoteAccountKind.S3
                    val res = if (isS3) R.string.remote_access_key else R.string.remote_username
                    Text(stringResource(res))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            uiState.certSha256?.let { pinned ->
                Text(
                    text = stringResource(R.string.remote_cert_pinned, pinned.toFingerprintDisplay()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = uiState.secret,
                onValueChange = { v -> onUpdate { copy(secret = v) } },
                label = {
                    val isS3 = uiState.kind == RemoteAccountKind.S3
                    val res = if (isS3) R.string.remote_secret_key else R.string.remote_password
                    Text(stringResource(res))
                },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text(stringResource(R.string.remote_secret_note)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = uiState.canSubmit && !uiState.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.remote_test_connection))
                }
                Button(
                    onClick = onSave,
                    enabled = uiState.canSubmit && !uiState.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetDropdown(selected: S3Preset, onSelect: (S3Preset) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource(selected.labelRes()),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.remote_preset)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            S3Preset.entries.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(stringResource(preset.labelRes())) },
                    onClick = {
                        expanded = false
                        onSelect(preset)
                    },
                )
            }
        }
    }
}

private fun S3Preset.labelRes(): Int = when (this) {
    S3Preset.NAVER -> R.string.remote_preset_naver
    S3Preset.KT -> R.string.remote_preset_kt
    S3Preset.AWS -> R.string.remote_preset_aws
    S3Preset.R2 -> R.string.remote_preset_r2
    S3Preset.CUSTOM -> R.string.remote_preset_custom
}
