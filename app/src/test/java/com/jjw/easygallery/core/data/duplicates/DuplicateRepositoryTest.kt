package com.jjw.easygallery.core.data.duplicates

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.data.upload.db.AppDatabase
import com.jjw.easygallery.core.data.upload.db.MediaHashEntity
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class DuplicateRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repository: DuplicateRepository
    private val items = MutableStateFlow<List<MediaItem>>(emptyList())
    private val media: MediaRepository = mockk { every { observeMedia(any()) } returns items }
    private val ledger: UploadLedgerRepository = mockk {
        every { observeUploadedIds() } returns MutableStateFlow(emptySet())
    }

    private val bytesA = ByteArray(3_000) { (it % 7).toByte() }
    private val bytesB = ByteArray(3_000) { (it % 11).toByte() }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val hasher = MediaHasher(context, UnconfinedTestDispatcher())
        repository = DuplicateRepository(media, db.mediaHashDao(), hasher, ledger)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `only size collisions are hashed and identical bytes group together`() = runTest {
        // 1·2 는 같은 크기·같은 내용, 3 은 같은 크기·다른 내용, 4 는 크기가 유일(해시 안 함)
        register(1, bytesA)
        register(2, bytesA)
        register(3, bytesB)
        register(4, ByteArray(10))
        items.value = listOf(item(1, 3_000), item(2, 3_000), item(3, 3_000), item(4, 10))

        val result = repository.scan()

        assertEquals(DuplicateRepository.ScanResult(candidates = 3, hashed = 3, failed = 0), result)
        val groups = repository.observeGroups().first()
        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L), groups[0].items.map { it.id })
        assertEquals(3, db.mediaHashDao().getAll().size)
    }

    @Test
    fun `cached hashes are reused unless size or modified time changed`() = runTest {
        register(1, bytesA)
        register(2, bytesA)
        items.value = listOf(item(1, 3_000), item(2, 3_000))
        repository.scan()

        // 그대로 다시 검사 → 해시 0건
        assertEquals(0, repository.scan().hashed)

        // 2번 파일이 수정됨 → 그것만 다시 해시
        items.value = listOf(item(1, 3_000), item(2, 3_000, modified = 99))
        assertEquals(1, repository.scan().hashed)
    }

    @Test
    fun `hashes of deleted items are pruned`() = runTest {
        db.mediaHashDao().upsert(MediaHashEntity(42, 1, 1, "gone", 0))
        register(1, bytesA)
        register(2, bytesA)
        items.value = listOf(item(1, 3_000), item(2, 3_000))

        repository.scan()

        assertEquals(listOf(1L, 2L), db.mediaHashDao().getAll().map { it.mediaId }.sorted())
    }

    private fun register(id: Long, bytes: ByteArray) {
        shadowOf(context.contentResolver).registerInputStream(uri(id), ByteArrayInputStream(bytes))
    }

    private fun uri(id: Long): Uri = Uri.parse("content://com.jjw.easygallery.test/media/$id")

    private fun item(id: Long, size: Long, modified: Long = 1) = MediaItem(
        id = id,
        uri = uri(id),
        displayName = "IMG_$id.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = size,
        dateTakenMillis = id * 1_000,
        dateAddedSeconds = id,
        dateModifiedSeconds = modified,
        bucketId = 1,
        bucketName = "Camera",
    )
}
