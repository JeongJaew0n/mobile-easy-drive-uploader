package com.jjw.easygallery.feature.gallery

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 길게 눌러 선택을 시작하고, 손가락을 끌면 지나간 범위를 한 번에 선택한다.
 * - 시작 항목이 이미 선택돼 있었으면 범위를 해제한다 (Google 포토 방식)
 * - 가장자리에 닿으면 자동 스크롤하며 선택을 이어간다
 *
 * @param entryIds 그리드 인덱스 순서의 항목 ID (헤더는 null). 범위 계산과 비가시 항목 포함에 쓴다.
 */
@Composable
internal fun Modifier.dragSelect(
    state: LazyGridState,
    entryIds: List<Long?>,
    selectedIds: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val currentEntryIds by rememberUpdatedState(entryIds)
    val currentSelected by rememberUpdatedState(selectedIds)
    val currentOnChange by rememberUpdatedState(onSelectionChange)
    val session = remember { DragSelectSession() }
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }

    // 가장자리 자동 스크롤: 손가락이 멈춰 있어도 아래로 흘러가는 항목이 계속 선택되도록 매 틱 재계산
    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed == 0f) return@LaunchedEffect
        while (isActive) {
            state.scrollBy(autoScrollSpeed)
            session.lastPosition?.let { session.update(state, it, currentEntryIds, currentSelected, currentOnChange) }
            delay(AUTO_SCROLL_TICK_MILLIS)
        }
    }

    return this.pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val index = state.indexAt(offset) ?: return@detectDragGesturesAfterLongPress
                val id = currentEntryIds.getOrNull(index) ?: return@detectDragGesturesAfterLongPress
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                session.start(index, id, currentSelected)
                session.update(state, offset, currentEntryIds, currentSelected, currentOnChange)
            },
            onDrag = { change, _ ->
                if (!session.isActive) return@detectDragGesturesAfterLongPress
                session.update(state, change.position, currentEntryIds, currentSelected, currentOnChange)
                autoScrollSpeed = edgeScrollSpeed(change.position.y, size.height)
            },
            onDragEnd = {
                session.stop()
                autoScrollSpeed = 0f
            },
            onDragCancel = {
                session.stop()
                autoScrollSpeed = 0f
            },
        )
    }
}

/** 드래그 한 번의 상태. 시작 시점의 선택을 기준(base)으로 범위를 더하거나 뺀다. */
private class DragSelectSession {
    private var startIndex = -1
    private var baseSelection: Set<Long> = emptySet()
    private var removing = false
    private var lastIndex = -1
    var lastPosition: Offset? = null
        private set

    val isActive: Boolean get() = startIndex >= 0

    fun start(index: Int, id: Long, selected: Set<Long>) {
        startIndex = index
        lastIndex = index
        baseSelection = selected
        removing = id in selected
    }

    fun stop() {
        startIndex = -1
        lastIndex = -1
        lastPosition = null
    }

    fun update(
        state: LazyGridState,
        position: Offset,
        entryIds: List<Long?>,
        current: Set<Long>,
        onChange: (Set<Long>) -> Unit,
    ) {
        if (!isActive) return
        lastPosition = position
        val index = state.indexAt(position) ?: return
        if (index == lastIndex && current.isNotEmpty()) return
        lastIndex = index
        val range = minOf(startIndex, index)..maxOf(startIndex, index)
        val rangeIds = range.mapNotNull { entryIds.getOrNull(it) }
        val next = if (removing) baseSelection - rangeIds.toSet() else baseSelection + rangeIds
        if (next != current) onChange(next)
    }
}

/** 포인터 위치에 있는 그리드 항목의 인덱스. 헤더 위면 그 인덱스(호출 측에서 null ID 로 걸러짐). */
private fun LazyGridState.indexAt(position: Offset): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.contains(position) }?.index

private fun androidx.compose.foundation.lazy.grid.LazyGridItemInfo.contains(p: Offset): Boolean =
    offset.contains(size, p)

private fun IntOffset.contains(size: IntSize, p: Offset): Boolean =
    p.x >= x && p.x < x + size.width && p.y >= y && p.y < y + size.height

/** 상·하단 가장자리에 가까울수록 빠르게. 안쪽이면 0. */
private fun edgeScrollSpeed(y: Float, height: Int): Float {
    val threshold = height * EDGE_FRACTION
    return when {
        y < threshold -> -((threshold - y) / threshold) * MAX_SCROLL_PX_PER_TICK
        y > height - threshold -> ((y - (height - threshold)) / threshold) * MAX_SCROLL_PX_PER_TICK
        else -> 0f
    }
}

private const val EDGE_FRACTION = 0.12f
private const val MAX_SCROLL_PX_PER_TICK = 24f
private const val AUTO_SCROLL_TICK_MILLIS = 10L
