package com.jjw.easygallery.core.domain.model

/** 상세보기 정보 패널용 메타데이터. EXIF 가 없거나 읽을 수 없으면 각 필드는 null. */
data class MediaDetails(
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val aperture: String? = null,
    val exposureTime: String? = null,
    val isoSensitivity: String? = null,
    val focalLength: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val hasCameraInfo: Boolean
        get() = listOfNotNull(cameraMake, cameraModel, aperture, exposureTime, isoSensitivity, focalLength).isNotEmpty()

    val hasLocation: Boolean get() = latitude != null && longitude != null
}
