package com.jjw.easygallery.core.data.media

import android.content.Context
import com.jjw.easygallery.core.common.di.AppDispatcher
import com.jjw.easygallery.core.common.di.Dispatcher
import com.jjw.easygallery.core.domain.model.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("UnusedPrivateProperty") // MediaStore 쿼리 구현 시 사용
class MediaStoreRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : MediaRepository {

    // TODO(gallery): ContentResolver + ContentObserver 기반 MediaStore 쿼리로 교체
    override fun observeMedia(): Flow<List<MediaItem>> = flowOf(emptyList())
}
