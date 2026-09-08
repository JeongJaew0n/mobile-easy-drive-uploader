package com.jjw.easygallery.feature.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 아래로 끌어 닫기. 끌수록 작아지고 투명해지며, 임계값을 넘겨 놓으면 [onDismiss], 아니면 스프링으로 복귀.
 * [enabled] 가 false(확대 중)면 제스처를 받지 않는다.
 */
internal fun Modifier.swipeToDismiss(
    offset: Animatable<Float, *>,
    scope: CoroutineScope,
    enabled: Boolean,
    settleSpec: AnimationSpec<Float>,
    onDismiss: () -> Unit,
): Modifier {
    val progress = (offset.value / DISMISS_DISTANCE_PX).coerceIn(0f, 1f)
    return this
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectVerticalDragGestures(
                onVerticalDrag = { change, dragAmount ->
                    val next = (offset.value + dragAmount).coerceAtLeast(0f)
                    if (next > 0f || dragAmount > 0f) {
                        change.consume()
                        scope.launch { offset.snapTo(next) }
                    }
                },
                onDragEnd = {
                    if (offset.value > DISMISS_THRESHOLD_PX) {
                        onDismiss()
                    } else {
                        scope.launch { offset.animateTo(0f, settleSpec) }
                    }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f, settleSpec) } },
            )
        }
        .graphicsLayer {
            translationY = offset.value
            val s = 1f - progress * DISMISS_SCALE_RANGE
            scaleX = s
            scaleY = s
            alpha = 1f - progress * DISMISS_ALPHA_RANGE
        }
}

/** 이 거리에서 축소·투명도가 최대, 임계값을 넘겨 놓으면 닫힌다 */
private const val DISMISS_DISTANCE_PX = 900f
private const val DISMISS_THRESHOLD_PX = 220f
private const val DISMISS_SCALE_RANGE = 0.25f
private const val DISMISS_ALPHA_RANGE = 0.6f
