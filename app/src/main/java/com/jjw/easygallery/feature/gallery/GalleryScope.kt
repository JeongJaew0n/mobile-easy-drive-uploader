package com.jjw.easygallery.feature.gallery

/**
 * 앨범 칸에서 연 갤러리의 범위(`docs/plans/bottom-navigation/spec.md`). 갤러리 화면을 그대로 쓰되 출처 탭 대신
 * ← 와 이름이 붙고, 선택·업로드·삭제는 그대로 된다.
 */
sealed interface GalleryScope {
    /** 앨범(폴더) 하나 — MediaStore `relativePath` */
    data class Album(val relativePath: String) : GalleryScope

    data object Favorites : GalleryScope

    /** 백업 칸에서 연 "백업된 사진" — 백업됨·안 됨·대기·실패를 세그먼트로(`docs/plans/backed-up-photos/spec.md`) */
    data object Backup : GalleryScope
}
