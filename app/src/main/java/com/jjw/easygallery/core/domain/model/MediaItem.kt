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
    val dateTakenMillis: Long,
    val bucketId: Long,
    val bucketName: String,
    val durationMillis: Long? = null,
)
