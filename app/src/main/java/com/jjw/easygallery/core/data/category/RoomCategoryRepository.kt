package com.jjw.easygallery.core.data.category

import com.jjw.easygallery.core.common.di.AppDispatcher
import com.jjw.easygallery.core.common.di.Dispatcher
import com.jjw.easygallery.core.data.upload.db.CategoryDao
import com.jjw.easygallery.core.data.upload.db.CategoryEntity
import com.jjw.easygallery.core.data.upload.db.CategoryWithCount
import com.jjw.easygallery.core.domain.model.CATEGORY_COLOR_COUNT
import com.jjw.easygallery.core.domain.model.Category
import com.jjw.easygallery.core.domain.model.CategoryAssignments
import com.jjw.easygallery.core.domain.model.CategoryError
import com.jjw.easygallery.core.domain.model.normalizeCategoryName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomCategoryRepository @Inject constructor(
    private val dao: CategoryDao,
    @Dispatcher(AppDispatcher.IO) ioDispatcher: CoroutineDispatcher,
) : CategoryRepository {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val categories: Flow<List<Category>> = dao.observeWithCounts()
        .map { rows -> rows.map(CategoryWithCount::toDomain) }
        .distinctUntilChanged()

    private val assignments: Flow<CategoryAssignments> = dao.observeAssignments()
        .map { refs ->
            val map = HashMap<Long, MutableSet<Long>>()
            refs.forEach { map.getOrPut(it.mediaId) { HashSet() }.add(it.categoryId) }
            map as CategoryAssignments
        }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_STOP_TIMEOUT_MILLIS), replay = 1)

    override fun observeCategories(): Flow<List<Category>> = categories

    override fun observeAssignments(): Flow<CategoryAssignments> = assignments

    override suspend fun create(name: String, colorIndex: Int): Result<Category> {
        val normalized = normalizeCategoryName(name).getOrElse { return Result.failure(it) }
        val lower = normalized.lowercase(Locale.ROOT)
        if (dao.findIdByNameLower(lower) != null) return Result.failure(CategoryError.DuplicateName())
        val entity = CategoryEntity(
            name = normalized,
            nameLower = lower,
            colorIndex = colorIndex.coerceIn(0, CATEGORY_COLOR_COUNT - 1),
            sortOrder = dao.nextSortOrder(),
            createdAt = System.currentTimeMillis(),
        )
        val id = dao.insert(entity)
        return Result.success(Category(id, entity.name, entity.colorIndex, entity.sortOrder))
    }

    override suspend fun rename(id: Long, name: String): Result<Unit> {
        val normalized = normalizeCategoryName(name).getOrElse { return Result.failure(it) }
        val lower = normalized.lowercase(Locale.ROOT)
        if (dao.findIdByNameLower(lower, exceptId = id) != null) return Result.failure(CategoryError.DuplicateName())
        dao.rename(id, normalized, lower)
        return Result.success(Unit)
    }

    override suspend fun recolor(id: Long, colorIndex: Int) =
        dao.recolor(id, colorIndex.coerceIn(0, CATEGORY_COLOR_COUNT - 1))

    override suspend fun reorder(orderedIds: List<Long>) = dao.reorder(orderedIds)

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun assign(mediaIds: Collection<Long>, add: Set<Long>, remove: Set<Long>) {
        if (mediaIds.isEmpty() || (add.isEmpty() && remove.isEmpty())) return
        dao.assign(mediaIds.toList(), add, remove, System.currentTimeMillis())
    }

    override suspend fun removeMedia(mediaIds: Collection<Long>) {
        mediaIds.chunked(CategoryDao.QUERY_CHUNK).forEach { dao.deleteForMedia(it) }
    }

    private companion object {
        const val SHARE_STOP_TIMEOUT_MILLIS = 5_000L
    }
}

private fun CategoryWithCount.toDomain(): Category = Category(
    id = category.id,
    name = category.name,
    colorIndex = category.colorIndex,
    sortOrder = category.sortOrder,
    itemCount = itemCount,
)
