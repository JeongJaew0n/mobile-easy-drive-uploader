package com.jjw.easygallery.core.data.upload

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.jjw.easygallery.core.domain.model.VideoCompression
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 업로드 전 영상 변환. 원본은 건드리지 않고 캐시에 사본을 만든다. */
interface VideoCompressor {
    /**
     * 압축이 필요 없으면(사진, 프리셋 ORIGINAL, 이미 작음) null. 아니면 캐시 파일을 가리키는 새 [UploadSource].
     * 같은 항목·프리셋의 캐시가 있으면 재사용한다(워커 재시도·세션 재개 시 동일 바이트 보장).
     */
    suspend fun compress(
        source: UploadSource,
        preset: VideoCompression,
        onProgress: suspend (fraction: Float) -> Unit = {},
    ): UploadSource?

    /** 업로드가 끝났거나 영구 실패했을 때 캐시 사본 삭제 */
    fun cleanup(source: UploadSource)
}

class CompressionException(message: String, cause: Throwable? = null) : IOException(message, cause)

@Singleton
@androidx.annotation.OptIn(UnstableApi::class) // Transformer/ExportException 전체가 UnstableApi
class Media3VideoCompressor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : VideoCompressor {

    private val cacheDir: File get() = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }

    override suspend fun compress(
        source: UploadSource,
        preset: VideoCompression,
        onProgress: suspend (Float) -> Unit,
    ): UploadSource? {
        val shortSide = targetShortSide(source, preset) ?: return null
        val output = File(cacheDir, "${source.mediaId}_$shortSide.mp4")
        if (output.length() == 0L) {
            transcodeTo(source, output, shortSide, preset.bitrateBps ?: DEFAULT_BITRATE, onProgress)
        } else {
            Timber.d("compressed cache hit: %s", output.name)
        }
        return source.toCompressed(output)
    }

    /** 영상이 아니거나 프리셋이 원본이거나 이미 목표 이하면 null */
    private fun targetShortSide(source: UploadSource, preset: VideoCompression): Int? {
        if (!source.mimeType.startsWith("video/")) return null
        val shortSide = preset.shortSidePx ?: return null
        return shortSide.takeIf { preset.shouldCompress(source.width, source.height) }
    }

    private suspend fun transcodeTo(
        source: UploadSource,
        output: File,
        shortSide: Int,
        bitrate: Int,
        onProgress: suspend (Float) -> Unit,
    ) {
        val temp = File(cacheDir, "${output.name}.part")
        temp.delete()
        try {
            transcode(source.uri, temp, shortSide, bitrate, onProgress)
        } catch (e: ExportException) {
            temp.delete()
            throw CompressionException("영상 압축 실패: ${e.message}", e)
        }
        if (!temp.renameTo(output)) throw CompressionException("압축 결과 저장 실패")
        Timber.i("compressed %s: %d → %d bytes", source.displayName, source.sizeBytes, output.length())
    }

    override fun cleanup(source: UploadSource) {
        if (source.uri.scheme != "file") return
        val file = File(requireNotNull(source.uri.path))
        if (file.parentFile == cacheDir && file.delete()) Timber.d("deleted compressed cache %s", file.name)
    }

    /** Transformer 는 Looper 가 있는 스레드에서 만들어야 해서 Main 에서 돌린다(실제 인코딩은 내부 스레드). */
    private suspend fun transcode(
        input: Uri,
        output: File,
        shortSide: Int,
        bitrate: Int,
        onProgress: suspend (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        coroutineScope {
            var transformerRef: Transformer? = null
            val progressJob = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    val t = transformerRef
                    if (t != null && t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / PERCENT)
                    }
                    delay(PROGRESS_POLL_MILLIS)
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    val encoderFactory = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .build()
                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(encoderFactory)
                        .addListener(
                            object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (cont.isActive) cont.resume(Unit)
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException,
                                ) {
                                    if (cont.isActive) cont.resumeWithException(exportException)
                                }
                            },
                        )
                        .build()
                    transformerRef = transformer
                    val edited = EditedMediaItem.Builder(MediaItem.fromUri(input))
                        .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(shortSide))))
                        .build()
                    transformer.start(edited, output.absolutePath)
                    cont.invokeOnCancellation {
                        transformer.cancel()
                        output.delete()
                    }
                }
            } finally {
                progressJob.cancel()
            }
        }
    }

    private fun UploadSource.toCompressed(file: File) = copy(
        uri = Uri.fromFile(file),
        displayName = displayName.substringBeforeLast('.') + ".mp4",
        mimeType = "video/mp4",
        sizeBytes = file.length(),
    )

    private companion object {
        const val CACHE_DIR = "transcode"
        const val DEFAULT_BITRATE = 4_000_000
        const val PROGRESS_POLL_MILLIS = 500L
        const val PERCENT = 100f
    }
}
