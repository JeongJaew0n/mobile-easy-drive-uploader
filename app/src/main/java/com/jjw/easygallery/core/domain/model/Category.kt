package com.jjw.easygallery.core.domain.model

/**
 * 사용자가 만든 카테고리. 한 항목에 여러 개 붙을 수 있다(N:M). 설계: `docs/CATEGORIES.md`.
 *
 * @property colorIndex 팔레트 인덱스 0 until [CATEGORY_COLOR_COUNT]. 실제 색은 UI 테마가 결정
 * @property sortOrder 관리 화면에서 사용자가 정한 순서. 필터 시트·피커·칩 모두 이 순서
 * @property itemCount 붙어 있는 항목 수(조회 시 파생)
 */
data class Category(
    val id: Long,
    val name: String,
    val colorIndex: Int,
    val sortOrder: Int,
    val itemCount: Int = 0,
)

/** mediaId → 붙은 카테고리 ID */
typealias CategoryAssignments = Map<Long, Set<Long>>

/** 갤러리의 카테고리 필터. null 이면 끔 */
sealed interface CategoryFilter {
    /** 고른 카테고리 중 하나라도 붙은 항목(OR) */
    data class Any(val ids: Set<Long>) : CategoryFilter

    /** 카테고리가 하나도 없는 항목 */
    data object Uncategorized : CategoryFilter

    fun matches(assigned: Set<Long>?): Boolean = when (this) {
        is Any -> assigned != null && assigned.any { it in ids }
        Uncategorized -> assigned.isNullOrEmpty()
    }
}

/** 카테고리 이름·생성 규칙 위반. Exception 은 Serializable 이라 data object 대신 클래스로 둔다 */
sealed class CategoryError(message: String) : Exception(message) {
    class EmptyName : CategoryError("카테고리 이름이 비어 있습니다")
    class NameTooLong : CategoryError("카테고리 이름은 $CATEGORY_NAME_MAX_LENGTH 자까지입니다")
    class DuplicateName : CategoryError("같은 이름의 카테고리가 있습니다")
}

const val CATEGORY_COLOR_COUNT = 8
const val CATEGORY_NAME_MAX_LENGTH = 30

/** 앞뒤 공백 제거·길이 검사. 유일성은 저장소가 검사한다 */
fun normalizeCategoryName(raw: String): Result<String> {
    val name = raw.trim()
    return when {
        name.isEmpty() -> Result.failure(CategoryError.EmptyName())
        name.length > CATEGORY_NAME_MAX_LENGTH -> Result.failure(CategoryError.NameTooLong())
        else -> Result.success(name)
    }
}
