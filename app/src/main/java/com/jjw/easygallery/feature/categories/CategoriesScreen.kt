package com.jjw.easygallery.feature.categories

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.Category
import com.jjw.easygallery.core.domain.model.CategoryError
import com.jjw.easygallery.core.ui.motion.LocalMotion
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
) {
    val motion = LocalMotion.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(categories, key = { it.id }) { category ->
            CategoryRow(
                category = category,
                canMoveUp = categories.first().id != category.id,
                canMoveDown = categories.last().id != category.id,
                onEdit = { onEdit(category) },
                onDelete = { onDelete(category) },
                onMove = { onMove(category.id, it) },
                // 순서를 바꾸면 행이 미끄러져 자리를 바꾼다
                modifier = Modifier.animateItem(placementSpec = motion.settle()),
            )
        }
    }
}

@Composable
private fun LazyItemScope.CategoryRow(
    category: Category,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        modifier = modifier,
        leadingContent = { CategoryDot(colorIndex = category.colorIndex, size = ROW_DOT_DP) },
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
