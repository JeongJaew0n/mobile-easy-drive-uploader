package com.jjw.easygallery.feature.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.CATEGORY_COLOR_COUNT
import com.jjw.easygallery.core.domain.model.Category
import com.jjw.easygallery.core.domain.model.CategoryAssignments
import com.jjw.easygallery.core.domain.model.CategoryError
import com.jjw.easygallery.core.ui.theme.categoryColor
import kotlinx.coroutines.launch

/**
 * 선택한 항목들에 카테고리를 붙이고 떼는 바텀시트(`docs/CATEGORIES.md` §5.1).
 * 행은 전부/일부/없음 tri-state, 적용 시 바뀐 행만 반영한다. 시트 안에서 새 카테고리를 바로 만들 수 있다.
 *
 * @param onCreateCategory 이름·색으로 카테고리를 만든다. 실패는 [CategoryError]
 * @param onApply 바뀐 것만 (add, remove)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    mediaIds: Collection<Long>,
    categories: List<Category>,
    assignments: CategoryAssignments,
    onCreateCategory: suspend (name: String, colorIndex: Int) -> Result<Category>,
    onApply: (add: Set<Long>, remove: Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 시트가 열린 동안 목록이 바뀌어도(새 카테고리 추가) 사용자의 탭 상태는 유지한다
    var picker by remember { mutableStateOf(CategoryPickerState.of(mediaIds, categories.map { it.id }, assignments)) }
    val scope = rememberCoroutineScope()
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
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.category_assign_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = pluralStringResource(R.plurals.category_assign_items, mediaIds.size, mediaIds.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NewCategoryRow(
                onCreate = { name, color ->
                    onCreateCategory(name, color).onSuccess { picker = picker.withNewCategory(it.id) }
                },
            )
            if (categories.isEmpty()) {
                Text(
                    text = stringResource(R.string.category_assign_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT_DP.dp)) {
                items(categories, key = { it.id }) { category ->
                    val assignedCount = mediaIds.count { assignments[it]?.contains(category.id) == true }
                    CategoryPickRow(
                        category = category,
                        state = picker.stateOf(category.id),
                        assignedCount = assignedCount,
                        total = mediaIds.size,
                        onToggle = { picker = picker.toggle(category.id) },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(
                    onClick = {
                        val diff = picker.diff()
                        scope.launch { sheetState.hide() }
                        onApply(diff.add, diff.remove)
                    },
                    enabled = picker.hasChanges,
                ) {
                    Text(stringResource(R.string.action_apply))
                }
            }
        }
    }
}

@Composable
private fun CategoryPickRow(
    category: Category,
    state: PickState,
    assignedCount: Int,
    total: Int,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryDot(colorIndex = category.colorIndex)
        Spacer(Modifier.width(12.dp))
        Text(
            text = category.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.category_row_count, assignedCount, total),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TriStateCheckbox(
            state = when (state) {
                PickState.None -> ToggleableState.Off
                PickState.Partial -> ToggleableState.Indeterminate
                PickState.All -> ToggleableState.On
            },
            onClick = onToggle,
        )
    }
}

/** 색 점. 피커·필터 시트·칩·배지가 같은 모양을 쓴다 */
@Composable
fun CategoryDot(colorIndex: Int, modifier: Modifier = Modifier, size: Int = DOT_SIZE_DP) {
    val label = stringResource(R.string.category_color_label, colorIndex + 1)
    Box(
        modifier
            .size(size.dp)
            .background(categoryColor(colorIndex), CircleShape)
            .semantics { contentDescription = label },
    )
}

/** "새 카테고리" 인라인 입력. 접힌 상태에서는 한 줄 버튼, 펼치면 이름 + 색 팔레트 */
@Composable
internal fun NewCategoryRow(onCreate: suspend (name: String, colorIndex: Int) -> Result<Category>) {
    var expanded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var colorIndex by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<CategoryError?>(null) }
    val scope = rememberCoroutineScope()

    if (!expanded) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.category_new))
        }
        return
    }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                error = null
            },
            singleLine = true,
            label = { Text(stringResource(R.string.category_name_hint)) },
            isError = error != null,
            supportingText = error?.let { e -> { Text(stringResource(e.messageRes())) } },
            modifier = Modifier.fillMaxWidth(),
        )
        ColorPaletteRow(selected = colorIndex, onSelect = { colorIndex = it })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.action_cancel)) }
            TextButton(
                onClick = {
                    scope.launch {
                        onCreate(name, colorIndex)
                            .onSuccess {
                                name = ""
                                expanded = false
                            }
                            .onFailure { error = it as? CategoryError }
                    }
                },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.category_create))
            }
        }
    }
}

@Composable
internal fun ColorPaletteRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        repeat(CATEGORY_COLOR_COUNT) { index ->
            val label = stringResource(R.string.category_color_label, index + 1)
            Box(
                modifier = Modifier
                    .size(PALETTE_SIZE_DP.dp)
                    .background(categoryColor(index), CircleShape)
                    .clickable { onSelect(index) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                if (index == selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
            }
        }
    }
}

internal fun CategoryError.messageRes(): Int = when (this) {
    is CategoryError.EmptyName -> R.string.category_name_empty
    is CategoryError.NameTooLong -> R.string.category_name_too_long
    is CategoryError.DuplicateName -> R.string.category_name_duplicate
}

private const val LIST_MAX_HEIGHT_DP = 360
private const val DOT_SIZE_DP = 12
private const val PALETTE_SIZE_DP = 32
