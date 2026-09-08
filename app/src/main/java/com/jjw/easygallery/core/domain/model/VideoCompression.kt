package com.jjw.easygallery.core.domain.model

import kotlin.math.min

/**
 * 업로드 사본 영상 압축 프리셋. 짧은 변 기준(세로 영상도 자연스럽게 처리), H.264.
 * 원본이 이미 목표보다 작거나 같으면 압축하지 않는다.
 */
enum class VideoCompression(val shortSidePx: Int?, val bitrateBps: Int?) {
    ORIGINAL(null, null),
    HD_1080(SHORT_SIDE_1080, BITRATE_1080),
    HD_720(SHORT_SIDE_720, BITRATE_720),
    ;

    /** 해상도를 모르면(0) 압축을 시도한다 — Transformer 가 실제 크기를 보고 처리 */
    fun shouldCompress(width: Int, height: Int): Boolean {
        val target = shortSidePx ?: return false
        if (width <= 0 || height <= 0) return true
        return min(width, height) > target
    }

    companion object {
        fun fromStorageKey(key: String): VideoCompression? = entries.firstOrNull { it.name == key }
    }
}

private const val SHORT_SIDE_1080 = 1080
private const val SHORT_SIDE_720 = 720
private const val BITRATE_1080 = 8_000_000
private const val BITRATE_720 = 4_000_000
