package com.jjw.easygallery.feature.drive

import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveMediaOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

class DrivePhotosModelTest {

    private val zone = ZoneOffset.UTC

    @Test
    fun `원장에 있고 기기에도 남은 것만 기기에 있음`() {
        val remoteToMedia = mapOf("a" to 1L, "b" to 2L)
        // b 는 기기에서 지웠다. c 는 원장에 없다(다른 기기에서 올림)
        assertEquals(setOf("a"), onDeviceRemoteIds(remoteToMedia, deviceIds = setOf(1L, 9L)))
    }

    @Test
    fun `기기에 없음은 원장에 없는 것까지 남긴다`() {
        val entries = listOf(entry("a"), entry("b"), entry("c"))
        val visible = entries.filteredBy(DrivePhotoFilter.NOT_ON_DEVICE, onDeviceIds = setOf("a"))
        assertEquals(listOf("b", "c"), visible.map { it.id })
    }

    @Test
    fun `찍은 날짜순 - EXIF, 없으면 파일 이름의 시각, 둘 다 없으면 맨 뒤`() {
        val entries = listOf(
            entry("old-upload-new-shot", created = at(2026, 1, 1), taken = at(2026, 9, 1)),
            // EXIF 없는 영상 — 이름이 찍은 시각이다
            entry("20260501_120000", created = at(2026, 9, 30)),
            // 올린 시각만 있다 — 올린 달로 묶지 않는다(2026-10-08 기기)
            entry("unknown", created = at(2026, 10, 1)),
            entry("shot", created = at(2026, 9, 30), taken = at(2025, 12, 25)),
        )
        val sorted = entries.sortedFor(DriveMediaOrder.TAKEN).map { it.id }
        assertEquals(listOf("old-upload-new-shot", "20260501_120000", "shot", "unknown"), sorted)
    }

    @Test
    fun `파일 이름의 시각 - 카메라 형식과 13자리 밀리초`() {
        val kst = ZoneOffset.ofHours(9)
        assertEquals(
            LocalDateTime.of(2023, 8, 8, 12, 25, 40).toInstant(kst).toEpochMilli(),
            timeFromFileName("20230808_122540.mp4", kst),
        )
        assertEquals(
            LocalDateTime.of(2024, 1, 2, 3, 4, 5).toInstant(kst).toEpochMilli(),
            timeFromFileName("Screenshot_20240102-030405_KakaoTalk.jpg", kst),
        )
        assertEquals(1_435_487_647_130L, timeFromFileName("1435487647130.jpeg", kst))
        // 날짜처럼 생겼지만 아닌 것
        assertEquals(null, timeFromFileName("20231340_999999.jpg", kst))
        assertEquals(null, timeFromFileName("2일차", kst))
        assertEquals(null, timeFromFileName("0000000000001.jpg", kst))
    }

    @Test
    fun `올린 날짜순은 서버 순서를 그대로 둔다`() {
        val entries = listOf(entry("b", created = at(2026, 1, 1)), entry("a", created = at(2026, 9, 1)))
        assertEquals(entries, entries.sortedFor(DriveMediaOrder.UPLOADED))
    }

    @Test
    fun `달이 바뀔 때마다 머리글을 끼운다`() {
        val entries = listOf(
            entry("1", created = at(2026, 10, 3)),
            entry("2", created = at(2026, 10, 1)),
            entry("3", created = at(2026, 9, 30)),
            entry("4"),
        )
        val cells = entries.toCells(DriveMediaOrder.UPLOADED, zone)
        assertEquals(
            listOf(
                DrivePhotoCell.Header(YearMonth.of(2026, 10)),
                DrivePhotoCell.Photo(entries[0]),
                DrivePhotoCell.Photo(entries[1]),
                DrivePhotoCell.Header(YearMonth.of(2026, 9)),
                DrivePhotoCell.Photo(entries[2]),
                DrivePhotoCell.Header(null),
                DrivePhotoCell.Photo(entries[3]),
            ),
            cells,
        )
    }

    @Test
    fun `다음 쪽을 이을 때 겹친 것은 뺀다`() {
        val merged = listOf(entry("a"), entry("b")).appendNew(listOf(entry("b"), entry("c")))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    private fun at(year: Int, month: Int, day: Int): Long =
        LocalDateTime.of(year, month, day, 12, 0).toInstant(zone).toEpochMilli()

    private fun entry(id: String, created: Long? = null, taken: Long? = null) = DriveEntry(
        id = id,
        name = "$id.jpg",
        mimeType = "image/jpeg",
        sizeBytes = null,
        modifiedTimeMillis = null,
        webViewLink = null,
        createdTimeMillis = created,
        takenTimeMillis = taken,
    )
}
