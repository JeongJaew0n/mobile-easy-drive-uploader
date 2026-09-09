package com.jjw.easygallery.feature.categories

import com.jjw.easygallery.core.domain.model.Category
import com.jjw.easygallery.feature.categories.CategoryReorder.moved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryReorderTest {

    @Test
    fun `moved relocates one item and ignores out of range`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "a", "c", "d"), list.moved(0, 1))
        assertEquals(listOf("b", "c", "d", "a"), list.moved(0, 3))
        assertEquals(list, list.moved(0, 0))
        assertEquals(list, list.moved(0, 9))
        assertEquals(list, list.moved(-1, 1))
    }

    @Test
    fun `step swaps once per row height and keeps the remainder`() {
        assertEquals(CategoryReorder.Step(0, HALF_ROW), CategoryReorder.step(0, HALF_ROW, ROW, SIZE))
        assertEquals(CategoryReorder.Step(1, HALF_ROW), CategoryReorder.step(0, ROW + HALF_ROW, ROW, SIZE))
        assertEquals(CategoryReorder.Step(2, 0f), CategoryReorder.step(0, (2 * ROW).toFloat(), ROW, SIZE))
        assertEquals(CategoryReorder.Step(0, -HALF_ROW), CategoryReorder.step(1, -ROW - HALF_ROW, ROW, SIZE))
    }

    @Test
    fun `step does not run past the ends and never divides by zero`() {
        // 목록 끝에서 더 끌어도 마지막 자리에 머물고, 누적량은 실제 이동한 만큼만 줄어든다
        val atEnd = CategoryReorder.step(2, (5 * ROW).toFloat(), ROW, SIZE)
        assertEquals(2, atEnd.index)
        assertEquals((5 * ROW).toFloat(), atEnd.offset, 0.01f)
        assertEquals(CategoryReorder.Step(1, ROW.toFloat()), CategoryReorder.step(1, ROW.toFloat(), 0, SIZE))
    }

    @Test
    fun `changedOrder returns ids only when the order actually changed`() {
        val a = category(1, "A")
        val b = category(2, "B")
        assertNull(CategoryReorder.changedOrder(listOf(a, b), listOf(a, b)))
        assertEquals(listOf(2L, 1L), CategoryReorder.changedOrder(listOf(a, b), listOf(b, a)))
    }

    private fun category(id: Long, name: String) =
        Category(id = id, name = name, colorIndex = 0, sortOrder = id.toInt(), itemCount = 0)

    private companion object {
        const val ROW = 100
        const val HALF_ROW = 50f
        const val SIZE = 3
    }
}
