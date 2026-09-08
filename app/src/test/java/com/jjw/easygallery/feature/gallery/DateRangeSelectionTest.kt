package com.jjw.easygallery.feature.gallery

import com.jjw.easygallery.core.domain.model.DateRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DateRangeSelectionTest {

    private val d1 = LocalDate.of(2026, 9, 1)
    private val d5 = LocalDate.of(2026, 9, 5)
    private val d9 = LocalDate.of(2026, 9, 9)

    @Test
    fun `empty selection has no range`() {
        assertNull(DateRangeSelection().toDateRange())
        assertFalse(DateRangeSelection().contains(d5))
    }

    @Test
    fun `first tap is start and counts as a single day`() {
        val s = DateRangeSelection().select(d5)
        assertEquals(DateRange(d5, d5), s.toDateRange())
        assertTrue(s.contains(d5))
        assertFalse(s.contains(d1))
    }

    @Test
    fun `second tap after start completes the range`() {
        val s = DateRangeSelection().select(d1).select(d9)
        assertEquals(DateRange(d1, d9), s.toDateRange())
        assertTrue(s.contains(d5))
    }

    @Test
    fun `tapping before start restarts from that day`() {
        val s = DateRangeSelection().select(d5).select(d1)
        assertEquals(DateRangeSelection(start = d1), s)
    }

    @Test
    fun `tapping the start day again makes a single day range`() {
        val s = DateRangeSelection().select(d5).select(d5)
        assertEquals(DateRange(d5, d5), s.toDateRange())
    }

    @Test
    fun `tapping after a completed range starts over`() {
        val s = DateRangeSelection().select(d1).select(d5).select(d9)
        assertEquals(DateRangeSelection(start = d9), s)
    }

    @Test
    fun `of restores an existing range`() {
        assertEquals(DateRangeSelection(d1, d9), DateRangeSelection.of(DateRange(d1, d9)))
        assertEquals(DateRangeSelection(), DateRangeSelection.of(null))
    }
}
