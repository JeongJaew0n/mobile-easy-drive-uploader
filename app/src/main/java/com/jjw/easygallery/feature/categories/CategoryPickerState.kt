package com.jjw.easygallery.feature.categories

import com.jjw.easygallery.core.domain.model.CategoryAssignments

/** 피커 행의 상태. 여러 항목을 골랐을 때 일부에만 붙어 있으면 [Partial] */
enum class PickState { None, Partial, All }

/**
 * 카테고리 피커의 순수 상태(`docs/CATEGORIES.md` §5.1).
 * - 초기값은 선택 항목들의 현재 할당으로 계산한다(전부/일부/없음)
 * - 탭 전이: Partial → All → None → All … (Partial 로는 되돌아가지 않는다)
 * - [diff] 는 바뀐 행만 add/remove 로 돌려준다. Partial 그대로인 행은 건드리지 않는다
 */
data class CategoryPickerState(
    val initial: Map<Long, PickState>,
    val current: Map<Long, PickState> = initial,
) {
    fun stateOf(categoryId: Long): PickState = current[categoryId] ?: PickState.None

    fun toggle(categoryId: Long): CategoryPickerState {
        val next = when (stateOf(categoryId)) {
            PickState.None, PickState.Partial -> PickState.All
            PickState.All -> PickState.None
        }
        return copy(current = current + (categoryId to next))
    }

    /** 새로 만든 카테고리는 바로 붙은 상태로 목록에 들어온다 */
    fun withNewCategory(categoryId: Long): CategoryPickerState =
        copy(initial = initial + (categoryId to PickState.None), current = current + (categoryId to PickState.All))

    val hasChanges: Boolean get() = diff().let { it.add.isNotEmpty() || it.remove.isNotEmpty() }

    fun diff(): Diff {
        val add = HashSet<Long>()
        val remove = HashSet<Long>()
        current.forEach { (id, state) ->
            val before = initial[id] ?: PickState.None
            if (state == before) return@forEach
            when (state) {
                PickState.All -> add += id
                PickState.None -> remove += id
                PickState.Partial -> Unit
            }
        }
        return Diff(add, remove)
    }

    data class Diff(val add: Set<Long>, val remove: Set<Long>)

    companion object {
        fun of(
            mediaIds: Collection<Long>,
            categoryIds: Collection<Long>,
            assignments: CategoryAssignments,
        ): CategoryPickerState {
            val total = mediaIds.size
            val initial = categoryIds.associateWith { categoryId ->
                val count = mediaIds.count { assignments[it]?.contains(categoryId) == true }
                when {
                    total == 0 || count == 0 -> PickState.None
                    count == total -> PickState.All
                    else -> PickState.Partial
                }
            }
            return CategoryPickerState(initial)
        }
    }
}
