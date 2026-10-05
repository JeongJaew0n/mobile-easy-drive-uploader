package com.jjw.easygallery.feature.gallery

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R

/** 선택 하단바·더보기 시트에서 누를 수 있는 것들 — 파라미터 폭발 방지 */
internal data class SelectionBarActions(
    val onUpload: () -> Unit = {},
    val onUploadTo: (UploadTargetOption) -> Unit = {},
    val onUploadToGuest: () -> Unit = {},
    val onToggleChosen: () -> Unit = {},
    val onTrash: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onToggleFavorite: () -> Unit = {},
    val onHide: () -> Unit = {},
    val onCategories: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onMove: () -> Unit = {},
)

/** 더보기 시트의 한 줄. 순서가 곧 화면 순서다 — 되돌릴 수 없는 완전 삭제가 맨 아래 */
internal enum class SelectionMoreItem {
    FAVORITE,
    HIDE,
    CATEGORIES,
    RENAME,
    MOVE,
    UPLOAD_TO_OTHER,
    UPLOAD_TO_GUEST,
    DELETE_FOREVER,
}

/**
 * 더보기 시트에 무엇을 보일지(순수 함수 — 테스트로 굳힌다).
 * - 즐겨찾기는 API 30+ 에서만(MediaStore 요청)
 * - 이름 변경은 하나를 골랐을 때만
 * - 다른 저장소로 올리기는 대상이 둘 이상일 때만 — 하나뿐이면 하단바의 업로드와 같다
 * - 휴지통이 없는 기기(API 29)는 하단바 휴지통 자리가 이미 "삭제" 라 시트에서 뺀다
 */
internal fun selectionMoreItems(
    selectedCount: Int,
    supportsTrashAndFavorites: Boolean,
    uploadTargetCount: Int,
    guestAvailable: Boolean,
): List<SelectionMoreItem> = buildList {
    if (supportsTrashAndFavorites) add(SelectionMoreItem.FAVORITE)
    add(SelectionMoreItem.HIDE)
    add(SelectionMoreItem.CATEGORIES)
    if (selectedCount == 1) add(SelectionMoreItem.RENAME)
    add(SelectionMoreItem.MOVE)
    if (uploadTargetCount > 1) add(SelectionMoreItem.UPLOAD_TO_OTHER)
    if (guestAvailable) add(SelectionMoreItem.UPLOAD_TO_GUEST)
    if (supportsTrashAndFavorites) add(SelectionMoreItem.DELETE_FOREVER)
}

/**
 * 선택 하단바(`docs/plans/ux-round2/spec.md` §1). 자주 쓰는 넷만 **글자를 붙여** 두고 나머지는 더보기 시트로 —
 * 글자 없는 앱 고유 아이콘은 뜻을 맞히는 비율이 34% 다(NN/g).
 */
@Composable
internal fun SelectionBottomBar(
    selectedCount: Int,
    allFavorite: Boolean,
    allChosen: Boolean,
    supportsTrashAndFavorites: Boolean,
    enabled: Boolean,
    uploadTargets: List<UploadTargetOption>,
    guestAvailable: Boolean,
    actions: SelectionBarActions,
) {
    var showMore by rememberSaveable { mutableStateOf(false) }
    BottomAppBar {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            LabeledAction(
                icon = painterResource(R.drawable.ic_cloud_upload),
                label = R.string.selection_upload,
                enabled = enabled,
                onClick = actions.onUpload,
                modifier = Modifier.weight(1f),
            )
            LabeledAction(
                icon = painterResource(if (allChosen) R.drawable.ic_bookmark_remove else R.drawable.ic_bookmark_add),
                label = if (allChosen) R.string.selection_unchoose else R.string.selection_choose,
                enabled = enabled,
                onClick = actions.onToggleChosen,
                modifier = Modifier.weight(1f),
            )
            // 휴지통이 없는 기기(API 29)에서는 이 자리가 완전 삭제다
            LabeledAction(
                icon = if (supportsTrashAndFavorites) {
                    rememberVectorPainter(Icons.Filled.Delete)
                } else {
                    painterResource(R.drawable.ic_delete_forever)
                },
                label = if (supportsTrashAndFavorites) R.string.selection_trash else R.string.selection_delete,
                enabled = enabled,
                onClick = if (supportsTrashAndFavorites) actions.onTrash else actions.onDelete,
                modifier = Modifier.weight(1f),
            )
            LabeledAction(
                icon = rememberVectorPainter(Icons.Filled.MoreVert),
                label = R.string.selection_more,
                enabled = enabled,
                onClick = { showMore = true },
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (showMore) {
        SelectionMoreSheet(
            items = selectionMoreItems(selectedCount, supportsTrashAndFavorites, uploadTargets.size, guestAvailable),
            allFavorite = allFavorite,
            uploadTargets = uploadTargets,
            actions = actions,
            onDismiss = { showMore = false },
        )
    }
}

@Composable
private fun LabeledAction(
    icon: Painter,
    @StringRes label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    Column(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionMoreSheet(
    items: List<SelectionMoreItem>,
    allFavorite: Boolean,
    uploadTargets: List<UploadTargetOption>,
    actions: SelectionBarActions,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 시트를 닫고 나서 동작한다 — 이름 변경·이동 대화상자가 시트 위에 겹치지 않게
    val run: (() -> Unit) -> Unit = { action ->
        onDismiss()
        action()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding()) {
            items.forEach { item -> MoreItemRows(item, allFavorite, uploadTargets, actions, run) }
        }
    }
}

/** 시트의 한 항목. 다른 저장소로 올리기는 대상마다 한 줄이라 여러 줄일 수 있다 */
@Composable
private fun MoreItemRows(
    item: SelectionMoreItem,
    allFavorite: Boolean,
    uploadTargets: List<UploadTargetOption>,
    actions: SelectionBarActions,
    run: (() -> Unit) -> Unit,
) {
    when (item) {
        SelectionMoreItem.FAVORITE -> MoreRow(
            icon = rememberVectorPainter(if (allFavorite) Icons.Filled.Star else Icons.Outlined.Star),
            text = stringResource(if (allFavorite) R.string.action_unfavorite else R.string.action_favorite),
            onClick = { run(actions.onToggleFavorite) },
        )
        SelectionMoreItem.HIDE -> MoreRow(
            icon = painterResource(R.drawable.ic_visibility_off),
            text = stringResource(R.string.action_hide),
            onClick = { run(actions.onHide) },
        )
        SelectionMoreItem.CATEGORIES -> MoreRow(
            icon = painterResource(R.drawable.ic_label),
            text = stringResource(R.string.category_assign_title),
            onClick = { run(actions.onCategories) },
        )
        SelectionMoreItem.RENAME -> MoreRow(
            icon = rememberVectorPainter(Icons.Filled.Edit),
            text = stringResource(R.string.action_rename),
            onClick = { run(actions.onRename) },
        )
        SelectionMoreItem.MOVE -> MoreRow(
            icon = painterResource(R.drawable.ic_drive_file_move),
            text = stringResource(R.string.action_move),
            onClick = { run(actions.onMove) },
        )
        SelectionMoreItem.UPLOAD_TO_OTHER -> uploadTargets.forEach { target ->
            val label = stringResource(R.string.gallery_upload_to_item, target.name)
            MoreRow(
                icon = painterResource(R.drawable.ic_cloud_upload),
                text = if (target.isDefault) "$label ✓" else label,
                onClick = { run { actions.onUploadTo(target) } },
            )
        }
        SelectionMoreItem.UPLOAD_TO_GUEST -> MoreRow(
            icon = painterResource(R.drawable.ic_cloud_upload),
            text = stringResource(R.string.gallery_upload_to_guest),
            onClick = { run(actions.onUploadToGuest) },
        )
        SelectionMoreItem.DELETE_FOREVER -> MoreRow(
            icon = painterResource(R.drawable.ic_delete_forever),
            text = stringResource(R.string.action_delete_forever),
            onClick = { run(actions.onDelete) },
            destructive = true,
        )
    }
}

@Composable
private fun MoreRow(icon: Painter, text: String, onClick: () -> Unit, destructive: Boolean = false) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(headlineColor = color, leadingIconColor = color),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
