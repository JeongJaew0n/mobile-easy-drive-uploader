package com.jjw.easygallery.feature.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * "가로로 보기" 토글을 액티비티 방향 요청으로 옮긴다.
 *
 * 해제할 때 곧바로 `UNSPECIFIED` 로 두면 안 된다: 자동 회전이 꺼진 기기에서는 시스템이 잠금 방향(`user_rotation`)을
 * **현재 화면 방향(가로)** 으로 갈아 끼워 상세보기를 나가도 가로로 남는다(Galaxy S23+, Android 16 에서 재현).
 * 그래서 잠그기 전의 사용자 방향을 기억해 두고, 해제·종료 시 그 방향을 잠깐 명시한 뒤 [RELEASE_DELAY_MILLIS] 후에 `UNSPECIFIED` 로 돌린다.
 */
@Composable
internal fun OrientationLockEffect(landscapeLocked: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    val saved = remember { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }

    LaunchedEffect(landscapeLocked) {
        if (landscapeLocked) {
            saved.intValue = userPreferredOrientation(activity)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            activity.requestedOrientation = saved.intValue
            if (saved.intValue != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                delay(RELEASE_DELAY_MILLIS)
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
    DisposableEffect(activity) {
        onDispose {
            if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return@onDispose
            activity.requestedOrientation = saved.intValue
            if (saved.intValue != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                activity.window.decorView.postDelayed(
                    { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED },
                    RELEASE_DELAY_MILLIS,
                )
            }
        }
    }
}

/** 자동 회전이 켜져 있으면 UNSPECIFIED, 꺼져 있으면 잠금 방향(user_rotation)에 해당하는 고정 방향 */
private fun userPreferredOrientation(context: Context): Int {
    val resolver = context.contentResolver
    val autoRotate = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
    if (autoRotate) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    return when (Settings.System.getInt(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0)) {
        Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}

/** Compose 의 Context 는 ContextWrapper 로 감싸여 있을 수 있다 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val RELEASE_DELAY_MILLIS = 1_000L
