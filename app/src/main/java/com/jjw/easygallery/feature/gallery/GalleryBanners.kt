package com.jjw.easygallery.feature.gallery

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.Category
import com.jjw.easygallery.core.domain.model.CategoryFilter
import com.jjw.easygallery.core.domain.model.DateRange
import com.jjw.easygallery.core.domain.model.UploadSummary
import com.jjw.easygallery.core.ui.motion.LocalMotion
import com.jjw.easygallery.feature.categories.CategoryDot
import java.time.format.DateTimeFormatter

@Composable
internal fun UploadProgressBanner(
    summary: UploadSummary,
    onCancel: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = summary.current
    val doneCount = summary.completed + summary.failed
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.gallery_uploading,
                        (doneCount + 1).coerceAtMost(summary.total),
                        summary.total,
                        current?.displayName.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
            // DB 갱신(1초 주기)마다 200ms 만 움직인다 — 계단 느낌은 없애고 연속 재구성은 피한다
            val fraction by animateFloatAsState(
                targetValue = current?.fraction ?: 0f,
                animationSpec = LocalMotion.current.progress(),
                label = "uploadProgress",
            )
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun UploadFailedBanner(
    failed: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(R.plurals.gallery_upload_failed_banner, failed, failed),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClick) { Text(stringResource(R.string.action_view)) }
        }
    }
}

@Composable
internal fun PermissionRequiredContent(
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.gallery_permission_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.gallery_permission_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onRequestPermission) {
            Text(stringResource(R.string.gallery_permission_grant))
        }
        TextButton(onClick = onOpenAppSettings) {
            Text(stringResource(R.string.gallery_permission_open_settings))
        }
    }
}

/** 적용 중인 기간을 보여주고 한 번에 해제한다 */
@Composable
internal fun DateRangeBar(
    range: DateRange,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy.MM.dd") }
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (range.isSingleDay) {
                    formatter.format(range.start)
                } else {
                    stringResource(
                        R.string.gallery_date_range_label,
                        formatter.format(range.start),
                        formatter.format(range.endInclusive),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.gallery_date_clear))
            }
        }
    }
}

@Composable
internal fun PartialAccessBanner(
    onManageSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.gallery_partial_access_message),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onManageSelection) {
                Text(stringResource(R.string.gallery_partial_access_manage))
            }
        }
    }
}

/** 카테고리 필터가 켜져 있을 때 기간 바 아래에 붙는 바. 색 점 + 이름들, X 로 해제 */
@Composable
internal fun CategoryFilterBar(
    filter: CategoryFilter,
    categories: List<Category>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val selected = (filter as? CategoryFilter.Any)?.let { f -> categories.filter { it.id in f.ids } }.orEmpty()
            if (filter is CategoryFilter.Uncategorized) {
                Icon(
                    painterResource(R.drawable.ic_label_off),
                    contentDescription = null,
                    modifier = Modifier.size(CATEGORY_BAR_ICON_DP.dp),
                )
            } else {
                selected.take(MAX_CATEGORY_DOTS).forEach { category ->
                    CategoryDot(colorIndex = category.colorIndex, size = CATEGORY_BAR_DOT_DP)
                    Spacer(Modifier.width(4.dp))
                }
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = when (filter) {
                    CategoryFilter.Uncategorized -> stringResource(R.string.gallery_title_uncategorized)
                    is CategoryFilter.Any -> selected.joinToString(", ") { it.name }
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.category_filter_clear))
            }
        }
    }
}

private const val MAX_CATEGORY_DOTS = 3
private const val CATEGORY_BAR_DOT_DP = 10
private const val CATEGORY_BAR_ICON_DP = 16
