package com.jjw.easygallery.core.data.duplicates

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class ScanProgress(val running: Boolean, val done: Int, val total: Int)

@Singleton
class DuplicateScanScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    fun start() {
        workManager.enqueueUniqueWork(
            DuplicateScanWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DuplicateScanWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }

    fun cancel() = workManager.cancelUniqueWork(DuplicateScanWorker.UNIQUE_WORK_NAME)

    fun observeProgress(): Flow<ScanProgress> =
        workManager.getWorkInfosForUniqueWorkFlow(DuplicateScanWorker.UNIQUE_WORK_NAME).map { infos ->
            val info = infos.firstOrNull()
            val running = info != null &&
                (info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED)
            ScanProgress(
                running = running,
                done = info?.progress?.getInt(DuplicateScanWorker.KEY_DONE, 0) ?: 0,
                total = info?.progress?.getInt(DuplicateScanWorker.KEY_TOTAL, 0) ?: 0,
            )
        }
}
