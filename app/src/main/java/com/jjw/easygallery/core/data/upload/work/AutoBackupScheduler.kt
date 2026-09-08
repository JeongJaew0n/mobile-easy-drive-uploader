package com.jjw.easygallery.core.data.upload.work

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 자동 백업 감지 예약.
 * - 트리거 워크: MediaStore 이미지·영상 URI 변경 시 실행(1회성 → 워커가 재예약). 변경이 몰리면 최대 5분까지 모아서 한 번.
 * - 주기 워크: 6시간마다 폴백(트리거를 놓친 경우 대비).
 * 스캔 자체는 로컬이라 네트워크 제약이 없고, 실제 업로드 제약은 UploadScheduler 가 담당한다.
 */
@Singleton
class AutoBackupScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
) {
    private val workManager get() = WorkManager.getInstance(context)

    fun enable() {
        armTrigger(ExistingWorkPolicy.REPLACE)
        workManager.enqueueUniquePeriodicWork(
            AutoBackupWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
                .addTag(AutoBackupWorker.PERIODIC_WORK_NAME)
                .build(),
        )
    }

    fun disable() {
        workManager.cancelUniqueWork(AutoBackupWorker.TRIGGER_WORK_NAME)
        workManager.cancelUniqueWork(AutoBackupWorker.PERIODIC_WORK_NAME)
    }

    /** 지금 즉시 한 번 스캔 (설정 화면의 "지금 검사") */
    fun runNow() {
        workManager.enqueueUniqueWork(
            RUN_NOW_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoBackupWorker>().build(),
        )
    }

    /** 앱 시작 시: 켜져 있으면 예약이 살아 있도록 보장 */
    suspend fun ensureScheduled() {
        if (prefs.current().autoBackupEnabled) {
            armTrigger(ExistingWorkPolicy.KEEP)
            enable()
        }
    }

    /** 워커 실행 후 재예약. 꺼졌으면 걸지 않는다 */
    suspend fun rearmTriggerIfEnabled() {
        if (prefs.current().autoBackupEnabled) armTrigger(ExistingWorkPolicy.REPLACE)
    }

    private fun armTrigger(policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            // 사진 여러 장 연속 촬영 시 매 장마다 깨지 않고 모아서 실행
            .setTriggerContentUpdateDelay(TRIGGER_UPDATE_DELAY_SECONDS, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(TRIGGER_MAX_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(
            AutoBackupWorker.TRIGGER_WORK_NAME,
            policy,
            OneTimeWorkRequestBuilder<AutoBackupWorker>()
                .setConstraints(constraints)
                .addTag(AutoBackupWorker.TRIGGER_WORK_NAME)
                .build(),
        )
    }

    private companion object {
        const val RUN_NOW_WORK_NAME = "auto-backup-now"
        const val PERIODIC_HOURS = 6L
        const val TRIGGER_UPDATE_DELAY_SECONDS = 30L
        const val TRIGGER_MAX_DELAY_MINUTES = 5L
    }
}
