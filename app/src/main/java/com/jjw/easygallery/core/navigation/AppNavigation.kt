package com.jjw.easygallery.core.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.jjw.easygallery.feature.gallery.GalleryRoute
import com.jjw.easygallery.feature.settings.SettingsRoute

@Composable
fun AppNavigation() {
    val backStack = rememberNavBackStack(GalleryKey)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            // 각 NavEntry 에 ViewModel 스코프를 부여 (hiltViewModel() 이 엔트리 단위로 동작)
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<GalleryKey> {
                GalleryRoute(onSettingsClick = { backStack.add(SettingsKey) })
            }
            entry<SettingsKey> {
                SettingsRoute(onBackClick = { backStack.removeLastOrNull() })
            }
        },
    )
}
