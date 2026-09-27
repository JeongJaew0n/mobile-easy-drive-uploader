package com.jjw.easygallery.core.data.chosen

import com.jjw.easygallery.core.data.upload.db.ChosenMediaDao
import com.jjw.easygallery.core.data.upload.db.ChosenMediaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 고른 사진 목록(`docs/plans/chosen-photos-tab/spec.md`). 파일은 건드리지 않으므로
 * `MediaActionController` 의 동의 흐름을 타지 않는다.
 */
@Singleton
class ChosenMediaRepository @Inject constructor(
    private val dao: ChosenMediaDao,
) {
    fun observeChosenIds(): Flow<Set<Long>> = dao.observeIds().map { it.toSet() }

    suspend fun choose(mediaIds: Collection<Long>) {
        if (mediaIds.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.choose(mediaIds.map { ChosenMediaEntity(it, now) })
    }

    suspend fun unchoose(mediaIds: Collection<Long>) {
        if (mediaIds.isNotEmpty()) dao.unchoose(mediaIds.toList())
    }
}
