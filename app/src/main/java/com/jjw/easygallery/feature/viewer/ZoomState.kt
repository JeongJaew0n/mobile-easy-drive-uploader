package com.jjw.easygallery.feature.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/** 확대 상태. 1배가 아니면 페이저 스와이프를 막아 팬 제스처와 충돌하지 않게 한다. */
internal class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    val isZoomed: Boolean get() = abs(scale - 1f) > EPSILON

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    fun toggle() {
        if (isZoomed) reset() else scale = DOUBLE_TAP_SCALE
    }

    private companion object {
        const val EPSILON = 0.01f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

@Composable
internal fun rememberZoomState(key: Any?): ZoomState = remember(key) { ZoomState() }

/** 핀치 확대·드래그 이동, 두 번 탭으로 확대 토글. [onTap] 은 1배일 때만 UI 토글용으로 쓴다. */
internal fun Modifier.zoomable(state: ZoomState, onTap: () -> Unit): Modifier = this
    .pointerInput(state) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { state.toggle() },
        )
    }
    .pointerInput(state) {
        detectTransformGestures { _, pan, zoom, _ ->
            state.scale = (state.scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
            state.offset = if (state.isZoomed) state.offset + pan else Offset.Zero
        }
    }
    .graphicsLayer(
        scaleX = state.scale,
        scaleY = state.scale,
        translationX = state.offset.x,
        translationY = state.offset.y,
    )

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
