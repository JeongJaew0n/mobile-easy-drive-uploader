package com.jjw.easygallery.core.data.upload

import android.content.Context
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.buffer
import okio.source
import java.io.FileNotFoundException

/**
 * content:// URI 를 [offset] 부터 스트리밍으로 전송하면서 진행률(절대 바이트)을 알린다.
 * 재전송 불가(isOneShot) — 실패 시 상위에서 세션 상태를 조회해 다시 이어 올린다.
 */
class ContentUriRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val mediaType: MediaType,
    private val offset: Long,
    private val totalLength: Long,
    private val onProgress: (bytesSentTotal: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = totalLength - offset

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val input = context.openOriginalStream(uri) ?: throw FileNotFoundException(uri.toString())
        input.source().buffer().use { source ->
            if (offset > 0) source.skip(offset)
            var sent = offset
            var sinceReport = 0L
            while (sent < totalLength) {
                val read = source.read(sink.buffer, minOf(SEGMENT_SIZE, totalLength - sent))
                if (read == -1L) break
                sink.emitCompleteSegments()
                sent += read
                sinceReport += read
                if (sinceReport >= REPORT_INTERVAL_BYTES) {
                    sinceReport = 0
                    onProgress(sent)
                }
            }
            onProgress(sent)
        }
    }

    private companion object {
        const val SEGMENT_SIZE = 64L * 1024
        const val REPORT_INTERVAL_BYTES = 256L * 1024
    }
}
