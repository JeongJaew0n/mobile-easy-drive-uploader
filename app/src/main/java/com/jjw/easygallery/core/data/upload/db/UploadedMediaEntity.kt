package com.jjw.easygallery.core.data.upload.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Drive 에 올라간 항목의 영구 기록. 큐(`upload_tasks`)는 새 배치마다 완료 행을 지우므로
 * "이미 백업됨" 판단은 이 표로 한다. 자동 백업 중복 방지와 업로드됨 배지의 근거.
 */
@Entity(tableName = "uploaded_media")
data class UploadedMediaEntity(
    @PrimaryKey val mediaId: Long,
    val driveFileId: String,
    val folderId: String?,
    val uploadedAt: Long,
    /** 어느 계정에 올라갔는지. null = Google Drive */
    val accountId: String? = null,
)
