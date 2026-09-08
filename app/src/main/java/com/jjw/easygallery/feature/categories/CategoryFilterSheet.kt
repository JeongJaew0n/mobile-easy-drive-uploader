package com.jjw.easygallery.feature.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.Category
import com.jjw.easygallery.core.domain.model.CategoryFilter

/**
 * 카테고리로 갤러리를 거르는 바텀시트(`docs/CATEGORIES.md` §4.2).
 * 여러 개 고르면 OR, "미분류"는 단독. 적용 전까지는 갤러리에 반영하지 않는다.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryFilterSheet(
    categories: List<Category>,
    current: CategoryFilter?,
    onApply: (CategoryFilter?) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedIds by remember { mutableStateOf((current as? CategoryFilter.Any)?.ids ?: emptySet()) }
    var uncategorized by remember { mutableStateOf(current is CategoryFilter.Uncategorized) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.category_filter_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onManage) { Text(stringResource(R.string.category_manage)) }
            }
            if (categories.isEmpty()) {
                Text(
                    text = stringResource(R.string.category_filter_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = uncategorized,
                    onClick = {
                        uncategorized = !uncategorized
                        if (uncategorized) selectedIds = emptySet()
                    },
                    label = { Text(stringResource(R.string.category_filter_uncategorized)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_label_off), contentDescription = null) },
                )
                categories.forEach { category ->
                    val selected = category.id in selectedIds
                    FilterChip(
                        selected = selected,
                        onClick = {
                            uncategorized = false
                            selectedIds = if (selected) selectedIds - category.id else selectedIds + category.id
                        },
                        label = { Text("${category.name} · ${category.itemCount}") },
                        leadingIcon = { CategoryDot(colorIndex = category.colorIndex) },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (current != null) {
                    TextButton(onClick = { onApply(null) }) { Text(stringResource(R.string.category_filter_clear)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(
                    onClick = {
                        onApply(
                            when {
                                uncategorized -> CategoryFilter.Uncategorized
                                selectedIds.isNotEmpty() -> CategoryFilter.Any(selectedIds)
                                else -> null
                            },
                        )
                    },
                    enabled = uncategorized || selectedIds.isNotEmpty() || current != null,
                ) {
                    Text(stringResource(R.string.action_apply))
                }
            }
        }
    }
}
