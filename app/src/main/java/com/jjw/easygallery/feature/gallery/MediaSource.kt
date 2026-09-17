package com.jjw.easygallery.feature.gallery

import com.jjw.easygallery.core.domain.model.MediaItem

/**
 * 사진이 어디서 온 것인지(`docs/glossary/README.md`, `docs/plans/gallery-source-tabs`).
 * 갤러리 상단 탭의 기준이다.
 *
 * 판정은 [MediaItem.relativePath] 하나만 본다 — MediaStore 조회에 이미 들어 있어 재조회가 없다.
 */
enum class MediaSource {
    /** `DCIM/` 아래(스크린샷 제외). 카메라 앱과 사용자가 직접 만든 앨범 */
    CAMERA,

    /** 경로에 `Screenshots` 세그먼트. `DCIM/Screenshots` 와 `Pictures/Screenshots` 둘 다 */
    SCREENSHOT,

    /** 그 밖에 전부 — `Pictures/<앱>`, `Download`, `Documents` … */
    OTHER,
    ;

    companion object {
        /**
         * 폴더 이름으로 카메라 앱을 가려내려 하면 기기·앱마다 달라 끝이 없다.
         * 그래서 "어느 최상위 디렉터리에 썼는가" 라는 한 줄 규칙만 쓴다.
         * `SilentCamera/` 처럼 최상위에 쓰는 카메라류 앱이 [OTHER] 로 가지만,
         * "DCIM 밖에 쓰는 앱" 이라는 사실 자체는 맞다.
         */
        fun of(item: MediaItem): MediaSource {
            val segments = item.relativePath.split('/').filter { it.isNotBlank() }
            if (segments.any { it.equals(SCREENSHOTS, ignoreCase = true) }) return SCREENSHOT
            return if (segments.firstOrNull()?.equals(DCIM, ignoreCase = true) == true) CAMERA else OTHER
        }

        private const val DCIM = "DCIM"
        private const val SCREENSHOTS = "Screenshots"
    }
}

/** 갤러리 상단 탭. [MediaSource] 에 "전체" 를 더한 것. `GalleryUiState` 에 실려 나가 public 이다 */
enum class GalleryTab(val source: MediaSource?) {
    ALL(null),
    CAMERA(MediaSource.CAMERA),
    SCREENSHOT(MediaSource.SCREENSHOT),
    OTHER(MediaSource.OTHER),
    ;

    fun matches(item: MediaItem): Boolean = source == null || MediaSource.of(item) == source
}
