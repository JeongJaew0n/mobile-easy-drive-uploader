package com.jjw.easygallery.core.domain.usecase

import android.net.Uri
import com.jjw.easygallery.core.data.auth.NotSignedInException
import com.jjw.easygallery.core.data.prefs.UserPreferences
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.data.upload.work.UploadScheduler
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnqueueUploadsUseCaseTest {

    private val prefs: UserPreferencesRepository = mockk()
    private val queue: UploadQueueRepository = mockk(relaxed = true)
    private val scheduler: UploadScheduler = mockk(relaxed = true)
    private val useCase = EnqueueUploadsUseCase(prefs, queue, scheduler)
    private val items = listOf(item(1), item(2))

    private val s3Default = UserPreferences(
        accountEmail = "a@b.com",
        uploadFolderId = "photos/2026/",
        uploadFolderName = "2026",
        uploadAccountId = "s3",
    )

    @Test
    fun `default target uses the configured account and folder`() = runTest {
        coEvery { prefs.current() } returns s3Default
        coEvery { queue.enqueue(items, DriveFolder("photos/2026/", "2026"), "s3") } returns 2

        assertEquals(2, useCase(items))

        coVerify { queue.deleteCompleted() }
        coVerify { scheduler.schedule() }
    }

    @Test
    fun `explicit account keeps the folder only when it matches the default target`() = runTest {
        coEvery { prefs.current() } returns s3Default
        coEvery { queue.enqueue(any(), any(), any()) } returns 2

        useCase.toAccount(items, "s3")
        coVerify { queue.enqueue(items, DriveFolder("photos/2026/", "2026"), "s3") }

        useCase.toAccount(items, "nas")
        coVerify { queue.enqueue(items, null, "nas") } // 다른 계정은 루트

        useCase.toAccount(items, null)
        coVerify { queue.enqueue(items, null, null) } // Drive 는 워커가 앱 폴더를 만든다
    }

    @Test
    fun `drive target without sign-in is rejected, other accounts are not`() = runTest {
        coEvery { prefs.current() } returns UserPreferences(accountEmail = null, uploadAccountId = null)

        val error = runCatching { useCase(items) }.exceptionOrNull()
        assertTrue(error is NotSignedInException)

        coEvery { queue.enqueue(any(), any(), any()) } returns 0
        useCase.toAccount(items, "nas") // 로그인 없이도 가능
        coVerify(exactly = 0) { scheduler.schedule() } // 추가 0 이면 예약 안 함
    }

    private fun item(id: Long) = MediaItem(
        id = id,
        uri = mockk<Uri>(relaxed = true),
        displayName = "IMG_$id.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 1,
        dateTakenMillis = id * 1_000,
        dateAddedSeconds = id,
        bucketId = 1,
        bucketName = "Camera",
        relativePath = "DCIM/Camera/",
    )
}
