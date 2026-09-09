package com.jjw.easygallery.core.data.remote.smb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream

class ContentChunkProviderTest {

    private val source = ByteArray(TOTAL) { it.toByte() }

    /**
     * smbj 가 하는 일을 흉내 낸다: `prepareWrite` 로 미리 읽게 한 뒤, 그때 `bytesLeft()` 가 약속한 만큼을
     * `getChunk` 가 실제로 채우는지 확인한다. 이 둘이 어긋나면 패킷 Length 와 본문이 맞지 않는다.
     */
    private fun drain(provider: ContentChunkProvider, chunkSize: Int): ByteArray {
        val written = ArrayList<Byte>()
        var rounds = 0
        while (provider.isAvailable()) {
            check(rounds++ < MAX_ROUNDS) { "무한 루프: offset=${provider.offset}" }
            provider.prepareWrite(chunkSize)
            val promised = provider.bytesLeft().coerceAtMost(chunkSize)
            val chunk = ByteArray(chunkSize)
            val size = provider.writeInto(chunk)
            assertEquals("bytesLeft 가 약속한 양과 실제로 채운 양이 달라 패킷이 깨진다", promised, size)
            written.addAll(chunk.take(size))
        }
        return written.toByteArray()
    }

    @Test
    fun `sends the whole source and reports progress`() {
        val progress = ArrayList<Long>()
        val provider = ContentChunkProvider(ByteArrayInputStream(source), 0) { progress += it }

        val written = drain(provider, CHUNK)

        assertArrayEquals(source, written)
        assertEquals(TOTAL.toLong(), progress.last())
        assertFalse(provider.isAvailable())
    }

    @Test
    fun `resuming skips to the offset and sends only the rest`() {
        val provider = ContentChunkProvider(ByteArrayInputStream(source), RESUME_AT.toLong())

        val written = drain(provider, CHUNK)

        assertArrayEquals(source.copyOfRange(RESUME_AT, TOTAL), written)
        assertEquals(TOTAL.toLong(), provider.offset)
    }

    @Test
    fun `an empty remainder finishes immediately instead of looping`() {
        val provider = ContentChunkProvider(ByteArrayInputStream(source), TOTAL.toLong())

        val written = drain(provider, CHUNK)

        assertEquals(0, written.size)
        assertFalse(provider.isAvailable())
    }

    private companion object {
        const val TOTAL = 1000
        const val CHUNK = 256
        const val RESUME_AT = 400
        const val MAX_ROUNDS = 50
    }
}
