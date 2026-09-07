package com.jjw.easygallery.core.ui.image

import android.content.ContentResolver
import android.provider.MediaStore
import android.util.Size
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import coil3.toAndroidUri
import okio.buffer
import okio.source
import timber.log.Timber
import java.io.IOException

/**
 * MediaStore content URI 는 시스템이 캐시한 썸네일([ContentResolver.loadThumbnail])을 우선 사용한다.
 * 원본을 디코딩하는 것보다 훨씬 빠르고, 영상도 프레임 추출 없이 썸네일을 얻는다.
 * 실패하면 원본 스트림을 넘겨 Coil 기본 디코더가 처리하게 한다.
 */
class MediaStoreThumbnailFetcher(
    private val uri: Uri,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val androidUri = uri.toAndroidUri()
        val resolver = options.context.contentResolver
        val size = Size(
            options.size.width.pxOrElse { DEFAULT_THUMBNAIL_PX },
            options.size.height.pxOrElse { DEFAULT_THUMBNAIL_PX },
        )
        return try {
            val bitmap = resolver.loadThumbnail(androidUri, size, null)
            ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
        } catch (e: IOException) {
            Timber.w(e, "loadThumbnail 실패, 원본으로 대체: %s", androidUri)
            val stream = resolver.openInputStream(androidUri) ?: throw e
            SourceFetchResult(
                source = ImageSource(stream.source().buffer(), options.fileSystem),
                mimeType = resolver.getType(androidUri),
                dataSource = DataSource.DISK,
            )
        }
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != ContentResolver.SCHEME_CONTENT || data.authority != MediaStore.AUTHORITY) return null
            return MediaStoreThumbnailFetcher(data, options)
        }
    }

    private companion object {
        const val DEFAULT_THUMBNAIL_PX = 512
    }
}
