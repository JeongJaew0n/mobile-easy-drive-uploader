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

    /**
     * 읽기 권한과 함께 [Manifest.permission.ACCESS_MEDIA_LOCATION] 을 같이 요청한다.
     * 별도 대화상자 없이 읽기 권한과 함께 부여되며, 없으면 EXIF 위치 원본 요청이
     * `UnsupportedOperationException` 으로 실패한다.
     */
    fun required(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
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
