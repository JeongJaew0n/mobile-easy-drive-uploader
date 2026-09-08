package com.jjw.easygallery.core.data.upload.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 연결된 원격 저장소 계정(비밀 제외 — 비밀은 SecretStore 에 [secretRef] 로). `docs/MULTI_CLOUD.md` §3 */
@Entity(tableName = "remote_account")
data class RemoteAccountEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val displayName: String,
    val endpoint: String,
    val region: String?,
    val bucketOrRoot: String?,
    val username: String?,
    val secretRef: String,
    val createdAt: Long,
)
