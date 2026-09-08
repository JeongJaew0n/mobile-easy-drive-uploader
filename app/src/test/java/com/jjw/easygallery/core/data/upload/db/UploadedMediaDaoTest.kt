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

    @Test
    fun `upsert replaces by mediaId and uploadedAmong filters`() = runTest {
        dao.upsert(UploadedMediaEntity(1, "d1", "f", 10))
        dao.upsert(UploadedMediaEntity(2, "d2", "f", 20))
        dao.upsert(UploadedMediaEntity(1, "d1-new", "f", 30))

        assertEquals(2, dao.count())
        assertEquals(listOf(1L, 2L), dao.uploadedAmong(listOf(1L, 2L, 3L)).sorted())
        assertEquals(setOf(1L, 2L), dao.observeUploadedIds().first().toSet())
    }

    @Test
    fun `clear empties the ledger`() = runTest {
        dao.upsert(UploadedMediaEntity(1, "d1", null, 10))

        dao.clear()

        assertEquals(0, dao.count())
    }
}
