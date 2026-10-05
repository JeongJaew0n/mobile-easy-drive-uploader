package com.jjw.easygallery.feature.gallery

import android.net.Uri
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import com.jjw.easygallery.core.domain.model.UploadRecord
import com.jjw.easygallery.core.domain.model.UploadState
import com.jjw.easygallery.core.domain.model.UploadTask
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class BackupScopeTest {

    private val zone = ZoneOffset.UTC
    private val uri = mockk<Uri>(relaxed = true)

    // 찍은 날짜 최신순(갤러리가 주는 순서)
    private val items = (1L..5L).map { item(it) }

    private val records = listOf(
        record(1, "drive:a@example.com", day(10, 5)),
        record(2, "drive:a@example.com", day(10, 1)),
        // 2 는 NAS 에도 — 더 늦게 올렸다
        record(2, "remote:nas", day(10, 6)),
        record(3, "remote:nas", day(10, 5)),
        // 주인 미정 옛 기록 — 주 Drive 로 본다
        record(4, "drive:", day(9, 1)),
    )

    @Test
    fun `백업됨 - 가장 최근에 올린 시각순, 올린 날짜로 묶는다`() {
        val sections = backupSections(inputs(BackupView()), items, uploadedAtTarget = emptySet(), zone = zone)
        assertEquals(
            listOf(
                SectionHeader.ByUploadDate(LocalDate.of(2026, 10, 6)) to listOf(2L),
                SectionHeader.ByUploadDate(LocalDate.of(2026, 10, 5)) to listOf(1L, 3L),
                SectionHeader.ByUploadDate(LocalDate.of(2026, 9, 1)) to listOf(4L),
            ),
            sections.map { it.header to it.items.map { i -> i.id } },
        )
    }

    @Test
    fun `백업됨 - 저장소 하나만, 주인 미정 기록은 주 Drive 에 든다`() {
        val view = BackupView(destination = "drive:a@example.com")
        val ids = backupSections(inputs(view), items, emptySet(), zone).flatMap { it.items }.map { it.id }
        assertEquals(listOf(1L, 2L, 4L), ids)
    }

    @Test
    fun `안 됨 - 어디에도 안 올라간 것, 저장소를 고르면 그곳에 없는 것`() {
        val none = backupSections(inputs(BackupView(BackupStatusFilter.NOT_BACKED_UP)), items, emptySet(), zone)
        assertEquals(listOf(5L), none.flatMap { it.items }.map { it.id })
        val notOnNas = backupSections(
            inputs(BackupView(BackupStatusFilter.NOT_BACKED_UP, destination = "remote:nas")),
            items,
            emptySet(),
            zone,
        )
        assertEquals(listOf(1L, 4L, 5L), notOnNas.flatMap { it.items }.map { it.id })
    }

    @Test
    fun `대기·실패 - 대기 먼저, 실패는 까닭별로 많은 것부터, 원장에 있는 실패는 뺀다`() {
        val tasks = listOf(
            task(5, UploadState.PENDING),
            task(1, UploadState.FAILED, reason = "storageQuotaExceeded"),
            task(2, UploadState.FAILED, reason = "storageQuotaExceeded"),
            task(3, UploadState.FAILED, reason = "weird"),
            // 4 는 실패 줄이 남았지만 원장(지금 대상)에 있다
            task(4, UploadState.FAILED, reason = "weird"),
        )
        val sections = backupSections(
            inputs(BackupView(BackupStatusFilter.PENDING_OR_FAILED), tasks),
            items,
            uploadedAtTarget = setOf(4L),
            zone = zone,
        )
        assertEquals(
            listOf(
                SectionHeader.ByUploadStatus(pending = true) to listOf(5L),
                SectionHeader.ByUploadStatus(false, R.string.upload_error_storage_full) to listOf(1L, 2L),
                SectionHeader.ByUploadStatus(false, R.string.upload_state_failed) to listOf(3L),
            ),
            sections.map { it.header to it.items.map { i -> i.id } },
        )
    }

    private fun inputs(view: BackupView, tasks: List<UploadTask> = emptyList()) =
        BackupInputs(view, records, tasks, primaryEmail = "a@example.com", accounts = emptyList())

    private fun day(month: Int, day: Int): Long =
        LocalDateTime.of(2026, month, day, 12, 0).toInstant(zone).toEpochMilli()

    private fun record(mediaId: Long, destination: String, at: Long) =
        UploadRecord(mediaId, destination, "r$mediaId", null, at, accountId = null)

    private fun task(mediaId: Long, state: UploadState, reason: String? = null) = UploadTask(
        id = mediaId,
        mediaId = mediaId,
        uri = uri,
        displayName = "IMG_$mediaId.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1,
        folderId = null,
        folderName = null,
        state = state,
        sessionUri = null,
        bytesUploaded = 0,
        driveFileId = null,
        errorMessage = null,
        errorReason = reason,
        attemptCount = 0,
        createdAt = 0,
    )

    private fun item(id: Long) = MediaItem(
        id = id,
        uri = uri,
        displayName = "IMG_$id.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 1_024,
        dateTakenMillis = 1_757_000_000_000 - id,
        bucketId = 1,
        bucketName = "Camera",
        relativePath = "DCIM/Camera/",
    )
}
