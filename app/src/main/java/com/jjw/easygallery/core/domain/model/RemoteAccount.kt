package com.jjw.easygallery.core.domain.model

/** 연결된 원격 저장소 종류. `docs/MULTI_CLOUD.md` §1 */
enum class RemoteAccountKind {
    GOOGLE_DRIVE,
    S3,
    WEBDAV,
    SMB,
}

/**
 * 원격 저장소 계정(비밀 제외). 비밀은 [id] 를 키로 `SecretStore` 에 따로 있다.
 *
 * @property endpoint S3: `https://kr.object.ncloudstorage.com`, WebDAV: `https://nas:5006/photos`
 * @property region S3 서명용(Naver `kr-standard`, KT `kr-central-1`, AWS `ap-northeast-2`, R2 `auto`)
 * @property bucketOrRoot S3: 버킷 이름, WebDAV: 루트 경로(endpoint 에 포함돼 있으면 빈 문자열)
 * @property username S3: Access Key, WebDAV: 사용자 이름
 */
data class RemoteAccount(
    val id: String,
    val kind: RemoteAccountKind,
    val displayName: String,
    val endpoint: String = "",
    val region: String? = null,
    val bucketOrRoot: String? = null,
    val username: String? = null,
    /** WebDAV 자체 서명 인증서 지문(SHA-256, 소문자 hex). 있으면 이 인증서만 신뢰한다(`docs/NAS_STORAGE.md` §2) */
    val certSha256: String? = null,
    val createdAt: Long = 0,
) {
    companion object {
        /** Google Drive 는 Play 서비스가 토큰을 관리하므로 계정 행이 없다. null accountId 가 이 값을 뜻한다 */
        const val GOOGLE_DRIVE_ID = "google-drive"
    }
}

/** 제공자가 지원하는 동작. UI 는 이 집합을 보고 메뉴를 바꾼다 */
enum class Capability {
    /** 삭제가 휴지통으로 가고 복원할 수 있다 */
    TRASH,

    RENAME,

    MOVE,

    /** 폴더도 이름 변경·이동할 수 있다(S3 는 오브젝트 전부 복사가 필요해 없음) */
    FOLDER_MUTATION,

    /** 중단된 업로드를 이어 올릴 수 있다 */
    RESUMABLE_UPLOAD,

    /** 용량 정보를 준다 */
    QUOTA,

    /** 파일을 브라우저/앱에서 열 수 있는 링크가 있다 */
    WEB_LINK,
}

data class RemoteAccountInfo(
    val displayName: String,
    val detail: String?,
    val storageUsedBytes: Long? = null,
    val storageLimitBytes: Long? = null,
)
