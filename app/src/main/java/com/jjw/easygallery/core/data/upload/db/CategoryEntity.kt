package com.jjw.easygallery.core.data.upload.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 사용자 카테고리. 이름 유일성은 대소문자 무시 — Room 어노테이션으로 COLLATE NOCASE 유니크 인덱스를
 * 걸 수 없어 소문자 사본 [nameLower] 에 유니크 인덱스를 둔다. 설계: `docs/CATEGORIES.md` §3.
 */
@Entity(tableName = "category", indices = [Index(value = ["nameLower"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nameLower: String,
    val colorIndex: Int,
    val sortOrder: Int,
    val createdAt: Long,
)
