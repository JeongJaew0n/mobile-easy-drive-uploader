package com.jjw.easygallery.core.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import com.jjw.easygallery.R

/**
 * 하단 칸 넷(`docs/plans/bottom-navigation/spec.md`). 사진이 시작 칸이다 — 앱을 켜면 사진 › 고른 사진.
 * 용어집의 "칸" 이다. 사진 칸 위의 "탭"(출처)과 다르다.
 */
enum class TopLevelDestination(
    val key: AppNavKey,
    @param:StringRes @get:StringRes val labelRes: Int,
    @param:DrawableRes @get:DrawableRes val iconRes: Int,
) {
    PHOTOS(GalleryKey, R.string.nav_photos, R.drawable.ic_nav_photos),
    ALBUMS(AlbumsKey, R.string.nav_albums, R.drawable.ic_nav_albums),
    BACKUP(BackupKey, R.string.nav_backup, R.drawable.ic_cloud_upload),
    MENU(MenuKey, R.string.nav_menu, R.drawable.ic_nav_menu),
    ;

    companion object {
        fun of(key: NavKey?): TopLevelDestination? = entries.find { it.key == key }
    }
}

/**
 * 칸을 바꾼다. 백스택을 `[사진]` 또는 `[사진, 그 칸]` 으로 다시 놓는다 — 다른 칸에서 뒤로 가면 사진으로,
 * 사진에서 뒤로 가면 앱을 나간다. 사진 칸은 맨 아래에 남아 있어 그 ViewModel(탭·스크롤·선택)이 유지된다.
 */
fun MutableList<NavKey>.selectTopLevel(destination: TopLevelDestination) {
    if (lastOrNull() == destination.key) return
    // 맨 아래 사진은 남겨 둔다 — 지우고 다시 넣으면 ViewModel 이 새로 만들어진다
    while (size > 1) removeAt(lastIndex)
    if (firstOrNull() != GalleryKey) {
        clear()
        add(GalleryKey)
    }
    if (destination != TopLevelDestination.PHOTOS) add(destination.key)
}

/** 칸의 첫 화면 아래에 붙는 막대. 각 칸 화면이 자기 Scaffold 의 bottomBar 로 받는다 */
@Composable
fun AppNavigationBar(current: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit) {
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == current,
                onClick = { onSelect(destination) },
                icon = { Icon(painterResource(destination.iconRes), contentDescription = null) },
                label = { Text(stringResource(destination.labelRes)) },
            )
        }
    }
}
