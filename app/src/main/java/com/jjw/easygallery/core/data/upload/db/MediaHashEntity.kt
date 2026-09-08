package com.jjw.easygallery.core.data.upload.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 파일 내용 해시 캐시. 크기·수정 시각이 같으면 다시 계산하지 않는다. */
@Entity(tableName = "media_hash", indices = [Index("sha256")])
data class MediaHashEntity(
    @PrimaryKey val mediaId: Long,
    val sizeBytes: Long,
    val dateModifiedSeconds: Long,
    val sha256: String,
    val hashedAt: Long,
)
