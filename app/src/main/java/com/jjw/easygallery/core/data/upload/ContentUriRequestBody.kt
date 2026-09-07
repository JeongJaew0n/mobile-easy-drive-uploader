package com.jjw.easygallery.core.data.upload

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.FileNotFoundException

/** content:// URI 를 스트리밍으로 전송하면서 진행률을 알린다. 재전송 불가(isOneShot). */
class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val mediaType: MediaType,
    private val length: Long,
    private val onProgress: (bytesSent: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = length

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        input.source().use { source ->
            var sent = 0L
            var sinceReport = 0L
            while (true) {
                val read = source.read(sink.buffer, SEGMENT_SIZE)
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
