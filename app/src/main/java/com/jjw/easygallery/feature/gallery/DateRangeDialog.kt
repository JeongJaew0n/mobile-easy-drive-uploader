package com.jjw.easygallery.feature.gallery

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.DatePreset
import com.jjw.easygallery.core.domain.model.DateRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 기간 선택. 위쪽 빠른 선택 칩과 아래 달력 중 아무 쪽이나 쓸 수 있다.
 * Material 달력은 UTC 자정 기준 밀리초를 주므로 로컬 날짜로 변환해서 다룬다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRangeDialog(
    current: DateRange?,
    onDismiss: () -> Unit,
    onConfirm: (DateRange?) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = current?.start?.toUtcMillis(),
        initialSelectedEndDateMillis = current?.endInclusive?.toUtcMillis(),
    )
    val selected = pickerState.toDateRange()

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { selected?.let(onConfirm) }, enabled = selected != null) {
                Text(stringResource(R.string.action_apply))
            }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = { onConfirm(null) }) {
                        Text(stringResource(R.string.gallery_date_clear))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DatePreset.entries.forEach { preset ->
                    val range = preset.toRange(today)
                    FilterChip(
                        selected = selected == range,
                        onClick = {
                            pickerState.setSelection(
                                startDateMillis = range.start.toUtcMillis(),
                                endDateMillis = range.endInclusive.toUtcMillis(),
                            )
                        },
                        label = { Text(stringResource(preset.labelRes())) },
                    )
                }
            }
            DateRangePicker(state = pickerState, showModeToggle = false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun androidx.compose.material3.DateRangePickerState.toDateRange(): DateRange? {
    val start = selectedStartDateMillis?.toLocalDateFromUtc() ?: return null
    // 하루만 고른 상태에서도 적용할 수 있게 종료일을 시작일로 채운다
    val end = selectedEndDateMillis?.toLocalDateFromUtc() ?: start
    return DateRange(start, end)
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDateFromUtc(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.of("UTC")).toLocalDate()

private fun DatePreset.labelRes(): Int = when (this) {
    DatePreset.TODAY -> R.string.gallery_date_preset_today
    DatePreset.LAST_7_DAYS -> R.string.gallery_date_preset_7days
    DatePreset.LAST_30_DAYS -> R.string.gallery_date_preset_30days
    DatePreset.THIS_YEAR -> R.string.gallery_date_preset_year
}
