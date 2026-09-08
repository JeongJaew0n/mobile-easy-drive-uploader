package com.jjw.easygallery.core.data.upload.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 항목 ↔ 카테고리 할당(N:M). 카테고리가 지워지면 CASCADE 로 함께 사라진다.
 * mediaId 는 MediaStore `_ID` — 업로드 원장·해시 캐시와 같은 기준.
 */
@Entity(
    tableName = "media_category",
    primaryKeys = ["mediaId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("categoryId")],
)
data class MediaCategoryEntity(
    val mediaId: Long,
    val categoryId: Long,
    val assignedAt: Long,
)
