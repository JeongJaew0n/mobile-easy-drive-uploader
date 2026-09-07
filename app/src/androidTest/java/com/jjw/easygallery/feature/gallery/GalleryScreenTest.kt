package com.jjw.easygallery.feature.gallery

import androidx.activity.ComponentActivity
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
            EasyGalleryTheme {
                GalleryScreen(
                    uiState = GalleryUiState.Content(sections = emptyList(), itemCount = 0, isPartialAccess = false),
                    onSettingsClick = {},
                    onRequestPermission = {},
                    onOpenAppSettings = {},
                )
            }
        }
        composeTestRule.onNodeWithText("표시할 사진이나 영상이 없습니다").assertIsDisplayed()
    }

    @Test
    fun permissionRequired_grantButtonInvokesCallback() {
        var requested = false
        composeTestRule.setContent {
            EasyGalleryTheme {
                GalleryScreen(
                    uiState = GalleryUiState.PermissionRequired,
                    onSettingsClick = {},
                    onRequestPermission = { requested = true },
                    onOpenAppSettings = {},
                )
            }
        }
        composeTestRule.onNodeWithText("권한 허용").performClick()
        assertTrue(requested)
    }
}
