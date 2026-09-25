package com.jjw.easygallery.core.domain.model

/**
 * 큐에 항목이 있는데 **왜 올라가지 않는지**.
 *
 * WorkManager 는 제약이 충족될 때까지 조용히 기다린다. 화면이 그동안 "업로드 중" 이라고만 말하면
 * 사용자는 앱이 멈췄다고 읽는다 — 실제로 그렇게 신고가 들어왔다. 기다리는 이유를 말한다.
 */
enum class UploadWaitReason {
    /** 제약이 모두 충족돼 실제로 올라가는 중 */
    NONE,

    /** "Wi-Fi 에서만" 이 켜져 있는데 지금은 종량제 회선이다 */
    WIFI,

    /** "충전 중에만" 이 켜져 있는데 충전 중이 아니다 */
    CHARGING,

    /** 실패해서 백오프 대기 중. 곧 스스로 다시 시도한다 */
    RETRY,
}

/** 기기 상태와 설정만으로 대기 이유를 정한다. 프레임워크에 기대지 않아 그대로 테스트할 수 있다. */
fun uploadWaitReason(
    isRunning: Boolean,
    wifiOnly: Boolean,
    chargingOnly: Boolean,
    isUnmetered: Boolean,
    isCharging: Boolean,
    attemptCount: Int,
): UploadWaitReason = when {
    // 실제로 전송 중이면 어떤 제약도 이미 충족된 것이다
    isRunning -> UploadWaitReason.NONE
    wifiOnly && !isUnmetered -> UploadWaitReason.WIFI
    chargingOnly && !isCharging -> UploadWaitReason.CHARGING
    attemptCount > 0 -> UploadWaitReason.RETRY
    else -> UploadWaitReason.NONE
}
