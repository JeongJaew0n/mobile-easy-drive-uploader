package com.jjw.easygallery.feature.gallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class MediaPermissionStatus {
    /** 모든 사진·영상 접근 가능 */
    Full,

    /** Android 14+ 에서 사용자가 일부 항목만 선택 */
    Partial,
    Denied,
}

/** SDK 버전별로 달라지는 미디어 읽기 권한을 한곳에서 다룬다. */
object MediaPermission {

    fun required(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun status(context: Context): MediaPermissionStatus {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                granted(Manifest.permission.READ_MEDIA_VIDEO) -> MediaPermissionStatus.Full

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaPermissionStatus.Partial

            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaPermissionStatus.Full

            else -> MediaPermissionStatus.Denied
        }
    }
}
