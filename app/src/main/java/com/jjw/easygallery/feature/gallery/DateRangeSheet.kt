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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
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
            WeekdayHeader(firstDayOfWeek)
            RangeCalendar(
                state = calendarState,
                dayCounts = dayCounts,
                today = today,
                selection = selection,
                onDayClick = { selection = selection.select(it) },
            )
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
    today: LocalDate,
    selection: DateRangeSelection,
    onDayClick: (LocalDate) -> Unit,
) {
    VerticalCalendar(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .height(CALENDAR_HEIGHT_DP.dp)
            .padding(horizontal = CALENDAR_HORIZONTAL_PADDING_DP.dp),
        monthHeader = { month -> MonthHeader(month) },
        dayContent = { day ->
            DayCell(
                day = day,
                count = dayCounts[day.date] ?: 0,
                enabled = day.position == DayPosition.MonthDate &&
                    (dayCounts[day.date] ?: 0) > 0 &&
                    !day.date.isAfter(today),
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
    enabled: Boolean,
    selection: DateRangeSelection,
    onClick: (LocalDate) -> Unit,
) {
    if (day.position != DayPosition.MonthDate) {
        Box(Modifier.aspectRatio(1f))
        return
    }
    val date = day.date
    val style = dayCellStyle(MaterialTheme.colorScheme, date, count, enabled, selection)
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
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled) { onClick(date) },
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
    enabled: Boolean,
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
        text = dayTextColor(colors, isEndpoint, inRange, enabled),
        dot = dayDotColor(colors, count, isEndpoint),
    )
}

private fun dayTextColor(colors: ColorScheme, isEndpoint: Boolean, inRange: Boolean, enabled: Boolean): Color = when {
    isEndpoint -> colors.onPrimary
    inRange -> colors.onPrimaryContainer
    enabled -> colors.onSurface
    else -> colors.onSurface.copy(alpha = DISABLED_ALPHA)
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
private const val DISABLED_ALPHA = 0.38f
