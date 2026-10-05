package com.jjw.easygallery.core.domain.usecase

import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.upload.DeviceConditionsMonitor
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.domain.model.UploadState
import com.jjw.easygallery.core.domain.model.UploadSummary
import com.jjw.easygallery.core.domain.model.uploadWaitReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * 업로드 큐 요약에 "왜 멈췄는지" 를 얹는다 — WorkManager 가 보는 것과 같은 조건(Wi-Fi·충전·재시도)을 화면도 본다.
 * 갤러리 배너와 백업 칸이 같은 문장을 보이도록 한 곳에서 만든다(`docs/plans/bottom-navigation/spec.md`).
 */
class ObserveUploadSummaryUseCase @Inject constructor(
    private val uploadQueue: UploadQueueRepository,
    private val conditions: DeviceConditionsMonitor,
    private val prefs: UserPreferencesRepository,
) {
    operator fun invoke(): Flow<UploadSummary> = combine(
        uploadQueue.observeSummary(),
        conditions.observe(),
        prefs.preferences,
    ) { summary, device, p ->
        summary.copy(
            waitReason = uploadWaitReason(
                isRunning = summary.current?.state == UploadState.RUNNING,
                wifiOnly = p.uploadWifiOnly,
                chargingOnly = p.uploadChargingOnly,
                isUnmetered = device.isUnmetered,
                isCharging = device.isCharging,
                attemptCount = summary.current?.attemptCount ?: 0,
            ),
        )
    }
}
