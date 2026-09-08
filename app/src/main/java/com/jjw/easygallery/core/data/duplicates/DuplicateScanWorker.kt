package com.jjw.easygallery.core.data.duplicates

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jjw.easygallery.core.data.upload.work.UploadNotifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.IOException

/** 해시 계산은 파일을 전부 읽어 오래 걸릴 수 있어 워커로 돌린다. 화면을 떠나도 이어지고 진행률은 setProgress 로. */
@HiltWorker
class DuplicateScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: DuplicateRepository,
    private val notifications: UploadNotifications,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        notifications.ensureChannel()
        return try {
            repository.scan { done, total ->
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                if (total >= FOREGROUND_THRESHOLD) {
                    runCatching { setForeground(notifications.scanForegroundInfo(done, total)) }
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.e(e, "duplicate scan failed")
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "duplicate-scan"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"

        /** 해시할 파일이 이 개수 이상이면 포그라운드 알림을 띄운다 */
        private const val FOREGROUND_THRESHOLD = 20
    }
}
