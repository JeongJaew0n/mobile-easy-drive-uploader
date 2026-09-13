package com.jjw.easygallery.core.data.autotag

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 자동 태그 진행 상태 */
data class AutoTagProgress(val running: Boolean, val done: Int, val total: Int)

/**
 * 훑기 예약. 즉시 실행과 "매일 정해진 시각" 두 가지가 있다(`docs/AUTO_TAGGING.md` §5.4).
 *
 * WorkManager 의 주기 작업은 "매일 04:00" 같은 지정을 못 하므로, 다음 그 시각까지의 지연을 계산해
 * 일회성으로 넣고 실행이 끝나면 워커가 다음 날 것을 다시 잡는다.
 */
@Singleton
class AutoTagScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** 사용자가 "지금 전체 분석" 을 눌렀을 때 */
    fun scanNow() {
        workManager.enqueueUniqueWork(
            AutoTagWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoTagWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }

    fun cancelScan() = workManager.cancelUniqueWork(AutoTagWorker.UNIQUE_WORK_NAME)

    /** 매일 실행을 켜거나 시각을 바꿨을 때 */
    suspend fun rescheduleDaily() {
        val p = prefs.current()
        if (!p.autoTagEnabled) {
            disableDaily()
            return
        }
        val delay = delayUntil(p.autoTagMinuteOfDay)
        workManager.enqueueUniqueWork(
            AutoTagWorker.UNIQUE_DAILY_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AutoTagWorker>()
                .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
                // 충전은 요구하지 않는다. 대개 몇 장이라 부담이 작다
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInputData(workDataOf(AutoTagWorker.KEY_DAILY to true))
                .build(),
        )
    }

    fun disableDaily() = workManager.cancelUniqueWork(AutoTagWorker.UNIQUE_DAILY_NAME)

    fun observeProgress(): Flow<AutoTagProgress> =
        workManager.getWorkInfosForUniqueWorkFlow(AutoTagWorker.UNIQUE_WORK_NAME).map { infos ->
            val info = infos.firstOrNull()
            val running = info != null &&
                (info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED)
            AutoTagProgress(
                running = running,
                done = info?.progress?.getInt(AutoTagWorker.KEY_DONE, 0) ?: 0,
                total = info?.progress?.getInt(AutoTagWorker.KEY_TOTAL, 0) ?: 0,
            )
        }

    companion object {
        /** 지금부터 다음 [minuteOfDay] 까지. 이미 지났으면 내일 그 시각 */
        fun delayUntil(minuteOfDay: Int, now: LocalDateTime = LocalDateTime.now()): Duration {
            val target = LocalTime.of(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)
            val today = LocalDateTime.of(now.toLocalDate(), target)
            val next = if (today.isAfter(now)) today else LocalDateTime.of(now.toLocalDate().plusDays(1), target)
            return Duration.between(now, next)
        }

        /** 저장값을 "오전 4:00" 같은 표시로 나눌 때 쓴다 */
        fun hourOf(minuteOfDay: Int): Int = minuteOfDay / MINUTES_PER_HOUR

        fun minuteOf(minuteOfDay: Int): Int = minuteOfDay % MINUTES_PER_HOUR

        fun minuteOfDay(hour: Int, minute: Int): Int = hour * MINUTES_PER_HOUR + minute

        /** 하루 중 분 단위 최댓값 검증에 쓴다 */
        fun isValid(minuteOfDay: Int): Boolean = minuteOfDay in 0 until MINUTES_PER_DAY

        private const val MINUTES_PER_HOUR = 60
        private const val MINUTES_PER_DAY = 24 * 60

        @Suppress("unused") // LocalDate 는 delayUntil 의 기본 인자 계산에 쓰인다
        private val unusedMarker: LocalDate? = null
    }
}
