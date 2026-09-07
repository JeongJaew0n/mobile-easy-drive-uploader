package com.jjw.easygallery.feature.settings

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme

@Composable
fun SettingsRoute(
    onBackClick: () -> Unit,
    onUploadFolderClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onConsentResult(result.resultCode, result.data) }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is SettingsEvent.LaunchConsent ->
                    consentLauncher.launch(IntentSenderRequest.Builder(event.pendingIntent).build())
                SettingsEvent.SignedIn ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.settings_signed_in))
                SettingsEvent.SignInCancelled ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.settings_sign_in_cancelled))
                is SettingsEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    SettingsScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onSignInClick = viewModel::signIn,
        onSignOutClick = viewModel::signOut,
        onUploadFolderClick = onUploadFolderClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onUploadFolderClick: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountCard(
                uiState = uiState,
                onSignInClick = onSignInClick,
                onSignOutClick = onSignOutClick,
            )
            if (uiState.isSignedIn) {
                UploadFolderRow(
                    folderName = uiState.uploadFolderName,
                    onClick = onUploadFolderClick,
                )
            }
        }
    }
}

@Composable
private fun AccountCard(
    uiState: SettingsUiState,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.settings_account_section),
                style = MaterialTheme.typography.titleMedium,
            )
            if (uiState.isSignedIn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AccountCircle, contentDescription = null, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        uiState.accountName?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        Text(
                            text = uiState.accountEmail.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val used = uiState.storageUsedBytes
                val limit = uiState.storageLimitBytes
                if (used != null) {
                    val usedText = Formatter.formatShortFileSize(context, used)
                    val text = if (limit != null) {
                        val limitText = Formatter.formatShortFileSize(context, limit)
                        stringResource(R.string.settings_storage_usage, usedText, limitText)
                    } else {
                        stringResource(R.string.settings_storage_usage_unlimited, usedText)
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onSignOutClick, enabled = !uiState.isBusy) {
                    Text(stringResource(R.string.settings_sign_out))
                }
            } else {
                Text(
                    text = stringResource(R.string.settings_account_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onSignInClick, enabled = !uiState.isBusy) {
                    if (uiState.isBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.settings_sign_in))
                }
            }
            Text(
                text = stringResource(R.string.settings_scope_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UploadFolderRow(
    folderName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_folder), contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_upload_folder), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = folderName ?: stringResource(R.string.settings_upload_folder_default),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        HorizontalDivider()
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenSignedOutPreview() {
    EasyGalleryTheme {
        SettingsScreen(
            uiState = SettingsUiState(),
            onBackClick = {},
            onSignInClick = {},
            onSignOutClick = {},
            onUploadFolderClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenSignedInPreview() {
    EasyGalleryTheme {
        SettingsScreen(
            uiState = SettingsUiState(
                isSignedIn = true,
                accountEmail = "someone@gmail.com",
                accountName = "홍길동",
                storageUsedBytes = 3_200_000_000,
                storageLimitBytes = 16_106_127_360,
                uploadFolderName = "Easy Gallery",
            ),
            onBackClick = {},
            onSignInClick = {},
            onSignOutClick = {},
            onUploadFolderClick = {},
        )
    }
}
