package com.jjw.easygallery.core.domain.model

/**
 * 원장 한 줄 — 사진 하나가 한 곳에 올라간 기록(`docs/plans/backed-up-photos/spec.md` §3).
 * [destination] 은 `drive:<이메일>` 또는 `remote:<accountId>`. [remoteId] 는 그곳의 파일 ID(Drive) 또는 경로·키.
 */
data class UploadRecord(
    val mediaId: Long,
    val destination: String,
    val remoteId: String,
    val folderId: String?,
    val uploadedAt: Long,
    val accountId: String?,
)

/**
 * 목적지와 화면에 보일 이름. [driveEmail] 이 있으면 Google Drive 다 — 이름은 화면이 붙인다
 * (연결 계정이면 "Google Drive", 다른 계정이면 "Google Drive · 이메일").
 */
data class BackupDestination(
    val destination: String,
    /** 다른 저장소의 이름(사용자가 정한 것). Drive 면 null */
    val remoteName: String?,
    /** Drive 면 그 계정. 주인 미정 옛 기록은 빈 문자열 */
    val driveEmail: String?,
    /** 연결된 Google 계정의 Drive 인가 — "Drive 에서 열기" 를 이 계정으로 연다 */
    val isPrimaryDrive: Boolean,
) {
    val isDrive: Boolean get() = driveEmail != null
}
