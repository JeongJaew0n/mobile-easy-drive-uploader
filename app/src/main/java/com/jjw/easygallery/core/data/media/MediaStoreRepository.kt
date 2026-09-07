package com.jjw.easygallery.core.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.jjw.easygallery.core.common.di.AppDispatcher
import com.jjw.easygallery.core.common.di.Dispatcher
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStoreRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : MediaRepository {

    private val resolver: ContentResolver get() = context.contentResolver

    // 사진·영상을 한 번에 조회하기 위해 Files 컬렉션 사용
    private val collectionUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /**
     * 최초 1회 즉시 조회하고, 이후 MediaStore 변경은 디바운스해서 재조회한다.
     * 변경 폭주 중에는 mapLatest 가 진행 중인 쿼리를 취소한다.
     */
    override fun observeMedia(): Flow<List<MediaItem>> =
        merge(flowOf(Unit), mediaChanges().debounce(CHANGE_DEBOUNCE_MILLIS))
            .mapLatest { queryAll() }
            .flowOn(ioDispatcher)

    private fun mediaChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(collectionUri, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    private suspend fun queryAll(): List<MediaItem> {
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        val items = ArrayList<MediaItem>()
        resolver.query(collectionUri, PROJECTION, selection, selectionArgs, sortOrder)?.use { cursor ->
            items.ensureCapacity(cursor.count)
            val reader = CursorReader(cursor)
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                reader.read()?.let(items::add)
            }
        }
        Timber.d("MediaStore query: %d items", items.size)
        // DATE_TAKEN 이 비어 있는 행은 DATE_ADDED 로 대체했으므로 메모리에서 다시 정렬
        return items.sortedByDescending { it.dateTakenMillis }
    }

    private class CursorReader(private val cursor: Cursor) {
        private val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        private val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        private val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        private val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        private val takenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        private val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        private val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
        private val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        private val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        private val widthCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        private val heightCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        private val durationCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)

        fun read(): MediaItem? {
            val type = when (cursor.getInt(typeCol)) {
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaType.IMAGE
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaType.VIDEO
                else -> return null
            }
            val id = cursor.getLong(idCol)
            val baseUri = if (type == MediaType.IMAGE) {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val dateTaken = cursor.getLong(takenCol).takeIf { it > 0 }
                ?: cursor.getLong(addedCol) * MILLIS_PER_SECOND

            return MediaItem(
                id = id,
                uri = ContentUris.withAppendedId(baseUri, id),
                displayName = cursor.getString(nameCol) ?: "",
                type = type,
                mimeType = cursor.getString(mimeCol) ?: "",
                sizeBytes = cursor.getLong(sizeCol),
                dateTakenMillis = dateTaken,
                bucketId = cursor.getLong(bucketIdCol),
                bucketName = cursor.getString(bucketNameCol) ?: "",
                width = cursor.getInt(widthCol),
                height = cursor.getInt(heightCol),
                durationMillis = if (type == MediaType.VIDEO) cursor.getLong(durationCol) else null,
            )
        }
    }

    private companion object {
        const val CHANGE_DEBOUNCE_MILLIS = 300L
        const val MILLIS_PER_SECOND = 1_000L

        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
        )
    }
}
