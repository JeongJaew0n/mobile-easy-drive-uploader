package com.jjw.easygallery.feature.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailUrlTest {

    @Test
    fun `끝의 크기 꼬리를 칸 크기로 바꾼다`() {
        assertEquals(
            "https://lh3.googleusercontent.com/drive-storage/abc=s400",
            thumbnailUrl("https://lh3.googleusercontent.com/drive-storage/abc=s220", 400),
        )
    }

    @Test
    fun `크기 꼬리가 없으면 그대로 둔다`() {
        val link = "https://drive.google.com/thumbnail?id=abc"
        assertEquals(link, thumbnailUrl(link, 400))
    }

    @Test
    fun `중간의 =s 는 건드리지 않는다`() {
        val link = "https://lh3.googleusercontent.com/a=s220/b"
        assertEquals(link, thumbnailUrl(link, 400))
    }
}
