package com.jjw.easygallery.core.data.upload.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.jjw.easygallery.core.data.auth.NotSignedInException
import com.jjw.easygallery.core.data.upload.DriveUploadException
import com.jjw.easygallery.core.data.upload.DriveUploader
import com.jjw.easygallery.core.data.upload.SessionStatus
import com.jjw.easygallery.core.data.upload.UploadEvent
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.data.upload.UploadSource
import com.jjw.easygallery.core.data.upload.db.AppDatabase
import com.jjw.easygallery.core.data.upload.db.UploadTaskEntity
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.UploadState
import com.jjw.easygallery.core.domain.usecase.GetUploadFolderUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class UploadWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var queue: UploadQueueRepository
    private lateinit var ledger: UploadLedgerRepository
    private val uploader: DriveUploader = mockk()
    private val getUploadFolder: GetUploadFolderUseCase = mockk()
    private val notifications = UploadNotifications(context)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        queue = UploadQueueRepository(db.uploadTaskDao())
        ledger = UploadLedgerRepository(db.uploadedMediaDao())
        coEvery { uploader.resolveLength(any()) } answers { firstArg<UploadSource>().sizeBytes }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `empty queue succeeds immediately`() = runTest {
        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
    }

    @Test
    fun `pending task without session is uploaded and marked completed`() = runTest {
        insert(mediaId = 1)
        coEvery { uploader.startSession(any(), "f", 1_000) } returns "https://session/1"
        every { uploader.upload(any(), "https://session/1", 0, 1_000) } returns flowOf(
            UploadEvent.Progress(0, 1_000),
            UploadEvent.Progress(1_000, 1_000),
            UploadEvent.Completed("drive-1"),
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val row = rows().single()
        assertEquals(UploadState.COMPLETED, row.state)
        assertEquals("drive-1", row.driveFileId)
        assertEquals("https://session/1", row.sessionUri)
        // 완료는 영구 원장에도 남는다 (자동 백업 중복 방지)
        assertEquals(setOf(1L), ledger.uploadedAmong(listOf(1L, 2L)))
    }

    @Test
    fun `task with existing session resumes from server offset`() = runTest {
        insert(mediaId = 1, sessionUri = "https://session/old")
        coEvery { uploader.queryStatus("https://session/old", 1_000) } returns SessionStatus.Incomplete(400)
        every { uploader.upload(any(), "https://session/old", 400, 1_000) } returns flowOf(
            UploadEvent.Progress(400, 1_000),
            UploadEvent.Completed("drive-2"),
        )

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        assertEquals(UploadState.COMPLETED, rows().single().state)
        coVerify(exactly = 0) { uploader.startSession(any(), any(), any()) }
    }

    @Test
    fun `expired session starts a new one`() = runTest {
        insert(mediaId = 1, sessionUri = "https://session/old")
        coEvery { uploader.queryStatus("https://session/old", 1_000) } returns SessionStatus.Expired
        coEvery { uploader.startSession(any(), "f", 1_000) } returns "https://session/new"
        every { uploader.upload(any(), "https://session/new", 0, 1_000) } returns flowOf(UploadEvent.Completed("d"))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        assertEquals("https://session/new", rows().single().sessionUri)
        assertEquals(UploadState.COMPLETED, rows().single().state)
    }

    @Test
    fun `already complete session is marked completed without upload`() = runTest {
        insert(mediaId = 1, sessionUri = "https://session/old")
        coEvery { uploader.queryStatus("https://session/old", 1_000) } returns SessionStatus.Complete("done")

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        assertEquals("done", rows().single().driveFileId)
        coVerify(exactly = 0) { uploader.upload(any(), any(), any(), any()) }
    }

    @Test
    fun `network failure returns retry and keeps task pending with attempt count`() = runTest {
        insert(mediaId = 1)
        coEvery { uploader.startSession(any(), any(), any()) } throws IOException("offline")

        assertEquals(ListenableWorker.Result.retry(), buildWorker().doWork())

        val row = rows().single()
        assertEquals(UploadState.PENDING, row.state)
        assertEquals(1, row.attemptCount)
    }

    @Test
    fun `transient failure gives up after max attempts`() = runTest {
        insert(mediaId = 1, attemptCount = UploadWorker.MAX_ATTEMPTS - 1)
        coEvery { uploader.startSession(any(), any(), any()) } throws IOException("offline")

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        assertEquals(UploadState.FAILED, rows().single().state)
    }

    @Test
    fun `4xx from Drive fails the task and continues with next`() = runTest {
        insert(mediaId = 1)
        insert(mediaId = 2)
        coEvery { uploader.startSession(match { it.mediaId == 1L }, any(), any()) } throws
            DriveUploadException("forbidden", 403)
        coEvery { uploader.startSession(match { it.mediaId == 2L }, any(), any()) } returns "https://s/2"
        every { uploader.upload(any(), "https://s/2", 0, 1_000) } returns flowOf(UploadEvent.Completed("d2"))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        val byMedia = rows().associateBy { it.mediaId }
        assertEquals(UploadState.FAILED, byMedia[1L]?.state)
        assertEquals("forbidden", byMedia[1L]?.errorMessage)
        assertEquals(UploadState.COMPLETED, byMedia[2L]?.state)
    }

    @Test
    fun `auth failure stops the worker and leaves task pending`() = runTest {
        insert(mediaId = 1)
        coEvery { uploader.startSession(any(), any(), any()) } throws NotSignedInException()

        assertEquals(ListenableWorker.Result.failure(), buildWorker().doWork())

        assertEquals(UploadState.PENDING, rows().single().state)
    }

    @Test
    fun `missing folder is resolved and persisted before upload`() = runTest {
        insert(mediaId = 1, folderId = null)
        coEvery { getUploadFolder() } returns DriveFolder("resolved", "Easy Gallery")
        coEvery { uploader.startSession(any(), "resolved", 1_000) } returns "https://s/1"
        every { uploader.upload(any(), "https://s/1", 0, 1_000) } returns flow { emit(UploadEvent.Completed("d")) }

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        assertEquals("resolved", rows().single().folderId)
    }

    private fun buildWorker(): UploadWorker =
        TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = UploadWorker(
                        appContext,
                        workerParameters,
                        queue,
                        ledger,
                        uploader,
                        getUploadFolder,
                        notifications,
                    )
                },
            )
            .build()

    private suspend fun insert(
        mediaId: Long,
        sessionUri: String? = null,
        attemptCount: Int = 0,
        folderId: String? = "f",
    ) {
        db.uploadTaskDao().insertAll(
            listOf(
                UploadTaskEntity(
                    mediaId = mediaId,
                    uri = "content://test/$mediaId",
                    displayName = "IMG_$mediaId.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 1_000,
                    folderId = folderId,
                    folderName = folderId?.let { "Easy Gallery" },
                    sessionUri = sessionUri,
                    attemptCount = attemptCount,
                    createdAt = mediaId,
                    updatedAt = mediaId,
                ),
            ),
        )
    }

    private suspend fun rows() = db.uploadTaskDao().observeAll().first()
}
