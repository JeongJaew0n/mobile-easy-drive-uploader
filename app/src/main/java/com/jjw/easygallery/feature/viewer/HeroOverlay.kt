package com.jjw.easygallery.feature.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.jjw.easygallery.core.navigation.HeroOrigin
import com.jjw.easygallery.core.ui.motion.LocalMotion
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 갤러리 썸네일 자리에서 상세보기 위치까지 확대되는 히어로 연출.
 * `SharedTransitionLayout` 대신 자체 오버레이를 쓰는 이유: 그것은 감싼 모든 화면을 매 레이아웃마다 두 번 측정하는 상시 비용이 있고,
 * 이 오버레이는 250ms 동안만 존재한다. 메모리 캐시의 썸네일(그리드와 같은 키)을 쓰므로 디코딩도 없다.
 * 시작·끝 모두 ContentScale.Crop — 끝 사각형이 원본 비율이라 Crop == Fit 이 되어 페이저의 이미지와 정확히 겹친다.
 */
@Composable
internal fun HeroOverlay(
    origin: HeroOrigin,
    mediaId: Long,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    val context = LocalPlatformContext.current
    val progress = remember { Animatable(0f) }
    val finished by rememberUpdatedState(onFinished)
    var containerOrigin by remember { mutableStateOf<Offset?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, motion.standard())
        finished()
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                containerOrigin = it.positionInWindow()
                containerSize = it.size
            },
    ) {
        val base = containerOrigin
        if (base != null && containerSize != IntSize.Zero) {
            // 윈도우 좌표 → 이 컨테이너 기준 좌표
            val start = Rect(
                left = origin.left - base.x,
                top = origin.top - base.y,
                right = origin.left - base.x + origin.width,
                bottom = origin.top - base.y + origin.height,
            )
            val end = fittedRect(containerSize, origin.imageWidth, origin.imageHeight)
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(origin.uri.toUri())
                    .memoryCacheKey(thumbnailCacheKey(mediaId))
                    .placeholderMemoryCacheKey(thumbnailCacheKey(mediaId))
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // progress 는 레이아웃 단계에서만 읽어 매 프레임 재구성 없이 재배치만 일어난다
                modifier = Modifier.layout { measurable, constraints ->
                    val rect = lerp(start, end, progress.value)
                    val width = rect.width.roundToInt().coerceAtLeast(1)
                    val height = rect.height.roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
                    }
                },
            )
        }
    }
}

/** 컨테이너 안에 [imageWidth]×[imageHeight] 를 비율 유지로 맞춘(가운데) 사각형. 크기를 모르면 컨테이너 전체 */
internal fun fittedRect(container: IntSize, imageWidth: Int, imageHeight: Int): Rect {
    val cw = container.width.toFloat()
    val ch = container.height.toFloat()
    if (imageWidth <= 0 || imageHeight <= 0) return Rect(0f, 0f, cw, ch)
    val scale = min(cw / imageWidth, ch / imageHeight)
    val w = imageWidth * scale
    val h = imageHeight * scale
    val left = (cw - w) / 2f
    val top = (ch - h) / 2f
    return Rect(left, top, left + w, top + h)
}
