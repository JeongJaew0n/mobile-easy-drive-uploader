package com.jjw.easygallery.core.data.upload.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UploadedMediaDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: UploadedMediaDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.uploadedMediaDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(mediaId: Long, destination: String, fileId: String = "f$mediaId", accountId: String? = null) =
        UploadedMediaEntity(mediaId, destination, fileId, folderId = null, uploadedAt = mediaId, accountId = accountId)

    @Test
    fun `same photo at the same destination is replaced`() = runTest {
        dao.upsert(row(1, "drive:a@x", fileId = "old"))
        dao.upsert(row(1, "drive:a@x", fileId = "new"))

        assertEquals(1, dao.count())
        assertEquals(listOf("new"), dao.observeRemoteIdsAt("drive:a@x").first())
    }

    @Test
    fun `same photo at two destinations keeps both`() = runTest {
        // 스키마 11 까지는 키가 mediaId 하나라 뒤의 것이 앞의 것을 덮어썼다
        dao.upsert(row(1, "drive:a@x"))
        dao.upsert(row(1, "remote:s3", accountId = "s3"))

        assertEquals(2, dao.count())
        assertEquals(listOf(1L), dao.uploadedAmongAt(listOf(1L), "drive:a@x"))
        assertEquals(listOf(1L), dao.uploadedAmongAt(listOf(1L), "remote:s3"))
    }

    @Test
    fun `google accounts are kept apart`() = runTest {
        dao.upsert(row(1, "drive:a@x"))
        dao.upsert(row(2, "drive:a@x"))

        assertEquals(setOf(1L, 2L), dao.observeUploadedIdsAt("drive:a@x").first().toSet())
        assertEquals(emptyList<Long>(), dao.observeUploadedIdsAt("drive:b@x").first())
        assertEquals(emptyList<Long>(), dao.uploadedAmongAt(listOf(1L, 2L), "drive:b@x"))
    }

    @Test
    fun `any-destination queries return each photo once`() = runTest {
        dao.upsert(row(1, "drive:a@x"))
        dao.upsert(row(1, "remote:s3"))
        dao.upsert(row(2, "drive:b@x"))

        assertEquals(listOf(1L, 2L), dao.uploadedAmong(listOf(1L, 2L, 3L)).sorted())
        assertEquals(listOf(1L, 2L), dao.observeUploadedIds().first().sorted())
    }

    @Test
    fun `claiming moves only unclaimed drive rows`() = runTest {
        dao.upsert(row(1, UNCLAIMED_DRIVE_DESTINATION))
        dao.upsert(row(2, UNCLAIMED_DRIVE_DESTINATION))
        dao.upsert(row(3, "remote:s3"))
        dao.upsert(row(4, "drive:b@x"))

        dao.claimUnclaimed("drive:a@x")

        assertEquals(listOf(1L, 2L), dao.observeUploadedIdsAt("drive:a@x").first().sorted())
        assertEquals(listOf(3L), dao.observeUploadedIdsAt("remote:s3").first())
        assertEquals(listOf(4L), dao.observeUploadedIdsAt("drive:b@x").first())
        assertEquals(emptyList<Long>(), dao.observeUploadedIdsAt(UNCLAIMED_DRIVE_DESTINATION).first())
    }

    @Test
    fun `claiming twice changes nothing the second time`() = runTest {
        dao.upsert(row(1, UNCLAIMED_DRIVE_DESTINATION))
        dao.claimUnclaimed("drive:a@x")
        // 다른 계정이 뒤늦게 불러도 이미 넘어간 것은 가져가지 못한다
        dao.claimUnclaimed("drive:b@x")

        assertEquals(listOf(1L), dao.observeUploadedIdsAt("drive:a@x").first())
        assertEquals(emptyList<Long>(), dao.observeUploadedIdsAt("drive:b@x").first())
    }

    @Test
    fun `claiming over an existing row keeps one`() = runTest {
        dao.upsert(row(1, UNCLAIMED_DRIVE_DESTINATION, fileId = "legacy"))
        dao.upsert(row(1, "drive:a@x", fileId = "fresh"))

        dao.claimUnclaimed("drive:a@x")

        assertEquals(1, dao.count())
    }

    @Test
    fun `clear empties the ledger`() = runTest {
        dao.upsert(row(1, "drive:a@x"))

        dao.clear()

        assertEquals(0, dao.count())
    }
}
