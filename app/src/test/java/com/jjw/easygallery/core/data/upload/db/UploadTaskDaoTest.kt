package com.jjw.easygallery.core.data.upload.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jjw.easygallery.core.domain.model.UploadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UploadTaskDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: UploadTaskDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.uploadTaskDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `nextUnfinished returns oldest pending or running and skips finished`() = runTest {
        dao.insertAll(
            listOf(
                entity(mediaId = 1, createdAt = 10, state = UploadState.COMPLETED),
                entity(mediaId = 2, createdAt = 20, state = UploadState.RUNNING),
                entity(mediaId = 3, createdAt = 30),
                entity(mediaId = 4, createdAt = 5, state = UploadState.FAILED),
            ),
        )

        assertEquals(2L, dao.nextUnfinished()?.mediaId)
        assertEquals(2, dao.countUnfinished())
        assertEquals(listOf(2L, 3L), dao.unfinishedMediaIds().sorted())
    }

    @Test
    fun `state transitions update the row`() = runTest {
        val id = dao.insertAll(listOf(entity(mediaId = 1, createdAt = 1))).single()

        dao.updateState(id, UploadState.RUNNING.name, attemptCount = 1, now = 2)
        dao.updateSession(id, "https://session", now = 3)
        dao.updateProgress(id, 500, now = 4)
        var row = dao.observeAll().first().single()
        assertEquals(UploadState.RUNNING, row.state)
        assertEquals(1, row.attemptCount)
        assertEquals("https://session", row.sessionUri)
        assertEquals(500L, row.bytesUploaded)

        dao.markCompleted(id, "drive-1", now = 5)
        row = dao.observeAll().first().single()
        assertEquals(UploadState.COMPLETED, row.state)
        assertEquals("drive-1", row.driveFileId)
        assertEquals(row.sizeBytes, row.bytesUploaded)
        assertNull(dao.nextUnfinished())
    }

    @Test
    fun `retryFailed resets failed rows and deleteCompleted removes finished`() = runTest {
        dao.insertAll(
            listOf(
                entity(mediaId = 1, createdAt = 1, state = UploadState.FAILED),
                entity(mediaId = 2, createdAt = 2, state = UploadState.COMPLETED),
                entity(mediaId = 3, createdAt = 3),
            ),
        )

        assertEquals(1, dao.retryFailed(now = 9))
        assertEquals(1, dao.deleteCompleted())
        val rows = dao.observeAll().first()
        assertEquals(listOf(1L, 3L), rows.map { it.mediaId })
        assertEquals(setOf(UploadState.PENDING), rows.map { it.state }.toSet())
        assertNull(rows.first().errorMessage)
    }

    @Test
    fun `deleteUnfinished keeps history`() = runTest {
        dao.insertAll(
            listOf(
                entity(mediaId = 1, createdAt = 1, state = UploadState.COMPLETED),
                entity(mediaId = 2, createdAt = 2, state = UploadState.RUNNING),
                entity(mediaId = 3, createdAt = 3),
            ),
        )

        assertEquals(2, dao.deleteUnfinished())
        assertEquals(listOf(1L), dao.observeAll().first().map { it.mediaId })
    }

    private fun entity(mediaId: Long, createdAt: Long, state: UploadState = UploadState.PENDING) = UploadTaskEntity(
        mediaId = mediaId,
        uri = "content://test/$mediaId",
        displayName = "IMG_$mediaId.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_000,
        folderId = "f",
        folderName = "Easy Gallery",
        state = state,
        errorMessage = if (state == UploadState.FAILED) "boom" else null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
