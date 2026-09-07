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
    val width: Int = 0,
    val height: Int = 0,
    val durationMillis: Long? = null,
) {
    val isVideo: Boolean get() = type == MediaType.VIDEO
}
