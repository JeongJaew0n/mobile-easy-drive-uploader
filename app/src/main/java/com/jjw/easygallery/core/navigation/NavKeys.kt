package com.jjw.easygallery.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 앱의 모든 화면 키. Navigation 3 백스택에 저장되므로 @Serializable 필수. */
@Serializable
sealed interface AppNavKey : NavKey

/** 하단 "사진" 칸. 앱의 시작 화면 — 백스택 맨 아래에 늘 있다(`docs/plans/bottom-navigation/spec.md`) */
@Serializable
data object GalleryKey : AppNavKey

/** 하단 "앨범" 칸 */
@Serializable
data object AlbumsKey : AppNavKey

/** 하단 "백업" 칸 */
@Serializable
data object BackupKey : AppNavKey

/** 하단 "메뉴" 칸 */
@Serializable
data object MenuKey : AppNavKey

/**
 * 앨범 하나(또는 즐겨찾기)만 보는 갤러리. 앨범 칸에서 연다.
 * [relativePath] 가 있으면 그 앨범, [favorites] 면 즐겨찾기 — 둘 중 하나만 쓴다.
 */
@Serializable
data class AlbumKey(val relativePath: String? = null, val favorites: Boolean = false) : AppNavKey

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

/** 자동 태그(ML Kit 이미지 라벨링). `docs/AUTO_TAGGING.md` */
@Serializable
data object AutoTagKey : AppNavKey

/** 숨긴 사진. 들어가려면 PIN 이 필요하다. `docs/PHOTO_HIDING.md` */
@Serializable
data object HiddenKey : AppNavKey

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
    /** 숨긴 사진 화면에서 열었다. 이때는 **숨긴 것만** 보여준다(`docs/PHOTO_HIDING.md`) */
    val hiddenOnly: Boolean = false,
    /** 갤러리 탭(`GalleryTab.name`). null 이면 전체 — 고른 사진·카메라 탭에서 열면 그 탭 안에서만 넘긴다 */
    val tab: String? = null,
    /** 앨범 필터(`relativePath`). 앨범에서 열면 그 앨범 안에서만 넘긴다(`docs/plans/album-view/spec.md`) */
    val albumPath: String? = null,
    /** 백업된 사진 화면의 세그먼트(`BackupStatusFilter.name`). 그 목록 안에서, 같은 순서로 넘긴다 */
    val backupStatus: String? = null,
    /** 백업된 사진 화면의 저장소 칩(원장의 목적지). null 이면 모든 저장소 */
    val backupDestination: String? = null,
    val hero: HeroOrigin? = null,
) : AppNavKey

/** 백업 칸에서 연 "백업된 사진"(`docs/plans/backed-up-photos/spec.md`) — 갤러리 화면을 백업 범위로 */
@Serializable
data object BackedUpKey : AppNavKey

/**
 * 원격 저장소 탐색. [accountId] null 은 Google Drive. [folderId] null 이면 그 저장소의 루트.
 * 하위 폴더로 들어갈 때마다 새 키를 push 한다.
 */
@Serializable
data class DriveBrowserKey(
    val folderId: String? = null,
    val folderName: String? = null,
    val accountId: String? = null,
    /**
     * 보기 전용 폴더의 하위다(`docs/DRIVE_FILE_SCOPE.md` §10). 한 번 true 가 되면
     * 더 깊이 들어가도 계속 true — 읽기 권한은 안쪽으로 갈수록 넓어지지 않는다.
     */
    val readOnly: Boolean = false,
    /** 폴더 소유자. 남의 폴더면 상단 부제로 보인다 — 제목만으로는 내 "Easy Gallery" 와 같다 */
    val ownerEmail: String? = null,
) : AppNavKey

/**
 * Drive 사진 — 폴더를 가로지른 사진첩(`docs/plans/drive-photos/spec.md`). [openFileId] 가 있으면 그 사진의 넘겨 보기를
 * 바로 띄운다(백업 칸의 썸네일 띠).
 */
@Serializable
data class DrivePhotosKey(val openFileId: String? = null) : AppNavKey

/** 저장소 계정 추가(S3 호환 / WebDAV / SMB). [accountId] 가 있으면 그 계정 수정 */
@Serializable
data class AddRemoteAccountKey(val accountId: String? = null) : AppNavKey
