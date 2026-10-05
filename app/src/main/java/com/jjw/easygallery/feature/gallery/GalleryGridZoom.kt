package com.jjw.easygallery.feature.gallery

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.jjw.easygallery.core.ui.motion.LocalMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 갤러리 칸 크기 단계(`docs/plans/ux-round2/spec.md` §6). 두 손가락으로 벌리면 커지고(단계 +1) 오므리면 작아진다.
 * 2 단계(100dp)가 예전의 고정 크기다.
 */
internal val CELL_SIZE_STEPS_DP = listOf(64, 80, 100, 130, 170)

/**
 * 한 번의 핀치가 끝났을 때 다음 단계. 손을 조금 벌린 정도로는 바뀌지 않게 문턱을 둔다 — 스크롤하다 손가락이 하나 더
 * 닿는 일이 흔하다. 한 번에 한 단계만 움직인다(크게 벌려도 한 칸 — 칸 크기를 애니메이션하지 않으므로 큰 점프는 길을 잃게 한다).
 */
internal fun nextCellStep(current: Int, zoom: Float): Int {
    val next = when {
        zoom >= ZOOM_IN_THRESHOLD -> current + 1
        zoom <= ZOOM_OUT_THRESHOLD -> current - 1
        else -> current
    }
    return next.coerceIn(0, CELL_SIZE_STEPS_DP.lastIndex)
}

/**
 * 두 번째 손가락이 닿았을 때만 듣는다 — 한 손가락(스크롤·길게 눌러 끌어 고르기)은 그대로 격자로 흘려 보낸다.
 * 두 손가락일 때는 먼저(Initial) 가로채 격자가 스크롤하지 않게 한다.
 */
internal fun Modifier.pinchToZoom(onPinchEnd: (zoom: Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        var zoom = 1f
        var pinched = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                pinched = true
                zoom *= event.calculateZoom()
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (pinched) onPinchEnd(zoom)
    }
}

/** 손잡이를 [fraction](0~1) 만큼 내렸을 때 갈 격자 index */
internal fun indexForFraction(fraction: Float, total: Int): Int =
    if (total <= 0) 0 else (fraction.coerceIn(0f, 1f) * (total - 1)).roundToInt()

/** [index] 가 속한 묶음의 머리글 index — 그 앞의 마지막 머리글. 없으면 null */
internal fun headerIndexFor(index: Int, headerIndexes: List<Int>): Int? = headerIndexes.lastOrNull { it <= index }

/**
 * 오른쪽 가장자리의 날짜 손잡이(`docs/plans/ux-round2/spec.md` §6). 스크롤하는 동안 나타나고, 끌면 목록 전체를 그 비율로
 * 건너뛰며 그 자리의 연·월을 말풍선으로 보인다. 6천 장을 손가락으로 튕겨 내려가는 대신 몇 해 전으로 바로 간다.
 *
 * [labels] 는 격자 index → 그 묶음의 이름(날짜 묶음은 "2025년 9월", 앱 묶음은 앱 이름).
 */
@Composable
internal fun DateScrollHandle(
    state: LazyGridState,
    totalItems: Int,
    headerIndexes: List<Int>,
    labelOf: (headerIndex: Int) -> String,
    modifier: Modifier = Modifier,
) {
    if (totalItems <= MIN_ITEMS_FOR_HANDLE) return
    val motion = LocalMotion.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // 스크롤이 멈추고 잠시 뒤에 숨긴다 — 바로 숨기면 잡으러 가는 사이 사라진다
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) {
            visible = true
        } else {
            delay(HIDE_DELAY_MILLIS)
            visible = false
        }
    }
    val fraction = if (dragging) {
        dragFraction
    } else {
        state.firstVisibleItemIndex.toFloat() / (totalItems - 1).coerceAtLeast(1)
    }
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val density = LocalDensity.current
        val thumbPx = with(density) { THUMB_HEIGHT_DP.dp.toPx() }
        val trackPx = (constraints.maxHeight - thumbPx).coerceAtLeast(1f)
        AnimatedVisibility(
            visible = visible,
            enter = motion.enterFade(),
            exit = motion.exitFade(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset { IntOffset(0, (fraction * trackPx).roundToInt()) },
            ) {
                if (dragging) {
                    val index = indexForFraction(fraction, totalItems)
                    headerIndexFor(index, headerIndexes)?.let { header ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text(
                                text = labelOf(header),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .padding(end = 2.dp)
                        .size(width = THUMB_WIDTH_DP.dp, height = THUMB_HEIGHT_DP.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(THUMB_WIDTH_DP.dp))
                        .pointerInput(totalItems, trackPx) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragFraction = fraction
                                    dragging = true
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, dy ->
                                change.consume()
                                dragFraction = (dragFraction + dy / trackPx).coerceIn(0f, 1f)
                                scope.launch { state.scrollToItem(indexForFraction(dragFraction, totalItems)) }
                            }
                        },
                )
            }
        }
    }
}

/** 날짜 묶음의 손잡이 말풍선 — 지금 언어의 "2025년 9월" / "September 2025" / "2025年9月" */
@Composable
internal fun rememberYearMonthFormatter(): DateTimeFormatter {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        val locale = ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale)
    }
}

private const val ZOOM_IN_THRESHOLD = 1.25f
private const val ZOOM_OUT_THRESHOLD = 0.8f
private const val MIN_ITEMS_FOR_HANDLE = 60
private const val HIDE_DELAY_MILLIS = 1_500L
private const val THUMB_WIDTH_DP = 6
private const val THUMB_HEIGHT_DP = 48
