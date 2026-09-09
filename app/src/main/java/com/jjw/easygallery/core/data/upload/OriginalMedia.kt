package com.jjw.easygallery.core.data.upload

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.InputStream

/**
 * 업로드는 **원본 그대로** 올려야 한다.
 *
 * MediaStore 는 `ACCESS_MEDIA_LOCATION` 없이 연 스트림에서 GPS EXIF 를 0 으로 덮어 준다
 * (길이는 그대로라 크기 비교로는 못 잡는다 — 실기기에서 25바이트가 지워진 채 올라가는 것을 확인했다).
 * 권한이 있으면 [MediaStore.setRequireOriginal] 로 가려지지 않은 원본을 요청한다.
 */
fun Context.openOriginalStream(uri: Uri): InputStream? {
    val resolver = contentResolver
    if (hasAccessMediaLocation()) {
        val original = runCatching { MediaStore.setRequireOriginal(uri) }.getOrNull()
        if (original != null) {
            // 원본 요청이 거부되는 URI(다운로드 캐시 등)도 있어 실패하면 일반 스트림으로 물러난다
            runCatching { resolver.openInputStream(original) }.getOrNull()?.let { return it }
        }
    }
    return resolver.openInputStream(uri)
}

private fun Context.hasAccessMediaLocation(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
