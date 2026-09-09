package com.jjw.easygallery.core.data.remote.smb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ContentChunkProviderTest {

    private val source = ByteArray(TOTAL) { it.toByte() }

    /** smbj 가 하는 일을 흉내 낸다: isAvailable 인 동안 getChunk 로 받아 offset 을 그만큼 전진 */
    private fun drain(provider: ContentChunkProvider, chunkSize: Int, maxRounds: Int = MAX_ROUNDS): ByteArray {
        val written = ArrayList<Byte>()
        var rounds = 0
        while (provider.isAvailable()) {
            check(rounds++ < maxRounds) { "무한 루프: offset=${provider.offset}" }
            val chunk = ByteArray(chunkSize)
            val size = provider.writeInto(chunk)
            check(size >= 0) { "getChunk 가 음수를 돌려주면 smbj 의 offset 이 뒤로 간다" }
            written.addAll(chunk.take(size))
        }
        return written.toByteArray()
    }

    @Test
    fun `sends the whole source and reports progress`() {
        val progress = ArrayList<Long>()
        val provider = ContentChunkProvider(ByteArrayInputStream(source), 0, TOTAL.toLong()) { progress += it }

        val written = drain(provider, CHUNK)

        assertArrayEquals(source, written)
        assertEquals(TOTAL.toLong(), progress.last())
        assertFalse(provider.isAvailable())
    }

    @Test
    fun `resuming skips to the offset and sends only the rest`() {
        val provider = ContentChunkProvider(ByteArrayInputStream(source), RESUME_AT.toLong(), TOTAL.toLong())

        val written = drain(provider, CHUNK)

        assertArrayEquals(source.copyOfRange(RESUME_AT, TOTAL), written)
    }

    @Test
    fun `a source shorter than the declared length stops instead of looping forever`() {
        val short = ByteArray(SHORT) { it.toByte() }
        val provider = ContentChunkProvider(ByteArrayInputStream(short), 0, TOTAL.toLong())

        val written = drain(provider, CHUNK)

        assertArrayEquals(short, written)
        assertFalse(provider.isAvailable())
        assertEquals(0, provider.bytesLeft())
    }

    @Test
    fun `never asks for more than the declared length even if the source is longer`() {
        val long = ByteArray(TOTAL * 2) { it.toByte() }
        val provider = ContentChunkProvider(ByteArrayInputStream(long), 0, TOTAL.toLong())

        val written = drain(provider, CHUNK)

        assertEquals(TOTAL, written.size)
        assertTrue(written.contentEquals(long.copyOfRange(0, TOTAL)))
    }

    private companion object {
        const val TOTAL = 1000
        const val CHUNK = 256
        const val RESUME_AT = 400
        const val SHORT = 300
        const val MAX_ROUNDS = 50
    }
}
