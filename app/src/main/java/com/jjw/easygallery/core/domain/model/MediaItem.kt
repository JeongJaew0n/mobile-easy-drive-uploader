package com.jjw.easygallery.core.domain.model

import android.net.Uri

enum class MediaType { IMAGE, VIDEO }

/** MediaStore 한 행에 대응하는 도메인 모델. */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val type: MediaType,
    val mimeType: String,
    val sizeBytes: Long,
    /** 촬영 시각(ms). DATE_TAKEN 이 없으면 DATE_ADDED 로 대체된 값. */
    val dateTakenMillis: Long,
    val bucketId: Long,
    val bucketName: String,
    /** 예: "DCIM/Camera/" — 앨범 이동의 대상 경로 단위 */
    val relativePath: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMillis: Long? = null,
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
) {
    val isVideo: Boolean get() = type == MediaType.VIDEO

    /** 확장자(점 제외). 없으면 빈 문자열 */
    val extension: String get() = displayName.substringAfterLast('.', "")
}
