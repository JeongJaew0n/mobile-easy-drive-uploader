package com.jjw.easygallery.core.data.hidden

import com.jjw.easygallery.core.data.upload.db.HiddenMediaDao
import com.jjw.easygallery.core.data.upload.db.HiddenMediaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 숨긴 사진 목록(`docs/PHOTO_HIDING.md`). 파일은 건드리지 않으므로
 * `MediaActionController` 의 동의 흐름을 타지 않는다.
 */
@Singleton
class HiddenMediaRepository @Inject constructor(
    private val dao: HiddenMediaDao,
) {
    fun observeHiddenIds(): Flow<Set<Long>> = dao.observeIds().map { it.toSet() }

    suspend fun hide(mediaIds: Collection<Long>) {
        if (mediaIds.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.hide(mediaIds.map { HiddenMediaEntity(it, now) })
    }

    suspend fun unhide(mediaIds: Collection<Long>) {
        if (mediaIds.isNotEmpty()) dao.unhide(mediaIds.toList())
    }

    /** 갤러리에서 사라진 사진의 기록을 지운다. [aliveIds] 는 지금 MediaStore 에 있는 id */
    suspend fun prune(aliveIds: Set<Long>) {
        val gone = dao.ids().filterNot { it in aliveIds }
        if (gone.isNotEmpty()) dao.prune(gone)
    }
}
