package com.jjw.easygallery.core.data.remote.smb

import com.hierynomus.smbj.io.InputStreamByteChunkProvider
import java.io.InputStream

/**
 * content:// 스트림을 SMB 쓰기 청크로 넘긴다. 재개 업로드를 위해 [startOffset] 부터 보낸다.
 *
 * smbj 의 [InputStreamByteChunkProvider] 를 그대로 쓴다 — `SMB2WriteRequest` 는 `bytesLeft()` 로
 * **패킷 Length 를 먼저 써 넣은 뒤** 본문을 채우므로, 직접 구현하면 EOF 에서 선언 길이와 본문이
 * 어긋난 WRITE 가 나간다. 상위 클래스가 `prepareWrite` 로 미리 읽어 두어 그 둘을 일치시킨다.
 */
internal class ContentChunkProvider(
    input: InputStream,
    startOffset: Long,
    private val onProgress: (Long) -> Unit = {},
) : InputStreamByteChunkProvider(input.also { it.skipExactly(startOffset) }) {

    init {
        offset = startOffset
    }

    override fun getChunk(chunk: ByteArray): Int {
        val size = super.getChunk(chunk)
        onProgress(offset + size)
        return size
    }

    /** smbj 가 하는 일(청크를 받아 offset 전진)을 테스트에서 재현하기 위한 창구 */
    fun writeInto(chunk: ByteArray): Int {
        val size = getChunk(chunk)
        offset += size
        return size
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
