package com.jjw.easygallery.core.data.upload.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /**
     * 큐 처리 워커를 예약한다. 이미 돌고 있으면 유지(KEEP).
     * 제약 조건이 바뀌었을 때는 [replace] 로 교체 — 진행 중 항목은 세션 상태 조회로 이어 올리므로 손실이 없다.
     */
    suspend fun schedule(replace: Boolean = false) {
        val p = prefs.current()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (p.uploadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(p.uploadChargingOnly)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(UploadWorker.UNIQUE_WORK_NAME)
            .build()
        workManager.enqueueUniqueWork(
            UploadWorker.UNIQUE_WORK_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(UploadWorker.UNIQUE_WORK_NAME)
    }

    private companion object {
        const val BACKOFF_SECONDS = 30L
    }
}
