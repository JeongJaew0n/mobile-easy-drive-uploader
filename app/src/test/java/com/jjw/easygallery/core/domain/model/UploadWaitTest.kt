package com.jjw.easygallery.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadWaitTest {

    private fun reason(
        isRunning: Boolean = false,
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
        isUnmetered: Boolean = true,
        isCharging: Boolean = true,
        attemptCount: Int = 0,
    ) = uploadWaitReason(isRunning, wifiOnly, chargingOnly, isUnmetered, isCharging, attemptCount)

    @Test
    fun `전송 중이면 기다리는 이유가 없다`() {
        // 제약이 어긋나 보여도 이미 돌고 있으면 그것이 사실이다
        assertEquals(UploadWaitReason.NONE, reason(isRunning = true, wifiOnly = true, isUnmetered = false))
    }

    @Test
    fun `Wi-Fi 전용인데 종량제면 Wi-Fi 를 기다린다`() {
        assertEquals(UploadWaitReason.WIFI, reason(wifiOnly = true, isUnmetered = false))
    }

    @Test
    fun `Wi-Fi 전용이어도 무제한 회선이면 기다리지 않는다`() {
        assertEquals(UploadWaitReason.NONE, reason(wifiOnly = true, isUnmetered = true))
    }

    @Test
    fun `충전 전용인데 충전 중이 아니면 충전을 기다린다`() {
        assertEquals(UploadWaitReason.CHARGING, reason(chargingOnly = true, isCharging = false))
    }

    @Test
    fun `네트워크가 먼저다 - 둘 다 어긋나면 Wi-Fi 를 말한다`() {
        val r = reason(wifiOnly = true, isUnmetered = false, chargingOnly = true, isCharging = false)
        assertEquals(UploadWaitReason.WIFI, r)
    }

    @Test
    fun `제약은 맞는데 실패한 적이 있으면 재시도 대기다`() {
        assertEquals(UploadWaitReason.RETRY, reason(attemptCount = 2))
    }
}
