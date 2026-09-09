package com.jjw.easygallery.core.data.remote.smb

import com.hierynomus.smbj.io.ByteChunkProvider
import java.io.InputStream

/**
 * content:// 스트림을 SMB 쓰기 청크로 넘긴다. [startOffset] 부터 [total] 까지만 보낸다(재개 업로드).
 *
 * smbj 의 `ByteChunkProvider.writeChunk` 는 [getChunk] 가 돌려준 수를 그대로 `offset` 에 더한다.
 * **음수를 돌려주면 offset 이 뒤로 가 무한 루프가 된다** — 원본이 예상보다 짧아도 0 을 돌려주고
 * [isAvailable] 로 끝났음을 알린다.
 */
internal class ContentChunkProvider(
    private val input: InputStream,
    startOffset: Long,
    private val total: Long,
    private val onProgress: (Long) -> Unit = {},
) : ByteChunkProvider() {

    /** 원본이 total 보다 짧아 더 읽을 게 없을 때 */
    private var exhausted = false

    init {
        offset = startOffset
        input.skipExactly(startOffset)
    }

    override fun prepareWrite(maxBytesToPrepare: Int) = Unit

    override fun isAvailable(): Boolean = !exhausted && offset < total

    override fun bytesLeft(): Int =
        if (exhausted) 0 else (total - offset).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** smbj 의 `writeChunk` 가 하는 일(청크를 받아 offset 전진)을 테스트에서 재현하기 위한 창구 */
    fun writeInto(chunk: ByteArray): Int {
        val size = getChunk(chunk)
        offset += size
        return size
    }

    override fun getChunk(chunk: ByteArray): Int {
        val want = minOf(chunk.size.toLong(), total - offset).toInt()
        if (want <= 0) {
            exhausted = true
            return 0
        }
        var read = 0
        while (read < want) {
            val n = input.read(chunk, read, want - read)
            if (n < 0) break
            read += n
        }
        if (read == 0) exhausted = true
        onProgress(offset + read)
        return read
    }
}

/** [offset] 바이트를 확실히 건너뛴다(스트림이 부분 skip 을 돌려줄 수 있다) */
internal fun InputStream.skipExactly(offset: Long) {
    var skipped = 0L
    while (skipped < offset) {
        val n = skip(offset - skipped)
        if (n <= 0) break
        skipped += n
    }
}
