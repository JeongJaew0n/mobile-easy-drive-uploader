package com.jjw.easygallery.feature.hidden

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.getSystemService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 기기 잠금(지문·얼굴·PIN·패턴)으로 본인 확인(`docs/PHOTO_HIDING.md` §2).
 * 숨김 PIN 을 잊었을 때 되찾는 유일한 길이다.
 *
 * `androidx.biometric` 대신 플랫폼 API 를 쓴다 — 그쪽은 `FragmentActivity` 를 요구하는데
 * 이 앱은 `ComponentActivity` 하나로 돌고, minSdk 29 면 플랫폼 API 로 충분하다.
 */
object DeviceCredential {

    /** 기기에 잠금이 없으면 복구를 제공할 수 없다. 잠금 없는 확인은 확인이 아니다 */
    fun isAvailable(context: Context): Boolean =
        context.getSystemService<KeyguardManager>()?.isDeviceSecure == true

    /**
     * 확인 창을 띄우고 결과를 기다린다. 사용자가 취소하거나 실패하면 false.
     *
     * 기기 잠금을 허용하면 "취소" 버튼을 따로 달 수 없다(플랫폼 제약).
     */
    suspend fun confirm(activity: Activity, title: String, subtitle: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val builder = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }

            builder.build().authenticate(
                signal,
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
            )
        }
}
