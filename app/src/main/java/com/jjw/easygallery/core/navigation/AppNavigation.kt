package com.jjw.easygallery.core.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import com.jjw.easygallery.core.ui.motion.MotionSpecs
import com.jjw.easygallery.feature.autobackup.AutoBackupRoute
import com.jjw.easygallery.feature.drive.DriveBrowserRoute
import com.jjw.easygallery.feature.duplicates.DuplicatesRoute
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
        // fade-through: 새 화면은 살짝 작은 상태에서 커지며 나타나고, 이전 화면은 빨리 사라진다.
        // 상세보기(히어로): 갤러리를 불투명하게 남겨 두고 뷰어만 페이드인 — 검은 배경이 서서히 덮이고 썸네일이
        // 제자리에서 커진다. 두 화면을 동시에 반투명하게 하면 뒤의 윈도우 배경이 비쳐 '찰칵' 번쩍인다(VIEWER_STABILITY §7).
        // 상세보기(히어로 없음)는 조금 더 작게(0.92)서 커진다
        transitionSpec = {
            val target = targetState.entries.lastOrNull()?.contentKey
            when {
                target is MediaViewerKey && target.hero != null ->
                    fadeIn(motion.standard()) togetherWith ExitTransition.KeepUntilTransitionsFinished
                target is MediaViewerKey -> motion.fadeThrough(VIEWER_ENTER_SCALE)
                else -> motion.fadeThrough(ENTER_SCALE)
            }
        },
        // 뒤로: 이전 화면은 제자리에서 나타나고, 떠나는 화면이 작아지며 사라진다.
        // 상세보기에서 돌아올 때는 갤러리를 즉시 깔고 뷰어만 축소·페이드(역방향도 반투명 겹침 없이)
        popTransitionSpec = { popTransition(motion, initialState.entries.lastOrNull()?.contentKey) },
        // 예측 뒤로가기 제스처 중에도 같은 연출을 진행률에 따라 보여준다
        predictivePopTransitionSpec = { _ -> popTransition(motion, initialState.entries.lastOrNull()?.contentKey) },
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
                    onDuplicatesClick = { backStack.add(DuplicatesKey) },
                    onOpenItem = { item, favoritesOnly, range, hero ->
                        backStack.add(
                            MediaViewerKey(
                                mediaId = item.id,
                                favoritesOnly = favoritesOnly,
                                startEpochDay = range?.start?.toEpochDay(),
                                endEpochDay = range?.endInclusive?.toEpochDay(),
                                hero = hero,
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
                    onDuplicatesClick = { backStack.add(DuplicatesKey) },
                )
            }
            entry<DuplicatesKey> {
                DuplicatesRoute(onBackClick = { backStack.removeLastOrNull() })
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

private fun MotionSpecs.fadeThrough(scale: Float): ContentTransform =
    (fadeIn(standard()) + scaleIn(standard(), initialScale = scale)) togetherWith fadeOut(quick())

private fun popTransition(motion: MotionSpecs, leaving: Any?): ContentTransform =
    if (leaving is MediaViewerKey) {
        EnterTransition.None togetherWith
            (fadeOut(motion.standard()) + scaleOut(motion.standard(), targetScale = VIEWER_ENTER_SCALE))
    } else {
        fadeIn(motion.standard()) togetherWith
            (fadeOut(motion.standard()) + scaleOut(motion.standard(), targetScale = ENTER_SCALE))
    }

private const val ENTER_SCALE = 0.96f
private const val VIEWER_ENTER_SCALE = 0.92f
