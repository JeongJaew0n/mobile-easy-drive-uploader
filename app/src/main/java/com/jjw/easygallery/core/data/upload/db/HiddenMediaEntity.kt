package com.jjw.easygallery.core.data.upload.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 숨긴 사진(`docs/PHOTO_HIDING.md`). **파일은 건드리지 않는다** — 우리 목록에서 뺄 뿐이다.
 * 휴지통(MediaStore `IS_TRASHED`)과 달리 기기 전체에 적용되지 않는다.
 */
@Entity(tableName = "hidden_media")
data class HiddenMediaEntity(
    @PrimaryKey val mediaId: Long,
    val hiddenAt: Long,
)
