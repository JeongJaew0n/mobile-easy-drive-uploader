package com.jjw.easygallery.core.data.autotag

import android.net.Uri
import com.jjw.easygallery.core.data.autotag.AutoTagRepository.Companion.shouldAnalyze
import com.jjw.easygallery.core.data.upload.db.AutoTagScanEntity
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTagRetryTest {

    private val item = item(sizeBytes = 1000, modified = 500)

    @Test
    fun `기록이 없으면 분석한다`() {
        assertTrue(shouldAnalyze(item, null))
    }

    @Test
    fun `성공 기록이 있으면 건너뛴다`() {
        assertFalse(shouldAnalyze(item, scan(failureCount = 0)))
    }

    @Test
    fun `실패가 한계 미만이면 다시 시도한다`() {
        assertTrue(shouldAnalyze(item, scan(failureCount = 1)))
        assertTrue(shouldAnalyze(item, scan(failureCount = 2)))
    }

    @Test
    fun `실패가 한계에 닿으면 더 시도하지 않는다`() {
        assertFalse(shouldAnalyze(item, scan(failureCount = AutoTagScanEntity.MAX_FAILURES)))
        assertFalse(shouldAnalyze(item, scan(failureCount = AutoTagScanEntity.MAX_FAILURES + 1)))
    }

    @Test
    fun `파일이 바뀌면 성공 기록도 포기 기록도 무시하고 다시 분석한다`() {
        val edited = item.copy(sizeBytes = 2000)
        assertTrue(shouldAnalyze(edited, scan(failureCount = 0)))
        assertTrue(shouldAnalyze(edited, scan(failureCount = AutoTagScanEntity.MAX_FAILURES)))

        val touched = item.copy(dateModifiedSeconds = 900)
        assertTrue(shouldAnalyze(touched, scan(failureCount = AutoTagScanEntity.MAX_FAILURES)))
    }

    private fun scan(failureCount: Int) = AutoTagScanEntity(
        mediaId = item.id,
        sizeBytes = item.sizeBytes,
        dateModifiedSeconds = item.dateModifiedSeconds,
        scannedAt = 1,
        failureCount = failureCount,
    )

    private fun item(sizeBytes: Long, modified: Long) = MediaItem(
        id = 1,
        uri = mockk<Uri>(relaxed = true),
        displayName = "IMG.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = sizeBytes,
        dateTakenMillis = 0,
        dateAddedSeconds = 0,
        dateModifiedSeconds = modified,
        bucketId = 1,
        bucketName = "Camera",
        relativePath = "DCIM/Camera/",
    )
}
