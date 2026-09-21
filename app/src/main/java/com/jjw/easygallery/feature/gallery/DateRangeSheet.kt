package com.jjw.easygallery.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.DatePreset
import com.jjw.easygallery.core.domain.model.DateRange
import com.kizitonwose.calendar.compose.CalendarState
import com.kizitonwose.calendar.compose.VerticalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.CalendarMonth
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * 기간 선택 바텀시트. 위쪽 빠른 선택 칩, 아래 세로 달력(가장 오래된 사진의 달 ~ 이번 달).
 * 사진이 있는 날만 탭할 수 있고 아래에 점이 붙는다. 시작일 → 종료일 순으로 탭한다([DateRangeSelection]).
 * 배경·근거: `docs/DATE_RANGE_PICKER.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRangeSheet(
    current: DateRange?,
    dayCounts: Map<LocalDate, Int>,
    onDismiss: () -> Unit,
    onConfirm: (DateRange?) -> Unit,
) {
    val today = remember { LocalDate.now() }
    var selection by remember { mutableStateOf(DateRangeSelection.of(current)) }
    val selected = selection.toDateRange()
    val thisMonth = remember(today) { YearMonth.from(today) }
    val startMonth = remember(dayCounts, thisMonth) {
        dayCounts.keys.minOrNull()?.let(YearMonth::from)?.coerceAtMost(thisMonth) ?: thisMonth
    }
    val firstDayOfWeek = remember { firstDayOfWeekFromLocale() }
    val calendarState = rememberCalendarState(
        startMonth = startMonth,
        endMonth = thisMonth,
        firstVisibleMonth = current?.start?.let(YearMonth::from)?.coerceIn(startMonth, thisMonth) ?: thisMonth,
        firstDayOfWeek = firstDayOfWeek,
    )
    val scope = rememberCoroutineScope()

    // 반만 펼친 상태면 달력이 잘리고 적용 버튼이 화면 밖으로 나간다 → 처음부터 전체 펼침
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            SheetHeader(hasCurrent = current != null, onClear = { onConfirm(null) })
            PresetChips(
                today = today,
                selected = selected,
                onPick = { range ->
                    selection = DateRangeSelection.of(range)
                    scope.launch {
                        calendarState.animateScrollToMonth(YearMonth.from(range.start).coerceIn(startMonth, thisMonth))
                    }
                },
            )
            var jumpOpen by remember { mutableStateOf(false) }
            // `firstVisibleMonth` 는 헤더 **안에서만** 읽는다. 여기서 읽으면 달력을 넘길 때마다
            // 시트 전체가 다시 그려진다(dayCounts 가 불안정 타입이라 달력도 건너뛰지 못한다).
            MonthJumpHeader(
                state = calendarState,
                expanded = jumpOpen,
                onToggle = { jumpOpen = !jumpOpen },
            )
            HorizontalDivider(Modifier.padding(horizontal = CALENDAR_HORIZONTAL_PADDING_DP.dp))
            // 패널은 달력 "자리를 대신" 쓴다. 위에 쌓으면 달력 높이(400dp 고정) 때문에
            // 적용 버튼이 화면 밖으로 밀린다.
            if (jumpOpen) {
                MonthJumpPanel(
                    dayCounts = dayCounts,
                    // 패널이 열려 있는 동안엔 달력이 없어 이 값이 변하지 않는다
                    initialYear = calendarState.firstVisibleMonth.yearMonth.year,
                    startMonth = startMonth,
                    endMonth = thisMonth,
                    onPick = { target ->
                        jumpOpen = false
                        scope.launch { calendarState.animateScrollToMonth(target) }
                    },
                    // 달력과 같은 이유로 남는 높이만 쓴다. 고정 높이로 두면 가로 화면에서
                    // 월 그리드 마지막 줄과 적용 버튼이 잘린다(실기기 확인).
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                WeekdayHeader(firstDayOfWeek)
                RangeCalendar(
                    state = calendarState,
                    dayCounts = dayCounts,
                    selection = selection,
                    onDayClick = { selection = selection.select(it) },
                    // 세로에서는 400dp, 가로처럼 낮은 화면에서는 남는 만큼만 쓴다.
                    // 고정 높이로 두면 적용·취소 행이 화면 밖으로 밀린다.
                    modifier = Modifier
                        .heightIn(max = CALENDAR_HEIGHT_DP.dp)
                        .weight(1f, fill = false),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = { selected?.let(onConfirm) }, enabled = selected != null) {
                    Text(stringResource(R.string.action_apply))
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(hasCurrent: Boolean, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.gallery_date_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.gallery_date_sheet_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hasCurrent) {
            TextButton(onClick = onClear) { Text(stringResource(R.string.gallery_date_clear)) }
        }
    }
}

@Composable
private fun PresetChips(today: LocalDate, selected: DateRange?, onPick: (DateRange) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DatePreset.entries.forEach { preset ->
            val range = preset.toRange(today)
            FilterChip(
                selected = selected == range,
                onClick = { onPick(range) },
                label = { Text(stringResource(preset.labelRes())) },
            )
        }
    }
}

@Composable
private fun WeekdayHeader(firstDayOfWeek: DayOfWeek) {
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) { ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault() }
    Row(Modifier.padding(horizontal = CALENDAR_HORIZONTAL_PADDING_DP.dp)) {
        daysOfWeek(firstDayOfWeek).forEach { day ->
            Text(
                text = day.getDisplayName(TextStyle.NARROW, locale),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RangeCalendar(
    state: CalendarState,
    dayCounts: Map<LocalDate, Int>,
    selection: DateRangeSelection,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    VerticalCalendar(
        state = state,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CALENDAR_HORIZONTAL_PADDING_DP.dp),
        monthHeader = { month -> MonthHeader(month) },
        dayContent = { day ->
            // 사진이 없는 날도 고를 수 있다. 없다는 것은 점이 없고 흐린 것으로 알린다 —
            // 막아 두면 "9월 1일부터" 같은 기간을 아예 지정할 수 없다.
            DayCell(
                day = day,
                count = dayCounts[day.date] ?: 0,
                selection = selection,
                onClick = onDayClick,
            )
        },
    )
}

@Composable
private fun MonthHeader(month: CalendarMonth) {
    Text(
        text = stringResource(R.string.gallery_date_month_label, month.yearMonth.year, month.yearMonth.monthValue),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * 날짜 한 칸. 범위 안이면 띠(시작·끝은 둥글게), 시작·끝은 원. 사진 없는 날·미래는 흐리게.
 * 셀이 많아(42 × 월 수) `if` 로 넣고 빼는 대신 배경색만 바꾼다 — 애니메이션 없음.
 */
@Composable
private fun BoxScope.DayCell(
    day: CalendarDay,
    count: Int,
    selection: DateRangeSelection,
    onClick: (LocalDate) -> Unit,
) {
    if (day.position != DayPosition.MonthDate) {
        Box(Modifier.aspectRatio(1f))
        return
    }
    val date = day.date
    val style = dayCellStyle(MaterialTheme.colorScheme, date, count, selection)
    val description = if (count > 0) {
        stringResource(R.string.gallery_date_day_with_count, date.dayOfMonth, count)
    } else {
        stringResource(R.string.gallery_date_day_empty, date.dayOfMonth)
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(vertical = 2.dp)
            .background(style.band, style.bandShape)
            .semantics { contentDescription = description }
            .clickable { onClick(date) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(ENDPOINT_SIZE_DP.dp)
                .background(style.circle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = style.text,
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .size(DOT_SIZE_DP.dp)
                        .background(style.dot, CircleShape),
                )
            }
        }
    }
}

private class DayCellStyle(
    val band: Color,
    val bandShape: RoundedCornerShape,
    val circle: Color,
    val text: Color,
    val dot: Color,
)

/** 셀의 색·모양만 계산한다(컴포저블 밖) — 시작·끝은 원, 사이는 띠, 사진 있는 날은 점 */
private fun dayCellStyle(
    colors: ColorScheme,
    date: LocalDate,
    count: Int,
    selection: DateRangeSelection,
): DayCellStyle {
    val inRange = selection.contains(date)
    val isStart = date == selection.start
    val isEnd = date == (selection.end ?: selection.start)
    val isEndpoint = isStart || isEnd
    val multiDay = selection.end != null && selection.start != selection.end
    val startPercent = if (isStart) HALF_PERCENT else 0
    val endPercent = if (isEnd) HALF_PERCENT else 0
    return DayCellStyle(
        band = if (inRange && multiDay) colors.primaryContainer else Color.Transparent,
        bandShape = RoundedCornerShape(
            topStartPercent = startPercent,
            bottomStartPercent = startPercent,
            topEndPercent = endPercent,
            bottomEndPercent = endPercent,
        ),
        circle = if (isEndpoint) colors.primary else Color.Transparent,
        text = dayTextColor(colors, isEndpoint, inRange, hasPhotos = count > 0),
        dot = dayDotColor(colors, count, isEndpoint),
    )
}

/** 사진이 없는 날도 고를 수 있다. 흐리게만 해서 "여기엔 없다" 를 알린다 */
private fun dayTextColor(colors: ColorScheme, isEndpoint: Boolean, inRange: Boolean, hasPhotos: Boolean): Color =
    when {
        isEndpoint -> colors.onPrimary
        inRange -> colors.onPrimaryContainer
        hasPhotos -> colors.onSurface
        else -> colors.onSurface.copy(alpha = EMPTY_ALPHA)
    }

private fun dayDotColor(colors: ColorScheme, count: Int, isEndpoint: Boolean): Color = when {
    count == 0 -> Color.Transparent
    isEndpoint -> colors.onPrimary
    else -> colors.primary
}

private fun DatePreset.labelRes(): Int = when (this) {
    DatePreset.TODAY -> R.string.gallery_date_preset_today
    DatePreset.LAST_7_DAYS -> R.string.gallery_date_preset_7days
    DatePreset.LAST_30_DAYS -> R.string.gallery_date_preset_30days
    DatePreset.THIS_YEAR -> R.string.gallery_date_preset_year
}

private const val CALENDAR_HEIGHT_DP = 400
private const val CALENDAR_HORIZONTAL_PADDING_DP = 12
private const val ENDPOINT_SIZE_DP = 40
private const val DOT_SIZE_DP = 4
private const val HALF_PERCENT = 50

/** 사진이 없는 날·달을 흐리게 하는 정도. 고를 수는 있다 */
private const val EMPTY_ALPHA = 0.38f

/** 달력 위 고정 헤더 — 지금 보이는 달을 보여주고, 누르면 연·월 점프 패널을 연다 */
@Composable
private fun MonthJumpHeader(state: CalendarState, expanded: Boolean, onToggle: () -> Unit) {
    val month = state.firstVisibleMonth.yearMonth
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = CALENDAR_HORIZONTAL_PADDING_DP.dp + 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.gallery_date_month_label, month.year, month.monthValue),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(
                if (expanded) R.string.gallery_date_jump_collapse else R.string.gallery_date_jump_expand,
            ),
        )
    }
}

/** 연도 행 + 월 그리드. 사진이 있는 연도·달만 고를 수 있다 */
@Composable
private fun MonthJumpPanel(
    dayCounts: Map<LocalDate, Int>,
    initialYear: Int,
    startMonth: YearMonth,
    endMonth: YearMonth,
    onPick: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 사진 없는 날도 고를 수 있게 됐으니 그 자리로 갈 수도 있어야 한다 → 범위 안 모든 해를 담는다
    val years = remember(startMonth, endMonth) { DateJump.yearsInRange(startMonth, endMonth) }
    // 패널이 열려 있는 동안만 유지한다. 열 때의 연도로 시작하고, 이후 사용자가 고른 값을 지킨다
    var selectedYear by rememberSaveable { mutableStateOf(initialYear) }
    val activeMonths = remember(dayCounts, selectedYear, startMonth, endMonth) {
        DateJump.monthsWithPhotos(dayCounts, selectedYear, startMonth, endMonth)
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CALENDAR_HORIZONTAL_PADDING_DP.dp, vertical = 4.dp),
    ) {
        // 오래된 해를 보다가 패널을 열면 선택된 칩이 오른쪽 화면 밖에 있다.
        // 그대로 두면 "아무것도 안 골라진" 것처럼 보이므로 열 때 그 칩까지 스크롤해 둔다.
        val yearListState = rememberLazyListState()
        LaunchedEffect(Unit) {
            val index = years.indexOf(selectedYear)
            if (index > 0) yearListState.scrollToItem(index)
        }
        LazyRow(
            state = yearListState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(years, key = { it }) { year ->
                FilterChip(
                    selected = year == selectedYear,
                    onClick = { selectedYear = year },
                    label = { Text(stringResource(R.string.gallery_date_year_label, year)) },
                )
            }
        }
        Column(Modifier.padding(top = 8.dp)) {
            for (row in 0 until MONTH_GRID_ROWS) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until MONTH_GRID_COLS) {
                        val month = row * MONTH_GRID_COLS + col + 1
                        MonthCell(
                            month = month,
                            // 달력이 다루지 않는 달만 막는다. 사진이 없을 뿐인 달은 고를 수 있다
                            enabled = DateJump.inRange(selectedYear, month, startMonth, endMonth),
                            hasPhotos = month in activeMonths,
                            modifier = Modifier.weight(1f),
                            onClick = { onPick(DateJump.target(selectedYear, month, startMonth, endMonth)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    month: Int,
    enabled: Boolean,
    hasPhotos: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(
            text = stringResource(R.string.gallery_date_month_only_label, month),
            // onSurfaceVariant 로는 어두운 테마에서 구별되지 않는다(실기기 확인).
            // 날짜 셀과 같은 투명도로 "사진 없음" 을 알린다.
            color = MaterialTheme.colorScheme.onSurface.let {
                if (enabled && hasPhotos) it else it.copy(alpha = EMPTY_ALPHA)
            },
        )
    }
}

private const val MONTH_GRID_COLS = 4
private const val MONTH_GRID_ROWS = 3
