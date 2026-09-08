package com.jjw.easygallery.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 앱의 모든 화면 키. Navigation 3 백스택에 저장되므로 @Serializable 필수. */
@Serializable
sealed interface AppNavKey : NavKey

@Serializable
data object GalleryKey : AppNavKey

@Serializable
data object SettingsKey : AppNavKey

@Serializable
data object UploadQueueKey : AppNavKey

@Serializable
data object TrashKey : AppNavKey

@Serializable
data object AutoBackupKey : AppNavKey

@Serializable
data object DuplicatesKey : AppNavKey

/** 카테고리 관리(생성·이름/색 변경·삭제·정렬) */
@Serializable
data object CategoriesKey : AppNavKey

/**
 * 탭한 썸네일의 화면(윈도우) 좌표와 원본 정보. 상세보기가 이 사각형에서 확대되는 히어로 연출을 그린다.
 * 회전·복원 시 좌표가 어긋날 수 있어 상세보기는 첫 진입 1회만 사용하고 이후 무시한다.
 */
@Serializable
data class HeroOrigin(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val uri: String,
    val imageWidth: Int,
    val imageHeight: Int,
)

/** 사진·영상 상세보기. 갤러리에서 보던 필터(즐겨찾기·기간·카테고리)를 이어받아 좌우 스와이프 범위를 맞춘다. */
@Serializable
data class MediaViewerKey(
    val mediaId: Long,
    val favoritesOnly: Boolean = false,
    val startEpochDay: Long? = null,
    val endEpochDay: Long? = null,
    /** 카테고리 필터(OR). null 이면 없음 */
    val categoryIds: List<Long>? = null,
    val uncategorizedOnly: Boolean = false,
    val hero: HeroOrigin? = null,
) : AppNavKey

/**
 * 원격 저장소 탐색. [accountId] null 은 Google Drive. [folderId] null 이면 그 저장소의 루트.
 * 하위 폴더로 들어갈 때마다 새 키를 push 한다.
 */
@Serializable
data class DriveBrowserKey(
    val folderId: String? = null,
    val folderName: String? = null,
    val accountId: String? = null,
) : AppNavKey

/** 저장소 계정 추가(S3 호환 / WebDAV) */
@Serializable
data object AddRemoteAccountKey : AppNavKey
