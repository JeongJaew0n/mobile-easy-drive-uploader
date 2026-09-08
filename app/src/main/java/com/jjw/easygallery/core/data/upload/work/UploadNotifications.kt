package com.jjw.easygallery.core.data.upload.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.jjw.easygallery.MainActivity
import com.jjw.easygallery.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val manager get() = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_upload),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.notification_channel_upload_description) }
        manager.createNotificationChannel(channel)
    }

    fun progressForegroundInfo(
        done: Int,
        total: Int,
        currentName: String,
        fraction: Float,
        compressing: Boolean = false,
    ): ForegroundInfo {
        val titleRes = if (compressing) R.string.notification_compress_title else R.string.notification_upload_title
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud_upload)
            .setContentTitle(context.getString(titleRes, done + 1, total))
            .setContentText(currentName)
            .setProgress(PROGRESS_MAX, (fraction * PROGRESS_MAX).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_ID, notification)
        }
    }

    /** 중복 검사(해시 계산) 진행 알림 — 업로드와 같은 채널, 다른 ID */
    fun scanForegroundInfo(done: Int, total: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud_upload)
            .setContentTitle(context.getString(R.string.notification_scan_title))
            .setContentText(context.getString(R.string.notification_scan_text, done, total))
            .setProgress(total.coerceAtLeast(1), done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(SCAN_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(SCAN_ID, notification)
        }
    }

    /** 다운로드 진행 — 업로드와 같은 채널, 파일당 알림 하나가 아니라 워커 포그라운드 하나 */
    fun downloadForegroundInfo(name: String, fraction: Float): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_download)
            .setContentTitle(context.getString(R.string.notification_download_title))
            .setContentText(name)
            .setProgress(PROGRESS_MAX, (fraction * PROGRESS_MAX).toInt(), fraction <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(DOWNLOAD_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(DOWNLOAD_ID, notification)
        }
    }

    fun showDownloadResult(name: String, success: Boolean) {
        val textRes = if (success) R.string.notification_download_done else R.string.notification_download_failed
        notify(
            DOWNLOAD_RESULT_ID,
            context.getString(textRes, name),
            context.getString(R.string.notification_download_title),
        )
    }

    fun showSummary(succeeded: Int, failed: Int) {
        val text = if (failed == 0) {
            context.resources.getQuantityString(R.plurals.notification_upload_done, succeeded, succeeded)
        } else {
            context.getString(R.string.notification_upload_done_with_failures, succeeded, failed)
        }
        notify(SUMMARY_ID, text, context.getString(R.string.notification_upload_summary_title))
    }

    fun showSignInRequired() {
        notify(
            SUMMARY_ID,
            context.getString(R.string.notification_sign_in_required_text),
            context.getString(R.string.notification_sign_in_required_title),
        )
    }

    private fun notify(id: Int, text: String, title: String) {
        // POST_NOTIFICATIONS 가 거부된 경우 notify 는 조용히 무시된다
        if (!manager.areNotificationsEnabled()) return
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        manager.notify(id, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_ID = "upload"
        const val PROGRESS_ID = 1001
        const val SUMMARY_ID = 1002
        const val SCAN_ID = 1003
        const val DOWNLOAD_ID = 1004
        const val DOWNLOAD_RESULT_ID = 1005
        private const val PROGRESS_MAX = 100
    }
}
