package com.jjw.easygallery.core.data.category

import com.jjw.easygallery.core.domain.model.Category
import com.jjw.easygallery.core.domain.model.CategoryAssignments
import kotlinx.coroutines.flow.Flow

/** 카테고리 CRUD 와 항목 할당. 설계: `docs/CATEGORIES.md` §3 */
interface CategoryRepository {
    /** sortOrder 순, 항목 수 포함 */
    fun observeCategories(): Flow<List<Category>>

    /** 앱 범위 공유(갤러리·상세보기·관리 화면이 같은 맵을 본다) */
    fun observeAssignments(): Flow<CategoryAssignments>

    /** 실패: [com.jjw.easygallery.core.domain.model.CategoryError] */
    suspend fun create(name: String, colorIndex: Int): Result<Category>

    suspend fun rename(id: Long, name: String): Result<Unit>

    suspend fun recolor(id: Long, colorIndex: Int)

    suspend fun reorder(orderedIds: List<Long>)

    suspend fun delete(id: Long)

    /** [mediaIds] 에 [add] 를 붙이고 [remove] 를 뗀다(한 트랜잭션) */
    suspend fun assign(mediaIds: Collection<Long>, add: Set<Long>, remove: Set<Long>)

    /** 영구 삭제·고아 정리 — 이 항목들의 할당을 모두 지운다 */
    suspend fun removeMedia(mediaIds: Collection<Long>)
}
