package com.jjw.easygallery.feature.categories

import com.jjw.easygallery.core.domain.model.Category

/**
 * 드래그 정렬의 순수 계산부(`docs/CATEGORIES.md` §12). UI 는 이 결과로 목록만 다시 그린다.
 * 행 높이가 모두 같다고 보고, 누적 이동량이 행 하나를 넘을 때마다 한 칸씩 자리를 바꾼다.
 */
internal object CategoryReorder {

    /** [from] 에 있던 항목을 [to] 로 옮긴 새 목록. 범위를 벗어나면 원본 그대로 */
    fun <T> List<T>.moved(from: Int, to: Int): List<T> {
        if (from !in indices || to !in indices || from == to) return this
        val copy = toMutableList()
        copy.add(to, copy.removeAt(from))
        return copy
    }

    /**
     * 드래그 중 한 칸 이동이 필요한지 판단한다.
     * @param index 드래그 중인 항목의 현재 위치
     * @param offset 마지막 자리바꿈 이후 누적된 세로 이동량(px)
     * @param rowHeight 행 높이(px)
     * @param size 목록 길이
     * @return 새 위치와 남은 이동량. 바꿀 필요가 없으면 [index] 와 [offset] 그대로
     */
    fun step(index: Int, offset: Float, rowHeight: Int, size: Int): Step {
        val steps = if (rowHeight > 0) (offset / rowHeight).toInt() else 0
        val target = if (steps == 0) index else (index + steps).coerceIn(0, size - 1)
        // 실제로 움직인 칸 수만큼만 누적량에서 뺀다(목록 끝에서 더 끌어도 밀리지 않게)
        return if (target == index) Step(index, offset) else Step(target, offset - (target - index) * rowHeight)
    }

    data class Step(val index: Int, val offset: Float)

    /** 드래그가 끝났을 때 저장할 순서. 바뀐 게 없으면 null */
    fun changedOrder(before: List<Category>, after: List<Category>): List<Long>? {
        val beforeIds = before.map { it.id }
        val afterIds = after.map { it.id }
        return afterIds.takeIf { it != beforeIds }
    }
}
