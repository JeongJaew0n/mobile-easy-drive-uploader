package com.jjw.easygallery.core.data.upload.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class CategoryWithCount(
    @Embedded val category: CategoryEntity,
    val itemCount: Int,
)

data class MediaCategoryRef(
    val mediaId: Long,
    val categoryId: Long,
)

@Dao
abstract class CategoryDao {

    @Query(
        """
        SELECT c.*, COUNT(mc.mediaId) AS itemCount FROM category c
        LEFT JOIN media_category mc ON mc.categoryId = c.id
        GROUP BY c.id ORDER BY c.sortOrder, c.id
        """,
    )
    abstract fun observeWithCounts(): Flow<List<CategoryWithCount>>

    @Query("SELECT mediaId, categoryId FROM media_category")
    abstract fun observeAssignments(): Flow<List<MediaCategoryRef>>

    @Query("SELECT * FROM category WHERE id = :id")
    abstract suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT id FROM category WHERE nameLower = :nameLower AND id != :exceptId LIMIT 1")
    abstract suspend fun findIdByNameLower(nameLower: String, exceptId: Long = 0): Long?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM category")
    abstract suspend fun nextSortOrder(): Int

    @Insert
    abstract suspend fun insert(entity: CategoryEntity): Long

    @Query("UPDATE category SET name = :name, nameLower = :nameLower WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String, nameLower: String)

    @Query("UPDATE category SET colorIndex = :colorIndex WHERE id = :id")
    abstract suspend fun recolor(id: Long, colorIndex: Int)

    @Query("UPDATE category SET sortOrder = :sortOrder WHERE id = :id")
    abstract suspend fun setSortOrder(id: Long, sortOrder: Int)

    /** 관리 화면 정렬 결과를 순서대로 0..n-1 로 다시 매긴다 */
    @Transaction
    open suspend fun reorder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> setSortOrder(id, index) }
    }

    @Query("DELETE FROM category WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAssignments(rows: List<MediaCategoryEntity>)

    @Query("DELETE FROM media_category WHERE categoryId = :categoryId AND mediaId IN (:mediaIds)")
    abstract suspend fun deleteAssignments(categoryId: Long, mediaIds: List<Long>)

    @Query("DELETE FROM media_category WHERE mediaId IN (:mediaIds)")
    abstract suspend fun deleteForMedia(mediaIds: List<Long>)

    /** 여러 항목에 [add] 를 붙이고 [remove] 를 떼는 것을 한 트랜잭션으로. IN 절은 SQLite 변수 한도 아래로 잘라 부른다 */
    @Transaction
    open suspend fun assign(mediaIds: List<Long>, add: Set<Long>, remove: Set<Long>, now: Long) {
        if (add.isNotEmpty()) {
            val rows = mediaIds.flatMap { mediaId -> add.map { MediaCategoryEntity(mediaId, it, now) } }
            rows.chunked(QUERY_CHUNK).forEach { insertAssignments(it) }
        }
        remove.forEach { categoryId ->
            mediaIds.chunked(QUERY_CHUNK).forEach { deleteAssignments(categoryId, it) }
        }
    }

    companion object {
        const val QUERY_CHUNK = 900
    }
}
