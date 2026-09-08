package com.jjw.easygallery.feature.categories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryPickerStateTest {

    private val assignments = mapOf(
        1L to setOf(10L, 20L),
        2L to setOf(10L),
        3L to emptySet<Long>(),
    )

    @Test
    fun `initial state reflects all, partial and none`() {
        val state = CategoryPickerState.of(listOf(1, 2, 3), listOf(10, 20, 30), assignments)
        assertEquals(PickState.Partial, state.stateOf(10)) // 1·2 에만
        assertEquals(PickState.Partial, state.stateOf(20)) // 1 에만
        assertEquals(PickState.None, state.stateOf(30))
        assertFalse(state.hasChanges)

        val single = CategoryPickerState.of(listOf(1), listOf(10, 30), assignments)
        assertEquals(PickState.All, single.stateOf(10))
        assertEquals(PickState.None, single.stateOf(30))
    }

    @Test
    fun `toggle cycles partial to all to none to all`() {
        var state = CategoryPickerState.of(listOf(1, 2, 3), listOf(10), assignments)
        state = state.toggle(10)
        assertEquals(PickState.All, state.stateOf(10))
        state = state.toggle(10)
        assertEquals(PickState.None, state.stateOf(10))
        state = state.toggle(10)
        assertEquals(PickState.All, state.stateOf(10))
    }

    @Test
    fun `diff only contains changed rows`() {
        val state = CategoryPickerState.of(listOf(1, 2, 3), listOf(10, 20, 30), assignments)
            .toggle(10) // Partial → All (add)
            .toggle(30) // None → All → ...
            .toggle(30) // → None (변화 없음)
        val diff = state.diff()
        assertEquals(setOf(10L), diff.add)
        assertTrue(diff.remove.isEmpty())
        assertTrue(state.hasChanges)
    }

    @Test
    fun `turning an all row off removes it`() {
        val state = CategoryPickerState.of(listOf(1), listOf(10), assignments).toggle(10)
        assertEquals(setOf(10L), state.diff().remove)
    }

    @Test
    fun `new category starts checked and counts as add`() {
        val state = CategoryPickerState.of(listOf(1, 2), listOf(10), assignments).withNewCategory(99)
        assertEquals(PickState.All, state.stateOf(99))
        assertEquals(setOf(99L), state.diff().add)
    }

    @Test
    fun `empty selection yields none for everything`() {
        val state = CategoryPickerState.of(emptyList(), listOf(10, 20), assignments)
        assertEquals(PickState.None, state.stateOf(10))
    }
}
