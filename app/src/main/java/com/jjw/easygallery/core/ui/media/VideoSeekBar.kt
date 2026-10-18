package com.jjw.easygallery.core.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.core.ui.motion.LocalMotion
import kotlin.math.roundToInt

/**
 * 영상 재생바(`docs/plans/video-controls/spec.md`). 평소엔 얇은 흰 선과 작은 손잡이, 끄는 동안 손잡이가 커지고 위에 [scrubLabel]
 * 말풍선이 뜬다. 기본 Slider 는 굵은 막대·세로 막대기 손잡이라 영상 위에서 무겁다.
 *
 * 누른 자리로 바로 가고, 끄는 동안의 움직임은 모두 여기서 소비한다 — 페이저 넘기기·스와이프 닫기가 끼어들지 않는다.
 * 손이 닿는 높이는 [TOUCH_HEIGHT_DP] 로 넉넉하게, 그리는 선은 [TRACK_DP] 로 얇게.
 */
@Composable
fun VideoSeekBar(
    fraction: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 끄는 동안 말풍선에 보일 시각("0:05"). null 이면 말풍선 없음 */
    scrubLabel: String? = null,
    /** 접근성 — 화면 읽기 프로그램이 읽을 이름("재생 위치") */
    label: String? = null,
) {
    val motion = LocalMotion.current
    var scrubbing by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }
    val latestScrub by rememberUpdatedState(onScrub)
    val latestFinished by rememberUpdatedState(onScrubFinished)
    // 그리는 반지름 배율 — 레이아웃 크기가 아니라 그리기 단계만 바뀐다
    val thumbScale by animateFloatAsState(
        targetValue = if (scrubbing) THUMB_SCALE_SCRUBBING else 1f,
        animationSpec = motion.quick(),
        label = "seekThumb",
    )
    val shown = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TOUCH_HEIGHT_DP.dp)
            .onSizeChanged { widthPx = it.width }
            .semantics {
                label?.let { contentDescription = it }
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        latestScrub(target.coerceIn(0f, 1f))
                        latestFinished()
                        true
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    scrubbing = true
                    latestScrub(fractionAt(down.position.x, size.width))
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.positionChanged()) {
                            latestScrub(fractionAt(change.position.x, size.width))
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    scrubbing = false
                    latestFinished()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            val track = TRACK_DP.dp.toPx()
            val x = size.width * shown
            drawLine(
                color = Color.White.copy(alpha = INACTIVE_ALPHA),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = track,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White,
                start = Offset(0f, y),
                end = Offset(x, y),
                strokeWidth = track,
                cap = StrokeCap.Round,
            )
            if (enabled) drawCircle(Color.White, radius = THUMB_RADIUS_DP.dp.toPx() * thumbScale, center = Offset(x, y))
        }
        // 끄는 동안 손잡이 위의 시각
        AnimatedVisibility(
            visible = scrubbing && scrubLabel != null,
            enter = motion.enterFade(),
            exit = motion.exitFade(),
        ) {
            var bubbleWidth by remember { mutableIntStateOf(0) }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = BUBBLE_ALPHA),
                contentColor = Color.White,
                modifier = Modifier
                    .onSizeChanged { bubbleWidth = it.width }
                    .offset {
                        val center = (widthPx * shown).roundToInt()
                        val left = (center - bubbleWidth / 2).coerceIn(0, (widthPx - bubbleWidth).coerceAtLeast(0))
                        IntOffset(left, -BUBBLE_RISE_DP.dp.roundToPx())
                    },
            ) {
                Text(
                    text = scrubLabel.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 가로 위치 → 0..1. 폭을 모르면(첫 배치 전) 0 */
internal fun fractionAt(x: Float, width: Int): Float = if (width <= 0) 0f else (x / width).coerceIn(0f, 1f)

private const val TOUCH_HEIGHT_DP = 36
private const val TRACK_DP = 3
private const val THUMB_RADIUS_DP = 6
private const val THUMB_SCALE_SCRUBBING = 1.6f
private const val INACTIVE_ALPHA = 0.3f
private const val BUBBLE_ALPHA = 0.7f
private const val BUBBLE_RISE_DP = 36
