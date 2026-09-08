package com.jjw.easygallery.core.ui.image

import coil3.Extras
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.Options

/**
 * 이 요청을 시스템 썸네일([android.content.ContentResolver.loadThumbnail])로 처리하라는 표시.
 * 그리드·히어로처럼 작은 크기가 필요한 곳에만 붙인다. 상세보기의 원본 요청에는 붙이지 않는다 —
 * MediaProvider 는 요청 크기와 무관하게 캐시된 저해상도 썸네일을 돌려주기 때문이다.
 */
private val mediaStoreThumbnailKey = Extras.Key(default = false)

fun ImageRequest.Builder.mediaStoreThumbnail(): ImageRequest.Builder = apply {
    extras[mediaStoreThumbnailKey] = true
}

internal val Options.isMediaStoreThumbnail: Boolean
    get() = getExtra(mediaStoreThumbnailKey)
