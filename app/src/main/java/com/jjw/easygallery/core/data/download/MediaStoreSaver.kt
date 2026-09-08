package com.jjw.easygallery.core.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 스트림을 MediaStore 에 새 항목으로 저장한다(`IS_PENDING` 으로 쓰는 동안 갤러리에 안 보이게).
 * 이미지 → Pictures/Easy Gallery, 영상 → Movies/Easy Gallery, 그 외 → Download/Easy Gallery.
 */
@Singleton
class MediaStoreSaver @Inject constructor(@param:ApplicationContext private val context: Context) {

    /** [onProgress] 는 복사한 바이트 수. 실패하면 만들어 둔 항목을 지우고 다시 던진다 */
    fun save(displayName: String, mimeType: String, input: InputStream, onProgress: (Long) -> Unit = {}): Uri {
        val resolver = context.contentResolver
        val (collection, relativePath) = target(mimeType)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert 실패: $displayName")
        val result = runCatching {
            val out = requireNotNull(resolver.openOutputStream(uri)) { "MediaStore 출력 스트림을 열 수 없습니다" }
            out.use { copy(input, it, onProgress) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        result.onFailure { runCatching { resolver.delete(uri, null, null) } }
        result.getOrThrow()
        return uri
    }

    private fun copy(input: InputStream, out: OutputStream, onProgress: (Long) -> Unit) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            copied += n
            onProgress(copied)
        }
    }

    private fun target(mimeType: String): Pair<Uri, String> = when {
        mimeType.startsWith("image/") ->
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/$FOLDER"
        mimeType.startsWith("video/") ->
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/$FOLDER"
        else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Download/$FOLDER"
    }

    private companion object {
        const val FOLDER = "Easy Gallery"
        const val BUFFER_SIZE = 256 * 1024
    }
}
