package com.jjw.easygallery.core.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.jjw.easygallery.feature.drive.DriveBrowserRoute
import com.jjw.easygallery.feature.gallery.GalleryRoute
import com.jjw.easygallery.feature.settings.SettingsRoute
import com.jjw.easygallery.feature.trash.TrashRoute
import com.jjw.easygallery.feature.uploads.UploadQueueRoute

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
                GalleryRoute(
                    onSettingsClick = { backStack.add(SettingsKey) },
                    onUploadQueueClick = { backStack.add(UploadQueueKey) },
                    onTrashClick = { backStack.add(TrashKey) },
                    onDriveClick = { backStack.add(DriveBrowserKey()) },
                )
            }
            entry<SettingsKey> {
                SettingsRoute(
                    onBackClick = { backStack.removeLastOrNull() },
                    onUploadFolderClick = { backStack.add(DriveBrowserKey()) },
                    onUploadQueueClick = { backStack.add(UploadQueueKey) },
                    onDriveClick = { backStack.add(DriveBrowserKey()) },
                )
            }
            entry<UploadQueueKey> {
                UploadQueueRoute(onBackClick = { backStack.removeLastOrNull() })
            }
            entry<TrashKey> {
                TrashRoute(onBackClick = { backStack.removeLastOrNull() })
            }
            entry<DriveBrowserKey> { key ->
                DriveBrowserRoute(
                    key = key,
                    onOpenFolder = { folder -> backStack.add(DriveBrowserKey(folder.id, folder.name)) },
                    // 업로드 폴더를 지정하면 Drive 탐색 스택 전체를 걷어내고 이전 화면으로 복귀
                    onUploadFolderSelected = { backStack.removeAll { it is DriveBrowserKey } },
                    onBackClick = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}
