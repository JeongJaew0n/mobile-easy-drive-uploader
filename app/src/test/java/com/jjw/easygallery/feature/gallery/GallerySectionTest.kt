package com.jjw.easygallery.feature.gallery

import android.net.Uri
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class GallerySectionTest {

    private val zone: ZoneId = ZoneOffset.UTC

    @Test
    fun `empty input yields no sections`() {
        assertTrue(groupByDate(emptyList(), zone).isEmpty())
    }

    @Test
    fun `items on the same day share one section and keep input order`() {
        val day = LocalDate.of(2026, 9, 7)
        val a = item(id = 1, date = day, time = LocalTime.of(23, 0))
        val b = item(id = 2, date = day, time = LocalTime.of(9, 0))

        val sections = groupByDate(listOf(a, b), zone)

        assertEquals(1, sections.size)
        assertEquals(day, sections[0].date)
        assertEquals(listOf(a, b), sections[0].items)
    }

    @Test
    fun `different days produce separate sections in input order`() {
        val newer = item(id = 1, date = LocalDate.of(2026, 9, 7), time = LocalTime.NOON)
        val older = item(id = 2, date = LocalDate.of(2026, 9, 6), time = LocalTime.NOON)
        val oldest = item(id = 3, date = LocalDate.of(2026, 1, 1), time = LocalTime.NOON)

        val sections = groupByDate(listOf(newer, older, oldest), zone)

        assertEquals(listOf(newer.date(), older.date(), oldest.date()), sections.map { it.date })
        assertEquals(listOf(1, 1, 1), sections.map { it.items.size })
    }

    @Test
    fun `day boundary respects the given zone`() {
        // UTC 기준 9/6 23:30 은 KST(+9) 로는 9/7 08:30
        val utcLate = item(id = 1, date = LocalDate.of(2026, 9, 6), time = LocalTime.of(23, 30))

        val utcSections = groupByDate(listOf(utcLate), ZoneOffset.UTC)
        val kstSections = groupByDate(listOf(utcLate), ZoneId.of("Asia/Seoul"))

        assertEquals(LocalDate.of(2026, 9, 6), utcSections.single().date)
        assertEquals(LocalDate.of(2026, 9, 7), kstSections.single().date)
    }

    @Test
    fun `toggleSection selects the whole day when it is not fully selected`() {
        val day = listOf(1L, 2L, 3L)

        assertEquals(setOf(1L, 2L, 3L), emptySet<Long>().toggleSection(day))
        // 일부만 선택된 상태에서는 나머지를 마저 채운다
        assertEquals(setOf(1L, 2L, 3L), setOf(2L).toggleSection(day))
        // 다른 날짜의 선택은 건드리지 않는다
        assertEquals(setOf(9L, 1L, 2L, 3L), setOf(9L).toggleSection(day))
    }

    @Test
    fun `toggleSection clears the day when every item is selected`() {
        val day = listOf(1L, 2L, 3L)

        assertEquals(emptySet<Long>(), setOf(1L, 2L, 3L).toggleSection(day))
        assertEquals(setOf(9L), setOf(9L, 1L, 2L, 3L).toggleSection(day))
    }

    @Test
    fun `toggleSection on an empty day keeps the selection`() {
        assertEquals(setOf(9L), setOf(9L).toggleSection(emptyList()))
    }

    @Test
    fun `formatDuration renders minutes and hours`() {
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("1:02", formatDuration(62_000))
        assertEquals("1:00:00", formatDuration(3_600_000))
    }

    @Test
    fun `countByDay counts items per local date`() {
        val a = item(id = 1, date = LocalDate.of(2026, 9, 7), time = LocalTime.NOON)
        val b = item(id = 2, date = LocalDate.of(2026, 9, 7), time = LocalTime.MIDNIGHT)
        val c = item(id = 3, date = LocalDate.of(2026, 9, 6), time = LocalTime.NOON)

        val counts = countByDay(listOf(a, b, c), zone)

        assertEquals(2, counts[LocalDate.of(2026, 9, 7)])
        assertEquals(1, counts[LocalDate.of(2026, 9, 6)])
        assertEquals(null, counts[LocalDate.of(2026, 9, 5)])
        assertTrue(countByDay(emptyList(), zone).isEmpty())
    }

    private fun MediaItem.date(): LocalDate =
        java.time.Instant.ofEpochMilli(dateTakenMillis).atZone(zone).toLocalDate()

    private fun item(id: Long, date: LocalDate, time: LocalTime): MediaItem = MediaItem(
        id = id,
        uri = mockk<Uri>(relaxed = true),
        displayName = "IMG_$id.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 1_024,
        dateTakenMillis = date.atTime(time).toInstant(ZoneOffset.UTC).toEpochMilli(),
        bucketId = 1,
        bucketName = "Camera",
    )
}
