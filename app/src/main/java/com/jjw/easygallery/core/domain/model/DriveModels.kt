package com.jjw.easygallery.core.domain.model

data class DriveFolder(
    val id: String,
    val name: String,
    /** 소유자 이메일(나일 수도 있다). null 은 모른다. 이름이 같은 폴더를 가르는 데 쓴다 */
    val ownerEmail: String? = null,
)

data class DriveAccount(
    val email: String,
    val displayName: String?,
    val storageUsedBytes: Long?,
    val storageLimitBytes: Long?,
)

/** Drive 폴더 안의 한 항목(폴더 또는 파일). */
data class DriveEntry(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedTimeMillis: Long?,
    val webViewLink: String?,
    /**
     * 보기 전용으로 추가한 폴더다(`docs/DRIVE_FILE_SCOPE.md` §10). 읽기 권한뿐이라
     * 이 안에서는 올리기·만들기·고치기·지우기가 없다. 루트 목록에서만 true 가 된다.
     */
    val readOnly: Boolean = false,
    /**
     * 소유자 이메일(나일 수도 있다). null 은 모른다. "내 계정" 인지는 화면이 연결 이메일과 비교해 정한다
     * (`docs/plans/guest-account-upload/spec.md` §6).
     */
    val ownerEmail: String? = null,
    /** 서버가 만든 썸네일 주소(몇 시간 뒤 만료). 없으면 아이콘으로 보인다 */
    val thumbnailLink: String? = null,
    /** 썸네일 캐시 키. 링크는 바뀌어도 이 값이 같으면 같은 그림이다 */
    val thumbnailVersion: String? = null,
    /** Drive 에 생긴(= 올린) 시각. Drive 사진 화면만 받는다 */
    val createdTimeMillis: Long? = null,
    /** 찍은 시각(사진 EXIF). 없으면 null — 영상·EXIF 없는 사진 */
    val takenTimeMillis: Long? = null,
    /** 영상 길이 */
    val durationMillis: Long? = null,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME_TYPE
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    fun toFolder() = DriveFolder(id, name, ownerEmail)

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val ROOT_ID = "root"
    }
}

data class DrivePage(
    val entries: List<DriveEntry>,
    val nextPageToken: String?,
)

/**
 * Drive 사진 화면이 어디까지 보나(`docs/plans/drive-photos/spec.md` §4).
 * 앱이 올린 것은 고칠 수 있고, [WholeDrive]·보기 전용 폴더는 읽기 권한으로만 보인다.
 */
sealed interface DriveMediaScope {
    /** 이 앱이 올린 것(기본) */
    data object AppUploads : DriveMediaScope

    /** 폴더 하나의 바로 아래. 하위 폴더는 들어가지 않는다 */
    data class Folder(val id: String, val name: String, val readOnly: Boolean) : DriveMediaScope

    /** Drive 전체 — 읽기 권한(`drive.readonly`)이 있을 때만 */
    data object WholeDrive : DriveMediaScope

    val isReadOnly: Boolean get() = this is WholeDrive || (this is Folder && readOnly)
}

/** Drive 사진 순서. [TAKEN] 은 서버가 정렬하지 못해 끝까지 읽는다 */
enum class DriveMediaOrder { UPLOADED, TAKEN }
