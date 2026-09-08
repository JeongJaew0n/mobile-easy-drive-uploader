package com.jjw.easygallery.feature.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 확대 상태. 값은 [Animatable] 이라 두 번 탭·손 뗀 뒤 보정이 스프링으로 움직인다.
 * 1배가 아니면 페이저 스와이프를 막아 팬 제스처와 충돌하지 않게 한다.
 * graphicsLayer(transformOrigin = 중앙) 기준이므로 offset 은 "확대된 콘텐츠 중심의 이동량"이다.
 */
internal class ZoomState {
    val scaleAnim = Animatable(1f)
    val offsetAnim = Animatable(Offset.Zero, Offset.VectorConverter)

    /** 경계 계산용 컨테이너 크기 — [Modifier.zoomable] 이 채운다 */
    var containerSize by mutableStateOf(IntSize.Zero)

    val scale: Float get() = scaleAnim.value
    val offset: Offset get() = offsetAnim.value
    val isZoomed: Boolean get() = abs(scale - 1f) > EPSILON

    /** 핀치 중: 즉시 반영. [centroid] 는 컨테이너 좌표(왼쪽 위 원점) */
    suspend fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val next = focalZoomOffset(offset, centroid - containerSize.center(), newScale / scale) + pan
        scaleAnim.snapTo(newScale)
        offsetAnim.snapTo(if (newScale <= MIN_SCALE) Offset.Zero else next)
    }

    /** 손을 뗀 뒤: 1배 미만이면 1배로, 경계 밖이면 안으로 스프링 */
    suspend fun settle(spec: AnimationSpec<Float>, offsetSpec: AnimationSpec<Offset>) {
        if (scale <= MIN_SCALE + EPSILON) {
            animateTo(MIN_SCALE, Offset.Zero, spec, offsetSpec)
            return
        }
        val clamped = clampOffset(offset, scale, containerSize)
        if (clamped != offset) offsetAnim.animateTo(clamped, offsetSpec)
    }

    /** 두 번 탭: 탭한 지점을 중심으로 확대, 이미 확대돼 있으면 1배로 */
    suspend fun toggle(tap: Offset, spec: AnimationSpec<Float>, offsetSpec: AnimationSpec<Offset>) {
        if (isZoomed) {
            animateTo(MIN_SCALE, Offset.Zero, spec, offsetSpec)
        } else {
            val target = focalZoomOffset(offset, tap - containerSize.center(), DOUBLE_TAP_SCALE / scale)
            animateTo(DOUBLE_TAP_SCALE, clampOffset(target, DOUBLE_TAP_SCALE, containerSize), spec, offsetSpec)
        }
    }

    private suspend fun animateTo(
        targetScale: Float,
        targetOffset: Offset,
        spec: AnimationSpec<Float>,
        offsetSpec: AnimationSpec<Offset>,
    ) {
        // 두 값을 동시에 움직이기 위해 별도 코루틴 없이 순차 호출 대신 병렬로
        kotlinx.coroutines.coroutineScope {
            launch { scaleAnim.animateTo(targetScale, spec) }
            launch { offsetAnim.animateTo(targetOffset, offsetSpec) }
        }
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 6f
        const val DOUBLE_TAP_SCALE = 2.5f
        private const val EPSILON = 0.01f
    }
}

@Composable
internal fun rememberZoomState(key: Any?): ZoomState = remember(key) { ZoomState() }

/**
 * 확대 배율이 [zoomFactor] 배 바뀔 때 [focal](컨테이너 중심 기준 좌표) 아래의 콘텐츠가 그 자리에 남도록 하는 offset.
 * 순수 함수 — 단위 테스트 대상.
 */
internal fun focalZoomOffset(offset: Offset, focal: Offset, zoomFactor: Float): Offset =
    (offset - focal) * zoomFactor + focal

/** 확대된 콘텐츠가 컨테이너 밖으로 빈 공간을 남기지 않도록 offset 을 경계 안으로 */
internal fun clampOffset(offset: Offset, scale: Float, container: IntSize): Offset {
    val maxX = ((container.width * scale - container.width) / 2f).coerceAtLeast(0f)
    val maxY = ((container.height * scale - container.height) / 2f).coerceAtLeast(0f)
    // coerceIn(-0f, 0f) 은 -0.0 을 낼 수 있어 Offset 동등성이 깨진다 → 0 을 더해 정규화
    return Offset(offset.x.coerceIn(-maxX, maxX) + 0f, offset.y.coerceIn(-maxY, maxY) + 0f)
}

private fun IntSize.center() = Offset(width / 2f, height / 2f)

/**
 * 핀치 확대·드래그 이동(즉시 반영), 손을 떼면 경계 보정, 두 번 탭은 탭 지점 중심 스프링 확대.
 * [onTap] 은 UI 토글용.
 */
internal fun Modifier.zoomable(
    state: ZoomState,
    scope: CoroutineScope,
    settleSpec: AnimationSpec<Float>,
    settleOffsetSpec: AnimationSpec<Offset>,
    onTap: () -> Unit,
): Modifier = this
    .onSizeChanged { state.containerSize = it }
    .pointerInput(state) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { tap -> scope.launch { state.toggle(tap, settleSpec, settleOffsetSpec) } },
        )
    }
    .pointerInput(state) {
        // detectTransformGestures 는 손을 뗀 시점을 알려주지 않아 직접 푼다
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var transformed = false
            do {
                val event = awaitPointerEvent()
                val changes = event.changes
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                val multiTouch = changes.size > 1
                // 1배에서 한 손가락 드래그는 페이저(좌우)·닫기(상하)에 넘긴다
                if (multiTouch || state.isZoomed) {
                    if (zoom != 1f || pan != Offset.Zero) {
                        transformed = true
                        val centroid = event.calculateCentroid()
                        scope.launch { state.transform(centroid, pan, zoom) }
                        changes.forEach { c -> if (c.positionChanged()) c.consume() }
                    }
                }
            } while (changes.any(PointerInputChange::pressed))
            if (transformed) scope.launch { state.settle(settleSpec, settleOffsetSpec) }
        }
    }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }
