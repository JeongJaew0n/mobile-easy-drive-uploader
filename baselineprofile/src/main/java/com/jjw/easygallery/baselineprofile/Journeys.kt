package com.jjw.easygallery.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/** 릴리스 빌드 패키지. 벤치마크는 .debug 접미사가 없는 릴리스 계열 변형을 대상으로 한다. */
const val TARGET_PACKAGE = "com.jjw.easygallery"

private const val GRID_WAIT_MILLIS = 5_000L
private const val FLING_COUNT = 4

/**
 * 갤러리 그리드가 보일 때까지 기다린 뒤 플링한다.
 * 권한이 없으면 그리드가 뜨지 않으므로 기기 준비 시 `pm grant` 로 READ_MEDIA_* 를 먼저 준다 (README 참고).
 */
fun MacrobenchmarkScope.scrollGallery() {
    val grid = device.wait(Until.findObject(By.scrollable(true)), GRID_WAIT_MILLIS) ?: return
    grid.setGestureMargin(device.displayWidth / 5)
    repeat(FLING_COUNT) {
        grid.fling(Direction.DOWN)
        device.waitForIdle()
    }
    repeat(FLING_COUNT) {
        grid.fling(Direction.UP)
        device.waitForIdle()
    }
}
