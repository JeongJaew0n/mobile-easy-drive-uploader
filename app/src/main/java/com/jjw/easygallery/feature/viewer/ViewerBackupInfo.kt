package com.jjw.easygallery.feature.viewer

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.BackupDestination
import com.jjw.easygallery.feature.gallery.destinationLabel

/** 올라간 곳마다 한 줄: 저장소 · 올린 시각. Google Drive 줄에는 "Drive 에서 열기". 기록이 없으면 아무것도 없다 */
@Composable
internal fun BackupInfoRows(backups: List<BackupLine>, onOpenInDrive: (BackupLine) -> Unit) {
    if (backups.isEmpty()) return
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.viewer_info_backup),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = LABEL_ALPHA),
            modifier = Modifier.size(width = LABEL_WIDTH_DP.dp, height = ROW_HEIGHT_DP.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            backups.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val time = DateFormat.getMediumDateFormat(context).format(line.uploadedAt) + " " +
                        DateFormat.getTimeFormat(context).format(line.uploadedAt)
                    Text(
                        text = destinationLabel(line.destination) + " · " + time,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (line.destination.isDrive) {
                        TextButton(onClick = { onOpenInDrive(line) }) {
                            Text(
                                text = stringResource(R.string.viewer_open_in_drive),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 상세보기 정보의 "백업" 한 줄 — 이 사진이 올라간 곳 하나(`docs/plans/backed-up-photos/spec.md` §2.3) */
data class BackupLine(
    val destination: BackupDestination,
    val uploadedAt: Long,
    /** 그곳의 파일 ID. Google Drive 면 "Drive 에서 열기" 로 연다 */
    val remoteId: String,
    /** Drive 링크를 열 계정 — 다른 계정으로 올린 것은 그 계정이라야 권한이 있다 */
    val openAs: String?,
)

private const val LABEL_ALPHA = 0.7f
private const val LABEL_WIDTH_DP = 92
private const val ROW_HEIGHT_DP = 20
