package com.jjw.easygallery.core.data.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.jjw.easygallery.core.data.prefs.UserPreferences
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.upload.db.AppDatabase
import com.jjw.easygallery.core.data.upload.db.UNCLAIMED_DRIVE_DESTINATION
import com.jjw.easygallery.core.data.upload.db.UploadedMediaEntity
import com.jjw.easygallery.core.domain.model.RemoteAccount
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Drive 기록은 지금 연결된 Google 계정 기준" 이 실제로 지켜지는지.
 * 여기가 새면 A 로 올린 뒤 B 로 바꿨을 때 B 에 없는 사진이 "이미 백업됨" 으로 업로드에서 빠진다.
 */
@RunWith(RobolectricTestRunner::class)
class UploadLedgerRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: UploadLedgerRepository
    private val state = MutableStateFlow(UserPreferences(accountEmail = A))

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val prefs: UserPreferencesRepository = mockk {
            every { preferences } returns state
            coEvery { current() } answers { state.value }
        }
        ledger = UploadLedgerRepository(db.uploadedMediaDao(), prefs)
    }

    @After
    fun tearDown() = db.close()

    private fun signInAs(email: String?) {
        state.value = state.value.copy(accountEmail = email)
    }

    @Test
    fun `what A uploaded is not uploaded for B`() = runTest {
        ledger.record(1, "file-1", "folder", accountId = null)

        assertEquals(setOf(1L), ledger.uploadedAmong(listOf(1L), accountId = null))
        signInAs(B)
        assertEquals(emptySet<Long>(), ledger.uploadedAmong(listOf(1L), accountId = null))
    }

    @Test
    fun `switching account re-emits the badge set`() = runTest {
        ledger.record(1, "file-1", "folder")
        ledger.record(2, "file-2", "folder")

        ledger.observeUploadedIds(null).test {
            assertEquals(setOf(1L, 2L), awaitItem())
            signInAs(B)
            assertEquals(emptySet<Long>(), awaitItem())
            signInAs(A)
            assertEquals(setOf(1L, 2L), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `signed out means nothing is known to be on Drive`() = runTest {
        ledger.record(1, "file-1", "folder")
        signInAs(null)

        ledger.observeUploadedIds(null).test {
            assertEquals(emptySet<Long>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(emptySet<Long>(), ledger.uploadedAmong(listOf(1L), null))
    }

    @Test
    fun `the same photo uploaded to A and B is recorded twice`() = runTest {
        ledger.record(1, "a-file", "folder")
        signInAs(B)
        ledger.record(1, "b-file", "folder")

        assertEquals(2, ledger.count())
        assertEquals(setOf(1L), ledger.uploadedAmong(listOf(1L), null))
        signInAs(A)
        assertEquals(setOf(1L), ledger.uploadedAmong(listOf(1L), null))
    }

    @Test
    fun `other storages do not care which Google account is connected`() = runTest {
        ledger.record(1, "s3-key", "bucket/", accountId = "s3")
        signInAs(B)

        assertEquals(setOf(1L), ledger.uploadedAmong(listOf(1L), accountId = "s3"))
        assertEquals(emptySet<Long>(), ledger.uploadedAmong(listOf(1L), accountId = null))
    }

    @Test
    fun `the drive account id is treated as Drive too`() = runTest {
        ledger.record(1, "file-1", "folder", accountId = RemoteAccount.GOOGLE_DRIVE_ID)

        assertEquals(setOf(1L), ledger.uploadedAmong(listOf(1L), accountId = null))
    }

    @Test
    fun `legacy rows go to the first account that shows up`() = runTest {
        db.uploadedMediaDao().upsert(UploadedMediaEntity(1, UNCLAIMED_DRIVE_DESTINATION, "old", null, 1, null))
        db.uploadedMediaDao().upsert(UploadedMediaEntity(2, UNCLAIMED_DRIVE_DESTINATION, "old2", null, 2, null))

        ledger.observeUploadedIds(null).test {
            assertEquals(setOf(1L, 2L), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // 한 번 넘어간 옛 기록은 나중에 연결한 계정이 가져가지 못한다
        signInAs(B)
        assertEquals(emptySet<Long>(), ledger.uploadedAmong(listOf(1L, 2L), null))
    }

    @Test
    fun `remote ids follow the connected account`() = runTest {
        ledger.record(1, "a-file", "folder")
        signInAs(B)
        ledger.record(2, "b-file", "folder")

        ledger.observeRemoteIds(null).test {
            assertEquals(setOf("b-file"), awaitItem())
            signInAs(A)
            assertEquals(setOf("a-file"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val A = "a@example.com"
        const val B = "b@example.com"
    }
}
