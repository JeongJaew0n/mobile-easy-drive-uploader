package com.jjw.easygallery.core.domain.model

import android.net.Uri
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class DateRangeTest {

    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    @Test
    fun `end before start is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            DateRange(LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 7))
        }
    }

    @Test
    fun `null range keeps every item`() {
        val items = listOf(item(1, LocalDate.of(2020, 1, 1)), item(2, LocalDate.of(2026, 9, 8)))

        assertEquals(items, items.filterByDate(null, seoul))
    }

    @Test
    fun `both boundary days are included`() {
        val range = DateRange(LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 8))
        val items = listOf(
            item(1, LocalDate.of(2026, 9, 5), LocalTime.of(23, 59)),
            item(2, LocalDate.of(2026, 9, 6), LocalTime.MIDNIGHT),
            item(3, LocalDate.of(2026, 9, 7), LocalTime.NOON),
            item(4, LocalDate.of(2026, 9, 8), LocalTime.of(23, 59, 59)),
            item(5, LocalDate.of(2026, 9, 9), LocalTime.MIDNIGHT),
        )

        assertEquals(listOf(2L, 3L, 4L), items.filterByDate(range, seoul).map { it.id })
    }

    @Test
    fun `range boundaries follow the given zone`() {
        // 서울 9월 8일 00:30 은 UTC 로는 9월 7일 15:30
        val item = item(1, LocalDate.of(2026, 9, 8), LocalTime.of(0, 30), seoul)
        val eighth = DateRange(LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 8))

        assertEquals(listOf(1L), listOf(item).filterByDate(eighth, seoul).map { it.id })
        assertTrue(listOf(item).filterByDate(eighth, ZoneOffset.UTC).isEmpty())
    }

    @Test
    fun `presets count today as the last day`() {
        val today = LocalDate.of(2026, 9, 8)

        assertEquals(DateRange(today, today), DatePreset.TODAY.toRange(today))
        assertEquals(DateRange(LocalDate.of(2026, 9, 2), today), DatePreset.LAST_7_DAYS.toRange(today))
        assertEquals(DateRange(LocalDate.of(2026, 8, 10), today), DatePreset.LAST_30_DAYS.toRange(today))
        assertEquals(DateRange(LocalDate.of(2026, 1, 1), today), DatePreset.THIS_YEAR.toRange(today))
    }

    @Test
    fun `isSingleDay marks one-day ranges`() {
        val day = LocalDate.of(2026, 9, 8)

        assertTrue(DateRange(day, day).isSingleDay)
        assertTrue(!DateRange(day.minusDays(1), day).isSingleDay)
    }

    private fun item(
        id: Long,
        date: LocalDate,
        time: LocalTime = LocalTime.NOON,
        zone: ZoneId = seoul,
    ) = MediaItem(
        id = id,
        uri = mockk<Uri>(relaxed = true),
        displayName = "IMG_$id.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 1,
        dateTakenMillis = date.atTime(time).atZone(zone).toInstant().toEpochMilli(),
        bucketId = 1,
        bucketName = "Camera",
    )
}
