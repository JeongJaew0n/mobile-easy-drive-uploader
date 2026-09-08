package com.jjw.easygallery.core.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.jjw.easygallery.core.ui.motion.LocalMotion
import com.jjw.easygallery.feature.autobackup.AutoBackupRoute
import com.jjw.easygallery.feature.drive.DriveBrowserRoute
import com.jjw.easygallery.feature.gallery.GalleryRoute
import com.jjw.easygallery.feature.settings.SettingsRoute
import com.jjw.easygallery.feature.trash.TrashRoute
import com.jjw.easygallery.feature.uploads.UploadQueueRoute
import com.jjw.easygallery.feature.viewer.MediaViewerRoute

@Composable
fun AppNavigation() {
    val backStack = rememberNavBackStack(GalleryKey)
    val motion = LocalMotion.current

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        // fade-through: 새 화면은 살짝 작은 상태에서 커지며 나타나고, 이전 화면은 빨리 사라진다
        transitionSpec = {
            (fadeIn(motion.standard()) + scaleIn(motion.standard(), initialScale = ENTER_SCALE)) togetherWith
                fadeOut(motion.quick())
        },
        // 뒤로: 이전 화면은 제자리에서 나타나고, 떠나는 화면이 작아지며 사라진다
        popTransitionSpec = {
            fadeIn(motion.standard()) togetherWith
                (fadeOut(motion.standard()) + scaleOut(motion.standard(), targetScale = ENTER_SCALE))
        },
        // 예측 뒤로가기 제스처 중에도 같은 연출을 진행률에 따라 보여준다
        predictivePopTransitionSpec = { _ ->
            fadeIn(motion.standard()) togetherWith
                (fadeOut(motion.standard()) + scaleOut(motion.standard(), targetScale = ENTER_SCALE))
        },
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
                    onOpenItem = { mediaId, favoritesOnly, range ->
                        backStack.add(
                            MediaViewerKey(
                                mediaId = mediaId,
                                favoritesOnly = favoritesOnly,
                                startEpochDay = range?.start?.toEpochDay(),
                                endEpochDay = range?.endInclusive?.toEpochDay(),
                            ),
                        )
                    },
                )
            }
            entry<SettingsKey> {
                SettingsRoute(
                    onBackClick = { backStack.removeLastOrNull() },
                    onUploadFolderClick = { backStack.add(DriveBrowserKey()) },
                    onUploadQueueClick = { backStack.add(UploadQueueKey) },
                    onDriveClick = { backStack.add(DriveBrowserKey()) },
                    onAutoBackupClick = { backStack.add(AutoBackupKey) },
                )
            }
            entry<AutoBackupKey> {
                AutoBackupRoute(onBackClick = { backStack.removeLastOrNull() })
            }
            entry<UploadQueueKey> {
                UploadQueueRoute(onBackClick = { backStack.removeLastOrNull() })
            }
            entry<TrashKey> {
                TrashRoute(onBackClick = { backStack.removeLastOrNull() })
            }
            entry<MediaViewerKey> { key ->
                MediaViewerRoute(
                    key = key,
                    onBackClick = { backStack.removeLastOrNull() },
                    onSettingsClick = { backStack.add(SettingsKey) },
                )
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

private const val ENTER_SCALE = 0.96f
