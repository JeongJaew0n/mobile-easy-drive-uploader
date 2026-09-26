package com.jjw.easygallery.feature.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.datasource.DataSource
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.DriveEntry

/**
 * Drive 의 사진·영상을 **앱 안에서** 본다.
 *
 * 외부 Drive 앱으로 넘기면 기기에 여러 Google 계정이 있을 때 파일마다 계정을 고르라고 묻는다.
 * 앱이 이미 어느 계정에 연결됐는지 알고 있으니 직접 받아 그린다 — 물음이 사라진다.
 */
@Composable
internal fun DrivePreview(
    entry: DriveEntry,
    imageLoader: ImageLoader,
    dataSourceFactory: DataSource.Factory,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.isVideo) {
                DriveVideoContent(entry = entry, dataSourceFactory = dataSourceFactory)
            } else {
                DriveImageContent(entry = entry, imageLoader = imageLoader, onDismiss = onDismiss)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun DriveImageContent(entry: DriveEntry, imageLoader: ImageLoader, onDismiss: () -> Unit) {
    var failed by remember { mutableStateOf(false) }
    if (failed) {
        PreviewMessage(stringResource(R.string.drive_preview_failed))
        return
    }
    var loading by remember { mutableStateOf(true) }
    AsyncImage(
        model = driveMediaUrl(entry.id),
        contentDescription = entry.name,
        imageLoader = imageLoader,
        contentScale = ContentScale.Fit,
        // 사진은 아무 데나 눌러 닫는다 — 전체화면에서 가장 기대되는 동작.
        // 영상은 컨트롤을 눌러야 해서 이 동작을 주지 않는다
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss),
        onState = { state ->
            loading = state is AsyncImagePainter.State.Loading
            if (state is AsyncImagePainter.State.Error) failed = true
        },
    )
    if (loading) CircularProgressIndicator()
}

@Composable
internal fun PreviewMessage(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(32.dp),
    )
}

/** 파일 내용 자체를 받는 주소. 인증은 `DriveHttpClient` 의 인터셉터가 붙인다 */
internal fun driveMediaUrl(fileId: String): String =
    "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
