package com.jjw.easygallery.feature.gallery

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GalleryScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun emptyState_showsEmptyMessage() {
        composeTestRule.setContent {
            Screen(
                uiState = GalleryUiState.Content(
                    sections = emptyList(),
                    itemCount = 0,
                    isPartialAccess = false,
                    tab = GalleryTab.ALL,
                ),
            )
        }
        composeTestRule.onNodeWithText("표시할 사진이나 영상이 없습니다").assertIsDisplayed()
    }

    /** 앱은 고른 사진 탭에서 시작한다 — 비어 있으면 넣는 방법을 알려 준다 */
    @Test
    fun chosenTabEmpty_showsHowToAdd() {
        composeTestRule.setContent {
            Screen(uiState = GalleryUiState.Content(sections = emptyList(), itemCount = 0, isPartialAccess = false))
        }
        composeTestRule.onNodeWithText("고른 사진이 없습니다", substring = true).assertIsDisplayed()
    }

    @Test
    fun permissionRequired_grantButtonInvokesCallback() {
        var requested = false
        composeTestRule.setContent {
            Screen(uiState = GalleryUiState.PermissionRequired, onRequestPermission = { requested = true })
        }
        composeTestRule.onNodeWithText("권한 허용").performClick()
        assertTrue(requested)
    }

    @Composable
    private fun Screen(uiState: GalleryUiState, onRequestPermission: () -> Unit = {}) {
        EasyGalleryTheme {
            GalleryScreen(
                uiState = uiState,
                onRequestPermission = onRequestPermission,
                onOpenAppSettings = {},
                onToggleSelection = {},
                onClearSelection = {},
                onUploadSelected = {},
                onCancelUpload = {},
                onUploadQueueClick = {},
            )
        }
    }
}
