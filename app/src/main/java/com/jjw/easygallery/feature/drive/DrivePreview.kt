package com.jjw.easygallery.feature.drive

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Drive 미리보기의 공용 조각. 사진·영상은 **앱 안에서** 본다(`DriveMediaPager`) — 외부 Drive 앱으로 넘기면 기기에 여러 Google
 * 계정이 있을 때 파일마다 계정을 고르라고 묻는다. 앱이 이미 어느 계정에 연결됐는지 알고 있으니 직접 받아 그린다.
 */
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
