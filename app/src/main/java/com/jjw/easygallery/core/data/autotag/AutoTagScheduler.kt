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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.Duration
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

    /**
     * 진행 중인 훑기를 멈춘다. 매일 실행분이 **돌고 있을 때만** 그것도 멈춘다 —
     * 다음 시각을 기다리며 ENQUEUED 로 앉아 있는 것까지 취소하면 매일 실행이 통째로 사라진다.
     */
    suspend fun cancelScan() {
        workManager.cancelUniqueWork(AutoTagWorker.UNIQUE_WORK_NAME)
        if (dailyInfos().any { it.state == WorkInfo.State.RUNNING }) {
            // 취소되면 워커가 finally 에서 다음 날 것을 다시 잡는다
            workManager.cancelUniqueWork(AutoTagWorker.UNIQUE_DAILY_NAME)
        }
    }

    private suspend fun dailyInfos() =
        workManager.getWorkInfosForUniqueWorkFlow(AutoTagWorker.UNIQUE_DAILY_NAME).first()

    /**
     * 매일 실행을 켜거나 시각을 바꿨을 때. 워커가 실행을 마치고 다음 날 것을 잡을 때는 [afterRun] 을 켠다 —
     * JobScheduler 는 목표보다 1초쯤 일찍 깨우는 일이 있어, 그대로 계산하면 "오늘 것"이 아직 안 지난 것으로 보여
     * 곧바로 한 번 더 돈다(실기기에서 21:49:59 · 21:50:00 두 번 실행으로 확인).
     */
    suspend fun rescheduleDaily(afterRun: Boolean = false) {
        val p = prefs.current()
        if (!p.autoTagEnabled) {
            disableDaily()
            return
        }
        val delay = delayUntil(
            p.autoTagMinuteOfDay,
            skipWithin = if (afterRun) SETTLE else Duration.ZERO,
        )
        workManager.enqueueUniqueWork(
            AutoTagWorker.UNIQUE_DAILY_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AutoTagWorker>()
                // 분 단위로 자르면 안 된다. 04:00 을 59초 앞두고 뜬 훑기가 끝나며 다시 예약하면
                // 남은 지연이 또 1분 미만 → 0분으로 잘려 04:00 을 넘길 때까지 헛도는 재실행이 반복된다.
                .setInitialDelay(delay.seconds.coerceAtLeast(1), TimeUnit.SECONDS) // toSeconds() 는 API 31
                // 충전은 요구하지 않는다. 대개 몇 장이라 부담이 작다
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInputData(workDataOf(AutoTagWorker.KEY_DAILY to true))
                .build(),
        )
    }

    fun disableDaily() = workManager.cancelUniqueWork(AutoTagWorker.UNIQUE_DAILY_NAME)

    /**
     * 즉시 훑기와 매일 훑기 **양쪽**을 본다. 즉시 훑기만 보고 있으면 새벽 훑기가 도는 동안
     * 화면은 "쉬는 중" 으로 보이고, 사용자가 겹치는 훑기를 또 시작할 수 있다.
     *
     * 다만 매일 실행분은 다음 시각까지 ENQUEUED 로 대기하는 게 정상이라, 그건 "도는 중" 이 아니다.
     */
    fun observeProgress(): Flow<AutoTagProgress> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(AutoTagWorker.UNIQUE_WORK_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(AutoTagWorker.UNIQUE_DAILY_NAME),
    ) { manual, daily ->
        val active = manual.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            ?: daily.firstOrNull { it.state == WorkInfo.State.RUNNING }
        AutoTagProgress(
            running = active != null,
            done = active?.progress?.getInt(AutoTagWorker.KEY_DONE, 0) ?: 0,
            total = active?.progress?.getInt(AutoTagWorker.KEY_TOTAL, 0) ?: 0,
        )
    }

    companion object {
        /**
         * 지금부터 다음 [minuteOfDay] 까지. 이미 지났으면 내일 그 시각.
         * [skipWithin] 안쪽으로 남았으면 "방금 그 실행이 끝난 것" 으로 보고 내일로 넘긴다.
         */
        fun delayUntil(
            minuteOfDay: Int,
            now: LocalDateTime = LocalDateTime.now(),
            skipWithin: Duration = Duration.ZERO,
        ): Duration {
            val target = LocalTime.of(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)
            val today = LocalDateTime.of(now.toLocalDate(), target)
            val next = if (Duration.between(now, today) > skipWithin) {
                today
            } else {
                LocalDateTime.of(now.toLocalDate().plusDays(1), target)
            }
            return Duration.between(now, next)
        }

        /** 이 안쪽이면 방금 돈 것으로 본다 */
        private val SETTLE: Duration = Duration.ofMinutes(1)

        /** 저장값을 "오전 4:00" 같은 표시로 나눌 때 쓴다 */
        fun hourOf(minuteOfDay: Int): Int = minuteOfDay / MINUTES_PER_HOUR

        fun minuteOf(minuteOfDay: Int): Int = minuteOfDay % MINUTES_PER_HOUR

        fun minuteOfDay(hour: Int, minute: Int): Int = hour * MINUTES_PER_HOUR + minute

        /** 하루 중 분 단위 최댓값 검증에 쓴다 */
        fun isValid(minuteOfDay: Int): Boolean = minuteOfDay in 0 until MINUTES_PER_DAY

        private const val MINUTES_PER_HOUR = 60
        private const val MINUTES_PER_DAY = 24 * 60
    }
}
