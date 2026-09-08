package com.jjw.easygallery.feature.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.ui.motion.LocalMotion

/** 그리드 썸네일과 같은 메모리 캐시 키 — 상세보기·히어로 오버레이가 원본을 받기 전까지 썸네일을 먼저 보여준다 */
internal fun thumbnailCacheKey(mediaId: Long): String = "thumb-$mediaId"

internal fun thumbnailCacheKey(item: MediaItem): String = thumbnailCacheKey(item.id)

@Composable
internal fun ImagePage(
    item: MediaItem,
    zoomState: ZoomState,
    onTap: () -> Unit,
) {
    val motion = LocalMotion.current
    val scope = rememberCoroutineScope()
    val context = LocalPlatformContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.uri)
                // 썸네일(이미 캐시) → 원본 순서로 표시해 회색 화면을 없앤다
                .placeholderMemoryCacheKey(thumbnailCacheKey(item))
                // 50MP 원본을 통째로 올리지 않도록 디코딩 상한. 6배 확대에서 약간 무뎌지는 것은 감수
                .size(MAX_DECODE_PX)
                .crossfade(false)
                .build(),
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .zoomable(
                    state = zoomState,
                    scope = scope,
                    settleSpec = motion.settle(),
                    settleOffsetSpec = motion.settle(),
                    onTap = onTap,
                ),
        )
    }
}

private const val MAX_DECODE_PX = 4096
