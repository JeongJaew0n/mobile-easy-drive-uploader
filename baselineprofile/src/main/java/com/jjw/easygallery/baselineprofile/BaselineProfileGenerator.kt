package com.jjw.easygallery.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 콜드 스타트 + 갤러리 첫 플링 경로를 AOT 컴파일 대상으로 수집한다.
 * 실행: ./gradlew :app:generateBaselineProfile  → app/src/release/generated/baselineProfiles/
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndGalleryScroll() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        scrollGallery()
    }
}
