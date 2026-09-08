package com.jjw.easygallery.feature.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.CategoryFilter
import com.jjw.easygallery.core.domain.model.DateRange
import com.jjw.easygallery.core.navigation.MediaViewerKey
import com.jjw.easygallery.core.ui.media.MediaActionEffect

@Composable
fun MediaViewerRoute(
    key: MediaViewerKey,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: MediaViewerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(key) {
        val range = if (key.startEpochDay != null && key.endEpochDay != null) {
            DateRange.of(key.startEpochDay, key.endEpochDay)
        } else {
            null
        }
        val category = when {
            key.uncategorizedOnly -> CategoryFilter.Uncategorized
            !key.categoryIds.isNullOrEmpty() -> CategoryFilter.Any(key.categoryIds.toSet())
            else -> null
        }
        viewModel.load(key.mediaId, key.favoritesOnly, range, category)
    }

    MediaActionEffect(
        events = viewModel.actionEvents,
        snackbarHostState = snackbarHostState,
        onConsentResult = viewModel::onConsentResult,
    )

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is MediaViewerEvent.Enqueued -> snackbarHostState.showSnackbar(
                    resources.getQuantityString(R.plurals.gallery_upload_enqueued, event.added, event.added),
                )
                MediaViewerEvent.SignInRequired -> {
                    snackbarHostState.showSnackbar(resources.getString(R.string.gallery_sign_in_required))
                    onSettingsClick()
                }
                is MediaViewerEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // 마지막 항목까지 삭제하면 볼 것이 없으므로 닫는다
    LaunchedEffect(uiState.isLoading, uiState.items.size) {
        if (!uiState.isLoading && uiState.items.isEmpty()) onBackClick()
    }

    // 히어로 연출은 첫 진입 1회만. 회전·복원 후에는 좌표가 어긋날 수 있어 건너뛴다
    var heroDone by rememberSaveable { mutableStateOf(key.hero == null) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        MediaViewerScreen(
            uiState = uiState,
            snackbarHostState = snackbarHostState,
            contentHidden = !heroDone,
            onBackClick = onBackClick,
            onPageChanged = viewModel::onPageChanged,
            onToggleFavorite = viewModel::toggleFavorite,
            onTrash = viewModel::trash,
            onDelete = viewModel::delete,
            onRename = viewModel::rename,
            onMove = viewModel::move,
            onUpload = viewModel::upload,
            onToggleInfo = viewModel::toggleInfo,
        )
        val hero = key.hero
        if (!heroDone && hero != null) {
            HeroOverlay(origin = hero, mediaId = key.mediaId, onFinished = { heroDone = true })
        }
    }
}
