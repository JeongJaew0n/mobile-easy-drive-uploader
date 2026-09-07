package com.jjw.easygallery.feature.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme

@Composable
fun GalleryRoute(
    onSettingsClick: () -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    GalleryScreen(uiState = uiState, onSettingsClick = onSettingsClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryScreen(
    uiState: GalleryUiState,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery_title)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (uiState) {
                GalleryUiState.Loading -> CircularProgressIndicator()
                is GalleryUiState.Error -> Text(
                    text = uiState.throwable.localizedMessage ?: uiState.throwable.toString(),
                    color = MaterialTheme.colorScheme.error,
                )
                is GalleryUiState.Success -> {
                    if (uiState.items.isEmpty()) {
                        Text(stringResource(R.string.gallery_empty))
                    } else {
                        Text(
                            pluralStringResource(
                                R.plurals.gallery_media_count,
                                uiState.items.size,
                                uiState.items.size,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GalleryScreenEmptyPreview() {
    EasyGalleryTheme {
        GalleryScreen(uiState = GalleryUiState.Success(emptyList()), onSettingsClick = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun GalleryScreenLoadingPreview() {
    EasyGalleryTheme {
        GalleryScreen(uiState = GalleryUiState.Loading, onSettingsClick = {})
    }
}
