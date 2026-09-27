package com.jjw.easygallery.core.data.upload.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 고른 사진(`docs/plans/chosen-photos-tab/spec.md`). 갤러리 첫 탭에 보일 것만 모아 둔다.
 * **파일은 건드리지 않는다** — 즐겨찾기(MediaStore `IS_FAVORITE`)와 달리 다른 갤러리 앱에는 보이지 않는다.
 */
@Entity(tableName = "chosen_media")
data class ChosenMediaEntity(
    @PrimaryKey val mediaId: Long,
    val chosenAt: Long,
)
