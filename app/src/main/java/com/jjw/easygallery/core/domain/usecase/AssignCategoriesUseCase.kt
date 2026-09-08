package com.jjw.easygallery.core.domain.usecase

import com.jjw.easygallery.core.data.category.CategoryRepository
import javax.inject.Inject

/**
 * 갤러리 선택 모드와 상세보기가 같은 진입점으로 카테고리를 붙이고 뗀다.
 * 나중에 "할당 시 Drive appProperties 갱신" 같은 부수 효과를 붙일 자리.
 */
class AssignCategoriesUseCase @Inject constructor(
    private val categories: CategoryRepository,
) {
    suspend operator fun invoke(mediaIds: Collection<Long>, add: Set<Long>, remove: Set<Long>) {
        categories.assign(mediaIds, add, remove)
    }
}
