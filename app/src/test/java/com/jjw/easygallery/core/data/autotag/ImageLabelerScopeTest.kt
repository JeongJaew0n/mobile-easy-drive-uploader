package com.jjw.easygallery.core.data.autotag

import org.junit.Assert.assertNull
import org.junit.Test
import javax.inject.Singleton

/**
 * [MlKitImageLabeler.close] 는 네이티브 분류기를 영구히 닫는다. 그래서 이 구현은 **스코프가 없어야** 한다.
 *
 * 싱글턴으로 바인딩하면 첫 훑기가 끝나며 닫힌 인스턴스를 두 번째 훑기가 그대로 받아,
 * 사진마다 `This detector is already closed!` 로 실패한다. 세 번 돌면 모든 사진이
 * [com.jjw.easygallery.core.data.upload.db.AutoTagScanEntity.MAX_FAILURES] 에 닿아 영구히 포기된다.
 * 실기기에서만 드러나고 증상이 "두 번째부터 태그가 안 붙는다" 라 원인을 찾기 어려워, 여기서 막는다.
 */
class ImageLabelerScopeTest {

    @Test
    fun `ML Kit 라벨러에는 스코프가 없다`() {
        assertNull(MlKitImageLabeler::class.java.getAnnotation(Singleton::class.java))
    }
}
