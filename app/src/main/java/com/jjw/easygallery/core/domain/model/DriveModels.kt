package com.jjw.easygallery.core.domain.model

data class DriveFolder(
    val id: String,
    val name: String,
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
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME_TYPE
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    fun toFolder() = DriveFolder(id, name)

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val ROOT_ID = "root"
    }
}

data class DrivePage(
    val entries: List<DriveEntry>,
    val nextPageToken: String?,
)
