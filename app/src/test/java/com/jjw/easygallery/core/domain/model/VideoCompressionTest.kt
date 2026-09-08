package com.jjw.easygallery.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCompressionTest {

    @Test
    fun `original never compresses`() {
        assertFalse(VideoCompression.ORIGINAL.shouldCompress(3840, 2160))
        assertFalse(VideoCompression.ORIGINAL.shouldCompress(0, 0))
    }

    @Test
    fun `compresses only when the short side exceeds the target`() {
        assertTrue(VideoCompression.HD_1080.shouldCompress(3840, 2160))
        assertTrue(VideoCompression.HD_1080.shouldCompress(2160, 3840)) // 세로 영상
        assertFalse(VideoCompression.HD_1080.shouldCompress(1920, 1080))
        assertFalse(VideoCompression.HD_1080.shouldCompress(1280, 720))
        assertTrue(VideoCompression.HD_720.shouldCompress(1920, 1080))
        assertFalse(VideoCompression.HD_720.shouldCompress(1280, 720))
    }

    @Test
    fun `unknown resolution is compressed so the encoder can decide`() {
        assertTrue(VideoCompression.HD_720.shouldCompress(0, 0))
    }

    @Test
    fun `storage key round trips and rejects garbage`() {
        VideoCompression.entries.forEach { assertEquals(it, VideoCompression.fromStorageKey(it.name)) }
        assertNull(VideoCompression.fromStorageKey("4K"))
    }
}
