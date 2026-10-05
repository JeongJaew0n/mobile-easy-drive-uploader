package com.jjw.easygallery.core.data.media

import android.content.IntentSender
import android.net.Uri
import app.cash.turbine.test
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 큰 묶음을 나눠 처리하는지(`docs/troubleshootings/reusable/mediastore-2000-uri-limit.md`).
 * API 36 타깃은 MediaStore 요청 하나에 URI 2,000개까지 — 넘기면 IllegalArgumentException 이었다.
 */
class MediaActionControllerTest {

    private val uri = mockk<Uri>(relaxed = true)
    private val sender = mockk<IntentSender>(relaxed = true)

    // API 30+ 처럼: 조각마다 동의가 필요하고, 동의하면 그 조각이 끝난다
    // (mockk 블록 안에서 run 을 쓰면 표준 라이브러리 run 으로 잡혀 밖에서 묶는다)
    private val runner: MediaActionRunner = mockk<MediaActionRunner>().also { r ->
        coEvery { r.run(any()) } answers { ActionOutcome.NeedsConsent(sender, firstArg()) }
        coEvery { r.afterConsent(any()) } answers {
            val action = firstArg<MediaAction>()
            ActionOutcome.Done(action, action.items.size)
        }
    }
    private val items = (1L..4_500L).map { item(it) }

    @Test
    fun `2000개씩 나눠 차례로 동의받고 완료는 한 번만`() = runTest {
        val controller = MediaActionController(runner)
        controller.events.test {
            controller.perform(this@runTest, MediaAction.Trash(items, trashed = true))
            repeat(3) {
                advanceUntilIdle()
                assertTrue(awaitItem() is MediaActionEvent.LaunchConsent)
                controller.onConsentResult(this@runTest, granted = true)
            }
            advanceUntilIdle()
            val done = awaitItem() as MediaActionEvent.Done
            assertEquals(4_500, done.affected)
            assertEquals(4_500, done.action.items.size)
        }
        coVerify(exactly = 1) { runner.run(MediaAction.Trash(items.subList(0, 2_000), trashed = true)) }
        coVerify(exactly = 1) { runner.run(MediaAction.Trash(items.subList(2_000, 4_000), trashed = true)) }
        coVerify(exactly = 1) { runner.run(MediaAction.Trash(items.subList(4_000, 4_500), trashed = true)) }
    }

    @Test
    fun `중간에 취소하면 그때까지 처리한 만큼만 완료로 알린다`() = runTest {
        val controller = MediaActionController(runner)
        controller.events.test {
            controller.perform(this@runTest, MediaAction.Delete(items))
            advanceUntilIdle()
            awaitItem() // 첫 조각 동의
            controller.onConsentResult(this@runTest, granted = true)
            advanceUntilIdle()
            awaitItem() // 둘째 조각 동의
            controller.onConsentResult(this@runTest, granted = false)
            advanceUntilIdle()
            val done = awaitItem() as MediaActionEvent.Done
            assertEquals(2_000, done.affected)
            assertEquals(items.subList(0, 2_000), done.action.items)
        }
        assertEquals(false, MediaActionController(runner).isMutating.value)
    }

    @Test
    fun `첫 조각부터 취소하면 취소`() = runTest {
        val controller = MediaActionController(runner)
        controller.events.test {
            controller.perform(this@runTest, MediaAction.Delete(items.take(10)))
            advanceUntilIdle()
            awaitItem()
            controller.onConsentResult(this@runTest, granted = false)
            advanceUntilIdle()
            assertEquals(MediaActionEvent.Cancelled, awaitItem())
        }
        assertEquals(false, controller.isMutating.value)
    }

    @Test
    fun `2000개 이하는 나누지 않는다`() {
        assertEquals(1, MediaAction.Trash(items.take(2_000), trashed = true).chunked().size)
        assertEquals(3, MediaAction.Favorite(items, favorite = true).chunked().size)
    }

    private fun item(id: Long) = MediaItem(
        id = id,
        uri = uri,
        displayName = "IMG_$id.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 1,
        dateTakenMillis = id,
        bucketId = 1,
        bucketName = "Camera",
        relativePath = "DCIM/Camera/",
    )
}
