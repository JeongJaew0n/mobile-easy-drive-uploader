package com.jjw.easygallery.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ANIMATION_IMPROVEMENT.md §5·§8 의 시나리오. 릴리스 계열(benchmarkRelease) 빌드에 대해 실행한다.
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest  (물리 기기 API 33+ 연결)
 * 결과: baselineprofile/build/outputs/connected_android_test_additional_output/ 아래 JSON 파일
 */
@RunWith(AndroidJUnit4::class)
class GalleryBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** 콜드 스타트 — 프로파일 없음 vs Baseline Profile 적용 비교 */
    @Test
    fun startupNoCompilation() = startup(CompilationMode.None())

    @Test
    fun startupBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    /** 그리드 플링 프레임 시간 — 목표 janky < 5%, 95th < 16ms(60Hz) */
    @Test
    fun galleryScrollBaselineProfile() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        },
    ) {
        scrollGallery()
    }

    private fun startup(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    private companion object {
        const val ITERATIONS = 5
    }
}
