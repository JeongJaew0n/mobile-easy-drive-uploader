package com.jjw.easygallery.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSpeedFormatTest {

    @Test
    fun `whole speeds drop the decimal point`() {
        assertEquals("1", formatSpeed(1f))
        assertEquals("2", formatSpeed(2f))
    }

    @Test
    fun `fractional speeds keep their digits`() {
        assertEquals("0.25", formatSpeed(0.25f))
        assertEquals("0.5", formatSpeed(0.5f))
        assertEquals("0.75", formatSpeed(0.75f))
        assertEquals("1.25", formatSpeed(1.25f))
        assertEquals("1.5", formatSpeed(1.5f))
    }
}
