package com.jjw.easygallery.feature.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.BackupDestination

/**
 * 백업된 사진 화면의 위쪽 — 세그먼트(백업됨·안 됨·대기·실패)와 저장소 칩(`docs/plans/backed-up-photos/spec.md` §2.2).
 * 저장소가 하나뿐이거나 대기·실패를 보고 있으면 칩 줄이 없다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupScopeBar(
    view: BackupView,
    destinations: List<BackupDestination>,
    onChange: (BackupView) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            BackupStatusFilter.entries.forEachIndexed { index, status ->
                SegmentedButton(
                    selected = view.status == status,
                    onClick = { onChange(view.copy(status = status)) },
                    shape = SegmentedButtonDefaults.itemShape(index, BackupStatusFilter.entries.size),
                    label = { Text(stringResource(status.labelRes()), maxLines = 1) },
                )
            }
        }
        if (destinations.size > 1 && view.status != BackupStatusFilter.PENDING_OR_FAILED) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all") {
                    FilterChip(
                        selected = view.destination == null,
                        onClick = { onChange(view.copy(destination = null)) },
                        label = { Text(stringResource(R.string.backed_up_all_destinations)) },
                    )
                }
                items(destinations, key = { it.destination }) { destination ->
                    FilterChip(
                        selected = view.destination == destination.destination,
                        onClick = { onChange(view.copy(destination = destination.destination)) },
                        label = {
                            Text(destinationLabel(destination), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
        }
    }
}

/** "Google Drive" / "Google Drive · b@example.com"(다른 계정 업로드) / 저장소 이름 */
@Composable
internal fun destinationLabel(destination: BackupDestination): String = when {
    destination.isPrimaryDrive -> stringResource(R.string.remote_kind_google)
    destination.driveEmail != null -> stringResource(R.string.backed_up_destination_drive_other, destination.driveEmail)
    else -> destination.remoteName.orEmpty()
}

private fun BackupStatusFilter.labelRes(): Int = when (this) {
    BackupStatusFilter.BACKED_UP -> R.string.backed_up_status_done
    BackupStatusFilter.NOT_BACKED_UP -> R.string.backed_up_status_none
    BackupStatusFilter.PENDING_OR_FAILED -> R.string.backed_up_status_queue
}
