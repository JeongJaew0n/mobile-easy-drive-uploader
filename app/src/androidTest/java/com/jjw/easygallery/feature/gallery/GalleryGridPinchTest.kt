package com.jjw.easygallery.feature.gallery

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 격자 두 손가락 확대·축소(`docs/plans/ux-round2/spec.md` §6) — **실기기의 진짜 멀티터치 입력**으로 확인한다.
 * adb `input` 은 손가락 하나만 넣을 수 있어 수동 테스트 UX2-09·13 을 여기서 대신한다(2026-10-08).
 */
class GalleryGridPinchTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val items = (1L..120L).map { id ->
        MediaItem(
            id = id,
            uri = Uri.parse("content://media/external/images/media/0"),
            displayName = "IMG_$id.jpg",
            type = MediaType.IMAGE,
            mimeType = "image/jpeg",
            sizeBytes = 1,
            dateTakenMillis = 1_757_000_000_000 - id * 3_600_000,
            bucketId = 1,
            bucketName = "Camera",
            relativePath = "DCIM/Camera/",
        )
    }

    /** 화면에 보이는 첫 칸의 폭 — 고정 격자에서는 두 손가락 움직임이 스크롤이 되어 IMG_1 이 밀려날 수 있다 */
    private fun cellWidth(): Float =
        rule.onAllNodesWithContentDescription("IMG_", substring = true)[0].fetchSemanticsNode().boundsInRoot.width

    private fun pinchOpen() = rule.onRoot().performTouchInput {
        pinch(
            start0 = center - Offset(0f, 100f),
            end0 = center - Offset(0f, 450f),
            start1 = center + Offset(0f, 100f),
            end1 = center + Offset(0f, 450f),
        )
    }

    private fun pinchClose() = rule.onRoot().performTouchInput {
        pinch(
            start0 = center - Offset(0f, 450f),
            end0 = center - Offset(0f, 100f),
            start1 = center + Offset(0f, 450f),
            end1 = center + Offset(0f, 100f),
        )
    }

    @Test
    fun 벌리면_한_단계_커지고_오므리면_작아진다_보던_사진은_남는다() {
        var step by mutableIntStateOf(2)
        rule.setContent {
            EasyGalleryTheme {
                GalleryGrid(
                    sections = groupByDate(items),
                    selectedIds = emptySet(),
                    onToggleSelection = {},
                    onSelectionChange = {},
                    cellSizeStep = step,
                    onCellSizeStepChange = { step = it },
                    showDateScroller = true,
                )
            }
        }
        val before = cellWidth()
        pinchOpen()
        rule.waitForIdle()
        assertEquals("벌리면 한 단계", 3, step)
        val bigger = cellWidth()
        assertTrue("칸이 커진다: $before → $bigger", bigger > before)
        rule.onNodeWithContentDescription("IMG_1.jpg").assertIsDisplayed()

        // 한 번 더 벌리면 4 — 예전엔 처음 단계(2)를 붙잡고 있어 3 에 머물렀다
        pinchOpen()
        rule.waitForIdle()
        assertEquals("두 번 벌리면 두 단계", 4, step)

        pinchClose()
        rule.waitForIdle()
        pinchClose()
        rule.waitForIdle()
        assertEquals("두 번 오므리면 처음으로", 2, step)
        assertEquals(before, cellWidth(), 1f)
    }

    @Test
    fun 한_손가락_스크롤은_칸_크기를_바꾸지_않는다() {
        var step by mutableIntStateOf(2)
        rule.setContent {
            EasyGalleryTheme {
                GalleryGrid(
                    sections = groupByDate(items),
                    selectedIds = emptySet(),
                    onToggleSelection = {},
                    onSelectionChange = {},
                    cellSizeStep = step,
                    onCellSizeStepChange = { step = it },
                    showDateScroller = true,
                )
            }
        }
        rule.onRoot().performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertEquals(2, step)
    }

    /** 숨긴 사진·휴지통 격자(UX2-13) — 크기 단계가 없으면 핀치해도 그대로 */
    @Test
    fun 고정_크기_격자는_핀치해도_그대로다() {
        rule.setContent {
            EasyGalleryTheme {
                GalleryGrid(
                    sections = groupByDate(items),
                    selectedIds = emptySet(),
                    onToggleSelection = {},
                    onSelectionChange = {},
                )
            }
        }
        val before = cellWidth()
        pinchOpen()
        rule.waitForIdle()
        assertEquals(before, cellWidth(), 1f)
    }
}
