package com.jjw.easygallery.core.data.autotag

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.upload.work.UploadNotifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.IOException

/**
 * 사진 라벨링을 백그라운드에서 돌린다(`docs/AUTO_TAGGING.md` §5.3).
 * 화면을 떠나도 이어지고 진행률은 `setProgress` 로 화면에 보낸다.
 */
@HiltWorker
class AutoTagWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: AutoTagRepository,
    private val prefs: UserPreferencesRepository,
    private val scheduler: AutoTagScheduler,
    private val notifications: UploadNotifications,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        notifications.ensureChannel()
        val daily = inputData.getBoolean(KEY_DAILY, false)
        return try {
            val result = repository.scan { done, total ->
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                if (total >= FOREGROUND_THRESHOLD) {
                    runCatching { setForeground(notifications.scanForegroundInfo(done, total)) }
                }
            }
            Timber.i(
                "자동 태그: 분석 %d, 라벨 붙음 %d, 실패 %d, 포기 %d, 모델대기 %s",
                result.scanned,
                result.tagged,
                result.failed,
                result.givenUp,
                result.modelUnavailable,
            )
            prefs.setAutoTagLastRun(System.currentTimeMillis())
            // 모델을 아직 못 받았으면 나중에 다시 — 지금 반복해 봐야 전부 실패한다
            if (result.modelUnavailable) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.e(e, "자동 태그 훑기 실패")
            Result.retry()
        } finally {
            // 매일 실행분이면 다음 날 것을 다시 잡는다(WorkManager 는 "매일 몇 시"를 직접 못 준다)
            if (daily) runCatching { scheduler.rescheduleDaily() }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "auto-tag-scan"
        const val UNIQUE_DAILY_NAME = "auto-tag-daily"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_DAILY = "daily"

        /** 이 개수 이상이면 포그라운드 알림(중복 검사와 같은 기준) */
        private const val FOREGROUND_THRESHOLD = 20
    }
}
