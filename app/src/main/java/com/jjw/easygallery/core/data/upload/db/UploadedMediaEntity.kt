package com.jjw.easygallery.core.data.upload.db

import androidx.room.Entity
import androidx.room.Index

/**
 * 올라간 항목의 영구 기록. 큐(`upload_tasks`)는 새 배치마다 완료 행을 지우므로
 * "이미 백업됨" 판단은 이 표로 한다. 자동 백업 중복 방지와 업로드됨 배지의 근거.
 *
 * 키가 `(mediaId, destination)` 이라 **한 사진이 여러 곳에** 올라간 것을 모두 남긴다.
 * 스키마 11 까지는 `mediaId` 만 키라서, 다른 곳에 올리면 앞 기록이 덮어써졌다
 * (`docs/plans/ledger-per-account/spec.md` §1).
 */
@Entity(
    tableName = "uploaded_media",
    primaryKeys = ["mediaId", "destination"],
    indices = [Index("destination")],
)
data class UploadedMediaEntity(
    val mediaId: Long,
    /**
     * 어디로 올렸나. Drive 는 `drive:<이메일>`, 다른 저장소는 `remote:<accountId>`.
     * [destinationFor] 로만 만든다 — 규칙이 흩어지면 같은 곳을 두 이름으로 적게 된다.
     */
    val destination: String,
    val driveFileId: String,
    val folderId: String?,
    val uploadedAt: Long,
    /** 어느 저장소 계정에 올라갔는지. null = Google Drive. 조회는 [destination] 으로 한다 */
    val accountId: String? = null,
)

/** 스키마 11 이전 Drive 기록 — 누가 올렸는지 적혀 있지 않다. 처음 보이는 연결 계정이 가져간다 */
const val UNCLAIMED_DRIVE_DESTINATION = "drive:"

/**
 * `destination` 을 만드는 유일한 자리.
 *
 * @param accountId 저장소 계정. null 또는 [driveAccountId] 면 Google Drive
 * @param driveEmail 지금 연결된 Google 계정. Drive 인데 null 이면 목적지를 정할 수 없다
 * @return 정할 수 없으면 null — 그때 Drive 기록은 "없다" 로 본다
 */
fun destinationFor(accountId: String?, driveEmail: String?, driveAccountId: String): String? =
    if (accountId == null || accountId == driveAccountId) {
        driveEmail?.let { "drive:$it" }
    } else {
        "remote:$accountId"
    }
