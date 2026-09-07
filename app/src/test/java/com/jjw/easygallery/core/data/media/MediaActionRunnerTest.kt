package com.jjw.easygallery.core.data.media

import android.content.IntentSender
import android.net.Uri
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaActionRunnerTest {

    private val repository: MediaRepository = mockk()
    private val runner = MediaActionRunner(repository)
    private val sender: IntentSender = mockk()
    private val item = MediaItem(
        id = 1,
        uri = mockk<Uri>(relaxed = true),
        displayName = "IMG_1.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 10,
        dateTakenMillis = 0,
        bucketId = 1,
        bucketName = "Camera",
        relativePath = "DCIM/Camera/",
    )

    @Test
    fun `delete on API30 needs consent then completes without rerun`() = runTest {
        every { repository.mutationsCompleteOnConsent } returns true
        coEvery { repository.requestDelete(listOf(item)) } returns MediaMutation.NeedsConsent(sender)
        val action = MediaAction.Delete(listOf(item))

        val first = runner.run(action)
        assertEquals(ActionOutcome.NeedsConsent(sender, action), first)

        val after = runner.afterConsent(action)
        assertEquals(ActionOutcome.Done(action, 1), after)
        coVerify(exactly = 1) { repository.requestDelete(any()) }
    }

    @Test
    fun `delete on API29 reruns after consent`() = runTest {
        every { repository.mutationsCompleteOnConsent } returns false
        coEvery { repository.requestDelete(listOf(item)) } returnsMany listOf(
            MediaMutation.NeedsConsent(sender),
            MediaMutation.Done(1),
        )
        val action = MediaAction.Delete(listOf(item))

        assertTrue(runner.run(action) is ActionOutcome.NeedsConsent)
        assertEquals(ActionOutcome.Done(action, 1), runner.afterConsent(action))
        coVerify(exactly = 2) { repository.requestDelete(any()) }
    }

    @Test
    fun `rename asks write consent first then updates`() = runTest {
        coEvery { repository.requestWrite(listOf(item)) } returns MediaMutation.NeedsConsent(sender)
        coEvery { repository.rename(item, "new.jpg") } returns MediaMutation.Done(1)
        val action = MediaAction.Rename(item, "new.jpg")

        val first = runner.run(action)
        assertTrue(first is ActionOutcome.NeedsConsent)
        coVerify(exactly = 0) { repository.rename(any(), any()) }

        val after = runner.afterConsent(action)
        assertEquals(ActionOutcome.Done(action.copy(writeGranted = true), 1), after)
        coVerify(exactly = 1) { repository.requestWrite(any()) }
        coVerify(exactly = 1) { repository.rename(item, "new.jpg") }
    }

    @Test
    fun `move without pending consent updates directly`() = runTest {
        coEvery { repository.requestWrite(listOf(item)) } returns MediaMutation.Done(0)
        coEvery { repository.move(listOf(item), "Pictures/Trip/") } returns MediaMutation.Done(1)
        val action = MediaAction.Move(listOf(item), "Pictures/Trip/")

        assertEquals(ActionOutcome.Done(action, 1), runner.run(action))
    }

    @Test
    fun `favorite and trash delegate with flags`() = runTest {
        coEvery { repository.requestFavorite(listOf(item), true) } returns MediaMutation.NeedsConsent(sender)
        coEvery { repository.requestTrash(listOf(item), false) } returns MediaMutation.NeedsConsent(sender)

        assertTrue(runner.run(MediaAction.Favorite(listOf(item), favorite = true)) is ActionOutcome.NeedsConsent)
        assertTrue(runner.run(MediaAction.Trash(listOf(item), trashed = false)) is ActionOutcome.NeedsConsent)
    }
}
