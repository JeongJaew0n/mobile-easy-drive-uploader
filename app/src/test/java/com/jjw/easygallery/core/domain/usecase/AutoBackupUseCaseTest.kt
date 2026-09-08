package com.jjw.easygallery.core.domain.usecase

import android.net.Uri
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.prefs.UserPreferences
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.data.upload.work.UploadScheduler
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoBackupUseCaseTest {

    private val prefs: UserPreferencesRepository = mockk(relaxed = true)
    private val media: MediaRepository = mockk()
    private val ledger: UploadLedgerRepository = mockk()
    private val queue: UploadQueueRepository = mockk(relaxed = true)
    private val scheduler: UploadScheduler = mockk(relaxed = true)
    private val useCase = AutoBackupUseCase(prefs, media, ledger, queue, scheduler, clock = { 1_000_000_000L })

    private val enabledPrefs = UserPreferences(
        accountEmail = "a@b.com",
        autoBackupEnabled = true,
        autoBackupPaths = setOf("DCIM/Camera/"),
        autoBackupSinceSeconds = 500,
    )

    @Test
    fun `does nothing when disabled, signed out or no albums`() = runTest {
        coEvery { prefs.current() } returnsMany listOf(
            enabledPrefs.copy(autoBackupEnabled = false),
            enabledPrefs.copy(accountEmail = null),
            enabledPrefs.copy(autoBackupPaths = emptySet()),
        )

        repeat(3) { assertEquals(AutoBackupUseCase.Result.Skipped, useCase.scanAndEnqueue()) }
        coVerify(exactly = 0) { media.queryAddedSince(any(), any(), any()) }
    }

    @Test
    fun `enqueues only items that are neither uploaded nor queued and advances the watermark`() = runTest {
        coEvery { prefs.current() } returns enabledPrefs
        val items = listOf(item(1, added = 600), item(2, added = 700), item(3, added = 800), item(4, added = 900))
        coEvery { media.queryAddedSince(500, setOf("DCIM/Camera/"), true) } returns items
        coEvery { ledger.uploadedAmong(any(), any()) } returns setOf(1L)
        coEvery { queue.queuedAmong(any()) } returns setOf(2L)
        val enqueued = slot<List<MediaItem>>()
        coEvery { queue.enqueue(capture(enqueued), any()) } returns 2

        val result = useCase.scanAndEnqueue()

        assertEquals(AutoBackupUseCase.Result(scanned = 4, enqueued = 2, skipped = 2), result)
        assertEquals(listOf(3L, 4L), enqueued.captured.map { it.id })
        coVerify { scheduler.schedule() }
        coVerify { prefs.markAutoBackupRun(sinceSeconds = 900, nowMillis = 1_000_000_000L) }
    }

    @Test
    fun `nothing new keeps the watermark and does not schedule`() = runTest {
        coEvery { prefs.current() } returns enabledPrefs
        coEvery { media.queryAddedSince(500, any(), true) } returns emptyList()

        val result = useCase.scanAndEnqueue()

        assertEquals(AutoBackupUseCase.Result(0, 0, 0), result)
        coVerify(exactly = 0) { scheduler.schedule() }
        coVerify { prefs.markAutoBackupRun(sinceSeconds = 500, nowMillis = any()) }
    }

    @Test
    fun `first run without watermark starts from now`() = runTest {
        coEvery { prefs.current() } returns enabledPrefs.copy(autoBackupSinceSeconds = 0)
        coEvery { media.queryAddedSince(1_000_000L, any(), true) } returns emptyList()

        useCase.scanAndEnqueue()

        coVerify { media.queryAddedSince(1_000_000L, setOf("DCIM/Camera/"), true) }
    }

    @Test
    fun `backfill scans from zero and honours the video toggle`() = runTest {
        coEvery { prefs.current() } returns enabledPrefs.copy(autoBackupIncludeVideos = false)
        coEvery { media.queryAddedSince(0, setOf("DCIM/Camera/"), false) } returns listOf(item(1, 10), item(2, 20))
        coEvery { ledger.uploadedAmong(any(), any()) } returns emptySet()
        coEvery { queue.queuedAmong(any()) } returns emptySet()
        coEvery { queue.enqueue(any(), any()) } returns 2

        assertEquals(2, useCase.pendingBackfillCount())
        assertEquals(AutoBackupUseCase.Result(2, 2, 0), useCase.backfill())
    }

    private fun item(id: Long, added: Long) = MediaItem(
        id = id,
        uri = mockk<Uri>(relaxed = true),
        displayName = "IMG_$id.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 1,
        dateTakenMillis = added * 1_000,
        dateAddedSeconds = added,
        bucketId = 1,
        bucketName = "Camera",
        relativePath = "DCIM/Camera/",
    )
}
