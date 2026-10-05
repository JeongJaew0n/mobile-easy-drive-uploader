package com.jjw.easygallery.feature.gallery

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jjw.easygallery.R

/**
 * `relativePath` 에서 "어느 앱의 사진인지" 를 뽑는다('다른 앱' 탭의 묶음 머리글).
 *
 * MediaStore 의 `OWNER_PACKAGE_NAME` 은 쓰지 않는다 — **그 앱이 MediaStore 를 통해 직접 넣은
 * 파일에만** 채워져서, 예전 기기에서 복사해 온 사진은 대개 비어 있다. 폴더 이름이 더 잘 맞는다.
 */
internal object AppFolders {

    /**
     * 표준 최상위 폴더 **바로 아래**가 그 앱의 폴더다(`Pictures/KakaoTalk/` → `KakaoTalk`).
     * 표준 폴더가 아니면 최상위 자체를 쓴다(`SilentCamera/` → `SilentCamera`).
     * 경로가 없으면 null.
     */
    fun folderOf(relativePath: String): String? {
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        val top = segments.firstOrNull() ?: return null
        val isMediaRoot = MEDIA_ROOTS.any { it.equals(top, ignoreCase = true) }
        return if (isMediaRoot) segments.getOrNull(1) ?: top else top
    }

    /** 폴더 이름이 한국어로 달리 불리는 것만 바꾼다. 표에 없으면 폴더 이름 그대로 보여준다 */
    fun displayNameRes(folder: String): Int? = KOREAN[folder.lowercase()]

    /** 안드로이드가 정해 둔 미디어 최상위 폴더. 이 아래 한 칸이 앱 폴더다 */
    private val MEDIA_ROOTS = listOf("DCIM", "Pictures", "Movies", "Download", "Documents", "Music")

    private val KOREAN: Map<String, Int> = mapOf(
        "kakaotalk" to R.string.gallery_app_kakaotalk,
        "kakaotalkdownload" to R.string.gallery_app_kakaotalk_download,
        "download" to R.string.gallery_app_download,
        "documents" to R.string.gallery_app_documents,
        "screenshots" to R.string.gallery_app_screenshots,
    )
}

/**
 * 앨범(폴더) 이름을 화면에 보일 말로. 저장공간 맨 위(`/`)에 바로 놓인 파일은 폴더 이름이 없다 — 빈 칸 대신
 * 삼성 갤러리처럼 "내장 저장공간". 카카오톡 같은 잘 알려진 폴더는 지금 언어의 이름으로, 나머지는 그대로.
 */
@Composable
internal fun albumLabel(name: String): String = when {
    name.isBlank() -> stringResource(R.string.album_storage_root)
    else -> AppFolders.displayNameRes(name)?.let { stringResource(it) } ?: name
}
