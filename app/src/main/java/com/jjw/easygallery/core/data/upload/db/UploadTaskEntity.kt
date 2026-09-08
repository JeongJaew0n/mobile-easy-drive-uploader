package com.jjw.easygallery.core.data.upload.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jjw.easygallery.core.domain.model.UploadState

@Entity(
    tableName = "upload_tasks",
    indices = [Index("state"), Index("mediaId")],
)
data class UploadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val folderId: String?,
    val folderName: String?,
    val state: UploadState = UploadState.PENDING,
    val sessionUri: String? = null,
    val bytesUploaded: Long = 0,
    val driveFileId: String? = null,
    val errorMessage: String? = null,
    val attemptCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val width: Int = 0,
    @ColumnInfo(defaultValue = "0") val height: Int = 0,
)
