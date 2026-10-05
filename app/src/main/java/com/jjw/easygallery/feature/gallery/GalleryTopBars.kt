package com.jjw.easygallery.feature.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryTopBar(
    /** 아직 목록이 없으면(로딩·권한 요청) null */
    content: GalleryUiState.Content?,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onNotBackedUpOnlyChange: (Boolean) -> Unit,
    onPickDateRange: () -> Unit,
    onPickCategory: () -> Unit = {},
    /** 앨범 하나(또는 즐겨찾기)만 보는 화면이면 그 범위. ← 와 앨범 이름이 붙는다 */
    scope: GalleryScope? = null,
    onBackClick: () -> Unit = {},
) {
    val itemCount = content?.itemCount
    val uploadedCount = content?.uploadedCount ?: 0
    val favoritesOnly = content?.favoritesOnly == true
    val notBackedUpOnly = content?.notBackedUpOnly == true
    val title = scopeTitle(scope, content) ?: content?.let { categoryTitle(it.categoryFilter, it.categories) }
    TopAppBar(
        navigationIcon = {
            if (scope != null) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        title = {
            Column {
                Text(
                    title ?: stringResource(
                        when {
                            notBackedUpOnly -> R.string.gallery_title_not_backed_up
                            favoritesOnly -> R.string.gallery_title_favorites
                            else -> R.string.gallery_title
                        },
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (itemCount != null) {
                    Text(
                        // 백업된 사진 화면은 세그먼트가 이미 "백업됨/안 됨" 을 말한다 — 개수만
                        text = if (uploadedCount > 0 && !notBackedUpOnly && scope != GalleryScope.Backup) {
                            stringResource(R.string.gallery_media_count_with_backup, itemCount, uploadedCount)
                        } else {
                            pluralStringResource(R.plurals.gallery_media_count, itemCount, itemCount)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onPickDateRange) {
                Icon(
                    painterResource(R.drawable.ic_date_range),
                    contentDescription = stringResource(R.string.gallery_menu_date_range),
                )
            }
            // 설정 톱니는 하단 "메뉴" 칸으로 옮겼다
            GalleryOverflowMenu(
                favoritesOnly = favoritesOnly,
                notBackedUpOnly = notBackedUpOnly,
                showFavoritesToggle = content?.supportsTrashAndFavorites == true && scope != GalleryScope.Favorites,
                // 백업된 사진 화면은 세그먼트가 같은 일을 한다
                showNotBackedUpToggle = scope != GalleryScope.Backup,
                onFavoritesOnlyChange = onFavoritesOnlyChange,
                onNotBackedUpOnlyChange = onNotBackedUpOnlyChange,
                onPickCategory = onPickCategory,
            )
        },
    )
}

/** 앨범 하나를 보는 화면의 제목. 잘 알려진 폴더(카카오톡 등)는 지금 언어의 이름으로 */
@Composable
private fun scopeTitle(scope: GalleryScope?, content: GalleryUiState.Content?): String? = when (scope) {
    null -> null
    GalleryScope.Favorites -> stringResource(R.string.gallery_title_favorites)
    GalleryScope.Backup -> stringResource(R.string.backed_up_title)
    is GalleryScope.Album -> {
        albumLabel(content?.albumFilter?.name ?: scope.relativePath.trimEnd('/').substringAfterLast('/'))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    selectedCount: Int,
    onClear: () -> Unit,
) {
    // 업로드·다른 저장소·다른 계정은 선택 하단바(업로드·더보기)로 옮겼다 — docs/plans/ux-round2/spec.md §1
    TopAppBar(
        title = { Text(stringResource(R.string.gallery_selected_count, selectedCount)) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_selection))
            }
        },
    )
}

/**
 * 출처 탭(`docs/plans/gallery-source-tabs`). 카메라 사진 사이에 카카오톡 이미지가 섞여 들어오는 것을 가른다.
 *
 * 항목이 없는 탭도 계속 보여준다 — 데이터에 따라 탭이 생겼다 사라지면 위치가 흔들린다.
 * 개수는 라벨에 넣지 않는다. 세려면 전체 목록을 네 번 훑어야 하고, 지금 탭의 개수는 상단바에 이미 있다.
 */
@Composable
internal fun GallerySourceTabs(tab: GalleryTab?, onSelect: (GalleryTab) -> Unit) {
    // 첫 목록이 오기 전에도 시작 탭에 밑줄을 둔다 — 로딩 뒤에 밑줄이 옮겨 가면 흔들려 보인다
    val selected = tab ?: GalleryTab.CHOSEN
    PrimaryTabRow(selectedTabIndex = selected.ordinal) {
        GalleryTab.entries.forEach { entry ->
            // text= 슬롯은 양옆 16dp 여백을 강제한다. 탭 다섯 개면 S23+(411dp, 글꼴 1.1)에서 칸이 82dp 라
            // 글자 자리가 50dp 뿐이고 "고른 사진"·"스크린샷" 이 두 줄로 꺾인다 — 여백을 줄이고 한 줄로 묶는다
            Tab(
                selected = entry == selected,
                onClick = { onSelect(entry) },
                modifier = Modifier.height(TAB_HEIGHT_DP.dp),
            ) {
                Text(
                    text = stringResource(entry.labelRes()),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = TAB_TEXT_PADDING_DP.dp),
                )
            }
        }
    }
}

private fun GalleryTab.labelRes(): Int = when (this) {
    GalleryTab.CHOSEN -> R.string.gallery_tab_chosen
    GalleryTab.ALL -> R.string.gallery_tab_all
    GalleryTab.CAMERA -> R.string.gallery_tab_camera
    GalleryTab.SCREENSHOT -> R.string.gallery_tab_screenshot
    GalleryTab.OTHER -> R.string.gallery_tab_other
}

private const val TAB_HEIGHT_DP = 48
private const val TAB_TEXT_PADDING_DP = 4
