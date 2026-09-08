package com.jjw.easygallery.core.data.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.jjw.easygallery.core.domain.model.DriveEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 원격 파일 → 기기 다운로드 워크 등록. 같은 파일을 두 번 누르면 진행 중인 것을 유지(KEEP) */
@Singleton
class DownloadScheduler @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    fun enqueue(accountId: String?, entry: DriveEntry) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(DownloadWorker.inputData(accountId, entry.id, entry.name, entry.mimeType, entry.sizeBytes))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork("download-${entry.id}", ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val BACKOFF_SECONDS = 15L
    }
}
