package com.jjw.easygallery.core.data.upload.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.jjw.easygallery.core.data.auth.NotSignedInException
import com.jjw.easygallery.core.data.prefs.UserPreferences
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.remote.RemoteStorage
import com.jjw.easygallery.core.data.remote.RemoteStorageException
import com.jjw.easygallery.core.data.remote.RemoteUploader
import com.jjw.easygallery.core.data.remote.StorageRegistry
import com.jjw.easygallery.core.data.upload.DriveUploadException
import com.jjw.easygallery.core.data.upload.SessionStatus
import com.jjw.easygallery.core.data.upload.UploadEvent
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.data.upload.UploadSource
import com.jjw.easygallery.core.data.upload.VideoCompressor
import com.jjw.easygallery.core.data.upload.db.AppDatabase
import com.jjw.easygallery.core.data.upload.db.UploadTaskEntity
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.UploadState
import com.jjw.easygallery.core.domain.model.VideoCompression
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
    private val uploader: RemoteUploader = mockk()
    private val storage: RemoteStorage = mockk {
        every { uploader() } returns uploader
        every { rootId } returns "root"
    }
    private val storages: StorageRegistry = mockk { coEvery { storage(any()) } returns storage }
    private val getUploadFolder: GetUploadFolderUseCase = mockk()
    private val notifications = UploadNotifications(context)
    private val compressor: VideoCompressor = mockk(relaxed = true) {
        coEvery { compress(any(), any(), any()) } returns null
    }
    private val prefs: UserPreferencesRepository = mockk {
        coEvery { current() } returns UserPreferences(
            accountEmail = "me@example.com",
            videoCompression = VideoCompression.HD_720,
        )
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        queue = UploadQueueRepository(db.uploadTaskDao())
        ledger = UploadLedgerRepository(db.uploadedMediaDao(), prefs)
        coEvery { uploader.resolveLength(any()) } answers { firstArg<UploadSource>().sizeBytes }
        // 기본은 "한 번에 올리기를 지원하지 않음" — 각 테스트가 재개 경로를 그대로 검증한다
        coEvery { uploader.uploadWhole(any(), any(), any()) } returns null
        coEvery { uploader.findUploaded(any(), any()) } returns null
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
    fun `compressed video replaces the source and the cache is cleaned after upload`() = runTest {
        insert(mediaId = 1)
        val compressed = UploadSource(
            mediaId = 1,
            uri = android.net.Uri.parse("file:///cache/1_720.mp4"),
            displayName = "VID_1.mp4",
            mimeType = "video/mp4",
            sizeBytes = 400,
        )
        coEvery { compressor.compress(match { it.mediaId == 1L }, VideoCompression.HD_720, any()) } returns compressed
        coEvery { uploader.resolveLength(compressed) } returns 400
        coEvery { uploader.startSession(compressed, "f", 400) } returns "https://s/c"
        every { uploader.upload(compressed, "https://s/c", 0, 400) } returns flowOf(UploadEvent.Completed("d"))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        assertEquals(UploadState.COMPLETED, rows().single().state)
        io.mockk.verify { compressor.cleanup(compressed) }
    }

    @Test
    fun `compression failure fails the task permanently`() = runTest {
        insert(mediaId = 1)
        coEvery { compressor.compress(any(), any(), any()) } throws
            com.jjw.easygallery.core.data.upload.CompressionException("encoder")

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        val row = rows().single()
        assertEquals(UploadState.FAILED, row.state)
        assertEquals("encoder", row.errorMessage)
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
                        storages,
                        getUploadFolder,
                        notifications,
                        compressor,
                        prefs,
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

    @Test
    fun `작은 파일은 세션 없이 한 번에 올린다`() = runTest {
        insert(mediaId = 1)
        coEvery { uploader.uploadWhole(any(), "f", 1_000) } returns "drive-whole"

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // 세션을 만들지 않았어야 한다 — 그것이 이 경로의 값어치다
        coVerify(exactly = 0) { uploader.startSession(any(), any(), any()) }
        assertEquals(UploadState.COMPLETED, rows().single().state)
        assertEquals(setOf(1L), ledger.uploadedAmong(listOf(1L)))
    }

    @Test
    fun `한 번에 올리기를 지원하지 않으면 세션 경로로 간다`() = runTest {
        insert(mediaId = 1)
        coEvery { uploader.uploadWhole(any(), any(), any()) } returns null
        coEvery { uploader.findUploaded(any(), any()) } returns null
        coEvery { uploader.startSession(any(), "f", 1_000) } returns "https://session/1"
        every { uploader.upload(any(), "https://session/1", 0, 1_000) } returns flowOf(
            UploadEvent.Completed("drive-1"),
        )

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
        coVerify { uploader.startSession(any(), "f", 1_000) }
    }

    @Test
    fun `한도 초과는 영구 실패가 아니라 재시도다`() = runTest {
        insert(mediaId = 1)
        coEvery { uploader.uploadWhole(any(), any(), any()) } throws
            RemoteStorageException("업로드 실패 (403): User rate limit exceeded.", 403)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        // FAILED 로 떨어지면 사용자가 손으로 다시 걸어야 한다
        assertEquals(UploadState.PENDING, rows().single().state)
    }

    @Test
    fun `되살아난 항목이 이미 서버에 있으면 다시 올리지 않는다`() = runTest {
        // 앱이 죽어 RUNNING 이 PENDING 으로 돌아온 상황. 한 번에 올리는 경로는 재개가 없어
        // 그냥 올리면 같은 파일이 두 벌 된다
        insert(mediaId = 1, attemptCount = 1)
        coEvery { uploader.findUploaded(1, "f") } returns "already-there"

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        coVerify(exactly = 0) { uploader.uploadWhole(any(), any(), any()) }
        coVerify(exactly = 0) { uploader.startSession(any(), any(), any()) }
        val row = rows().single()
        assertEquals(UploadState.COMPLETED, row.state)
        assertEquals("already-there", row.driveFileId)
    }

    @Test
    fun `처음 올리는 항목은 서버에 묻지 않는다`() = runTest {
        insert(mediaId = 1)
        coEvery { uploader.uploadWhole(any(), "f", 1_000) } returns "drive-1"

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        // 평소 업로드에 왕복을 더하면 빨라진 의미가 없다
        coVerify(exactly = 0) { uploader.findUploaded(any(), any()) }
    }
}
