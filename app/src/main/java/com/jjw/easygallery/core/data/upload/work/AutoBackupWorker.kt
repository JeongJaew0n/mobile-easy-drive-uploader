package com.jjw.easygallery.core.data.upload.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jjw.easygallery.core.domain.usecase.AutoBackupUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * MediaStore 변경(콘텐츠 URI 트리거) 또는 주기 폴백으로 깨어나 새 항목을 큐에 넣는다.
 * 콘텐츠 트리거는 1회성이라 실행 후 스스로 다시 예약한다.
 */
@HiltWorker
@Suppress("TooGenericExceptionCaught") // 스캔 실패는 다음 트리거에서 다시 시도
class AutoBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val autoBackup: AutoBackupUseCase,
    private val scheduler: AutoBackupScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val result = try {
            autoBackup.scanAndEnqueue()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "auto-backup scan failed")
            return Result.retry()
        } finally {
            // 트리거 워크는 소모되므로 다음 변경을 기다리도록 다시 건다 (꺼져 있으면 scheduler 가 무시)
            scheduler.rearmTriggerIfEnabled()
        }
        Timber.d("auto-backup worker: %s", result)
        return Result.success()
    }

    companion object {
        const val TRIGGER_WORK_NAME = "auto-backup-trigger"
        const val PERIODIC_WORK_NAME = "auto-backup-periodic"
    }
}
