package com.jjw.easygallery.core.domain.model

import android.net.Uri

enum class UploadState { PENDING, RUNNING, COMPLETED, FAILED }

/** 업로드 큐의 한 항목. Room 에 저장되어 프로세스 종료 후에도 이어서 올린다. */
data class UploadTask(
    val id: Long,
    val mediaId: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val folderId: String?,
    val folderName: String?,
    val state: UploadState,
    /** Drive resumable 세션 URI. 있으면 상태를 조회해 끊긴 지점부터 이어 올린다. */
    val sessionUri: String?,
    val bytesUploaded: Long,
    val driveFileId: String?,
    val errorMessage: String?,
    val attemptCount: Int,
    val createdAt: Long,
) {
    val fraction: Float get() = if (sizeBytes > 0) (bytesUploaded.toFloat() / sizeBytes).coerceIn(0f, 1f) else 0f
    val isActive: Boolean get() = state == UploadState.PENDING || state == UploadState.RUNNING
}

data class UploadSummary(
    val total: Int = 0,
    val active: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    /** 진행 중(없으면 다음 대기) 항목 */
    val current: UploadTask? = null,
) {
    val hasActive: Boolean get() = active > 0
}
