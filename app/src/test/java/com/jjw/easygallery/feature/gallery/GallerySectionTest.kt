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
    fun `formatDuration renders minutes and hours`() {
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("1:02", formatDuration(62_000))
        assertEquals("1:00:00", formatDuration(3_600_000))
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
