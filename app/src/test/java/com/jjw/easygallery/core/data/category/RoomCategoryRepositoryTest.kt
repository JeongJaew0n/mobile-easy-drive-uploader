package com.jjw.easygallery.core.data.category

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.jjw.easygallery.core.data.upload.db.AppDatabase
import com.jjw.easygallery.core.domain.model.CategoryError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RoomCategoryRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoomCategoryRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomCategoryRepository(db.categoryDao(), UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create trims, rejects empty, too long and duplicate names ignoring case`() = runTest {
        val travel = repository.create("  여행 ", 2).getOrThrow()
        assertEquals("여행", travel.name)
        assertEquals(0, travel.sortOrder)

        assertTrue(repository.create("   ", 0).exceptionOrNull() is CategoryError.EmptyName)
        assertTrue(repository.create("a".repeat(31), 0).exceptionOrNull() is CategoryError.NameTooLong)

        repository.create("Trip", 1).getOrThrow()
        assertTrue(repository.create("trip", 1).exceptionOrNull() is CategoryError.DuplicateName)
        assertTrue(repository.rename(travel.id, "TRIP").exceptionOrNull() is CategoryError.DuplicateName)
        // 자기 자신 이름으로 바꾸는 건 허용
        assertTrue(repository.rename(travel.id, "여행").isSuccess)
    }

    @Test
    fun `categories carry item counts and follow sort order`() = runTest {
        val a = repository.create("A", 0).getOrThrow()
        val b = repository.create("B", 1).getOrThrow()
        repository.assign(listOf(1, 2, 3), add = setOf(a.id), remove = emptySet())
        repository.assign(listOf(3), add = setOf(b.id), remove = emptySet())

        val list = repository.observeCategories().first()
        assertEquals(listOf("A", "B"), list.map { it.name })
        assertEquals(listOf(3, 1), list.map { it.itemCount })

        repository.reorder(listOf(b.id, a.id))
        assertEquals(listOf("B", "A"), repository.observeCategories().first().map { it.name })
    }

    @Test
    fun `assign adds and removes in one call and ignores duplicates`() = runTest {
        val a = repository.create("A", 0).getOrThrow()
        val b = repository.create("B", 1).getOrThrow()
        repository.assign(listOf(1, 2), add = setOf(a.id, b.id), remove = emptySet())
        repository.assign(listOf(1, 2), add = setOf(a.id), remove = setOf(b.id)) // a 는 이미 있음 → 무시

        repository.observeAssignments().test {
            val map = awaitItem()
            assertEquals(setOf(a.id), map[1L])
            assertEquals(setOf(a.id), map[2L])
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting a category cascades to assignments and removeMedia clears rows`() = runTest {
        val a = repository.create("A", 0).getOrThrow()
        val b = repository.create("B", 1).getOrThrow()
        repository.assign(listOf(1, 2), add = setOf(a.id, b.id), remove = emptySet())

        // shareIn(replay = 1) 이라 first() 는 캐시된 이전 값을 줄 수 있다 → 기대 상태가 올 때까지 기다린다
        repository.delete(a.id)
        val afterDelete = repository.observeAssignments().first { it[1L] == setOf(b.id) }
        assertEquals(setOf(b.id), afterDelete[2L])

        repository.removeMedia(listOf(1))
        val afterRemove = repository.observeAssignments().first { it[1L] == null }
        assertNull(afterRemove[1L])
        assertEquals(setOf(b.id), afterRemove[2L])
        assertEquals(1, repository.observeCategories().first { it.single().itemCount == 1 }.single().itemCount)
    }
}
