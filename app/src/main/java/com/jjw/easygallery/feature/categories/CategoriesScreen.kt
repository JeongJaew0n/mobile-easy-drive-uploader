package com.jjw.easygallery.feature.categories

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.Category
import com.jjw.easygallery.core.domain.model.CategoryError
import com.jjw.easygallery.core.ui.motion.LocalMotion
import com.jjw.easygallery.feature.categories.CategoryReorder.moved
import kotlinx.coroutines.launch

@Composable
fun CategoriesRoute(
    onBackClick: () -> Unit,
    viewModel: CategoriesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    CategoriesScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onCreate = viewModel::create,
        onRename = viewModel::rename,
        onRecolor = viewModel::recolor,
        onDelete = viewModel::delete,
        onMove = viewModel::move,
        onReorder = viewModel::reorder,
    )
}

/** 카테고리 관리(`docs/CATEGORIES.md` §5.3). 생성·이름/색 변경·삭제·위아래 이동 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoriesScreen(
    uiState: CategoriesUiState,
    onBackClick: () -> Unit,
    onCreate: suspend (String, Int) -> Result<Category>,
    onRename: suspend (Long, String) -> Result<Unit>,
    onRecolor: (Long, Int) -> Unit,
    onDelete: (Long) -> Unit,
    onMove: (Long, Int) -> Unit,
    onReorder: (List<Long>) -> Unit = {},
) {
    var editing by remember { mutableStateOf<CategoryEditTarget?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.category_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = CategoryEditTarget.New }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.category_new))
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (uiState) {
                CategoriesUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is CategoriesUiState.Error -> Text(
                    text = uiState.throwable.localizedMessage ?: uiState.throwable.toString(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
                is CategoriesUiState.Content -> if (uiState.categories.isEmpty()) {
                    Text(
                        text = stringResource(R.string.category_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                    )
                } else {
                    CategoryList(
                        categories = uiState.categories,
                        onEdit = { editing = CategoryEditTarget.Existing(it) },
                        onDelete = { deleting = it },
                        onMove = onMove,
                        onReorder = onReorder,
                    )
                }
            }
        }
    }

    editing?.let { target ->
        CategoryEditDialog(
            target = target,
            onDismiss = { editing = null },
            onCreate = onCreate,
            onRename = onRename,
            onRecolor = onRecolor,
        )
    }
    deleting?.let { category ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.category_delete_title, category.name)) },
            text = {
                Text(pluralStringResource(R.plurals.category_delete_message, category.itemCount, category.itemCount))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(category.id)
                        deleting = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete_forever))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun CategoryList(
    categories: List<Category>,
    onEdit: (Category) -> Unit,
    onDelete: (Category) -> Unit,
    onMove: (Long, Int) -> Unit,
    onReorder: (List<Long>) -> Unit,
) {
    val motion = LocalMotion.current
    // 드래그 중에는 화면에 보이는 순서를 로컬로 들고 있다가, 손을 떼면 한 번만 저장한다
    var order by remember(categories) { mutableStateOf(categories) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }

    LazyColumn(Modifier.fillMaxSize()) {
        items(order, key = { it.id }) { category ->
            val isDragging = draggingId == category.id
            CategoryRow(
                category = category,
                canMoveUp = order.first().id != category.id,
                canMoveDown = order.last().id != category.id,
                isDragging = isDragging,
                onEdit = { onEdit(category) },
                onDelete = { onDelete(category) },
                onMove = { onMove(category.id, it) },
                onDragStart = {
                    draggingId = category.id
                    dragOffset = 0f
                },
                onDrag = { delta ->
                    val index = order.indexOfFirst { it.id == category.id }
                    dragOffset += delta
                    val step = CategoryReorder.step(index, dragOffset, rowHeight, order.size)
                    if (step.index != index) {
                        order = order.moved(index, step.index)
                        dragOffset = step.offset
                    }
                },
                onDragEnd = {
                    draggingId = null
                    dragOffset = 0f
                    CategoryReorder.changedOrder(categories, order)?.let(onReorder)
                },
                modifier = Modifier
                    .onSizeChanged { size -> if (rowHeight == 0) rowHeight = size.height }
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    // 순서를 바꾸면 행이 미끄러져 자리를 바꾼다(끌고 있는 행은 손가락을 따라가므로 제외)
                    .then(if (isDragging) Modifier else Modifier.animateItem(placementSpec = motion.settle())),
            )
        }
    }
}

@Composable
private fun LazyItemScope.CategoryRow(
    category: Category,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isDragging: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        modifier = modifier,
        tonalElevation = if (isDragging) DRAG_ELEVATION_DP else 0.dp,
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DragHandle(onDragStart = onDragStart, onDrag = onDrag, onDragEnd = onDragEnd)
                Spacer(Modifier.width(HANDLE_GAP_DP))
                CategoryDot(colorIndex = category.colorIndex, size = ROW_DOT_DP)
            }
        },
        headlineContent = { Text(category.name) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.category_item_count, category.itemCount, category.itemCount))
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.category_edit)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.category_move_up)) },
                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                        enabled = canMoveUp,
                        onClick = {
                            menuExpanded = false
                            onMove(-1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.category_move_down)) },
                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                        enabled = canMoveDown,
                        onClick = {
                            menuExpanded = false
                            onMove(1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete_forever)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

internal sealed interface CategoryEditTarget {
    data object New : CategoryEditTarget
    data class Existing(val category: Category) : CategoryEditTarget
}

/** 생성과 이름/색 변경이 같은 다이얼로그를 쓴다. 이름 오류는 필드 아래에 */
@Composable
private fun CategoryEditDialog(
    target: CategoryEditTarget,
    onDismiss: () -> Unit,
    onCreate: suspend (String, Int) -> Result<Category>,
    onRename: suspend (Long, String) -> Result<Unit>,
    onRecolor: (Long, Int) -> Unit,
) {
    val existing = (target as? CategoryEditTarget.Existing)?.category
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var colorIndex by rememberSaveable { mutableIntStateOf(existing?.colorIndex ?: 0) }
    var error by remember { mutableStateOf<CategoryError?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.category_new else R.string.category_edit)) },
        text = {
            Column {
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    scope.launch {
                        val result = if (existing == null) {
                            onCreate(name, colorIndex).map { }
                        } else {
                            if (colorIndex != existing.colorIndex) onRecolor(existing.id, colorIndex)
                            if (name.trim() != existing.name) onRename(existing.id, name) else Result.success(Unit)
                        }
                        result.onSuccess { onDismiss() }.onFailure { error = it as? CategoryError }
                    }
                },
            ) {
                Text(stringResource(if (existing == null) R.string.category_create else R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val ROW_DOT_DP = 16

/** 이 손잡이에서만 끌어 순서를 바꾼다 — 행 전체를 끌면 목록 스크롤과 겹친다 */
@Composable
private fun DragHandle(onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    Icon(
        painter = painterResource(R.drawable.ic_drag_handle),
        contentDescription = stringResource(R.string.category_drag_handle),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { onDragStart() },
                onDragEnd = onDragEnd,
                onDragCancel = onDragEnd,
                onDrag = { change, amount ->
                    // 소비하지 않으면 LazyColumn 이 같이 스크롤된다
                    change.consume()
                    onDrag(amount.y)
                },
            )
        },
    )
}

private val DRAG_ELEVATION_DP = 8.dp
private val HANDLE_GAP_DP = 12.dp
