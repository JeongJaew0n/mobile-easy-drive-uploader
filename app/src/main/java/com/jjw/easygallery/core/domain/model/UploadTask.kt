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
    /** 서버가 준 실패 코드. 화면이 이걸로 문장을 고른다 */
    val errorReason: String? = null,
    val attemptCount: Int,
    val createdAt: Long,
    val width: Int = 0,
    val height: Int = 0,
    /** 업로드 대상 계정. null = Google Drive */
    val accountId: String? = null,
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
    /** 큐가 멈춰 있다면 그 이유. 화면이 "업로드 중" 대신 기다리는 까닭을 말한다 */
    val waitReason: UploadWaitReason = UploadWaitReason.NONE,
    /**
     * 실패한 것들이 **모두 같은 이유**면 그 코드. 배너가 개수만 말하지 않고 까닭을 말한다 —
     * "1553개 실패" 만으로는 무엇을 해야 할지 알 수 없다. 이유가 섞였으면 null.
     */
    val failureReason: String? = null,
) {
    val hasActive: Boolean get() = active > 0
}
