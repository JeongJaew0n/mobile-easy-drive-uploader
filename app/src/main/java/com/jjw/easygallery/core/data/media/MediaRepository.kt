package com.jjw.easygallery.core.data.media

import com.jjw.easygallery.core.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    /** 기기 갤러리의 사진·영상을 최신순으로 관찰한다. MediaStore 변경 시 재발행. */
    fun observeMedia(): Flow<List<MediaItem>>
}
