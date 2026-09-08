package com.jjw.easygallery.core.data.media

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.jjw.easygallery.core.common.di.AppDispatcher
import com.jjw.easygallery.core.common.di.Dispatcher
import com.jjw.easygallery.core.domain.model.MediaDetails
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
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
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

    private val isApi30 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override val supportsTrashAndFavorites: Boolean = isApi30
    override val mutationsCompleteOnConsent: Boolean = isApi30

    /**
     * 최초 1회 즉시 조회하고, 이후 MediaStore 변경은 디바운스해서 재조회한다.
     * 변경 폭주 중에는 mapLatest 가 진행 중인 쿼리를 취소한다.
     */
    override fun observeMedia(filter: MediaFilter): Flow<List<MediaItem>> =
        merge(flowOf(Unit), mediaChanges().debounce(CHANGE_DEBOUNCE_MILLIS))
            .mapLatest { queryAll(filter) }
            .flowOn(ioDispatcher)

    // ---------- 자동 백업 스캔 ----------

    override suspend fun queryAddedSince(
        sinceSeconds: Long,
        relativePaths: Set<String>,
        includeVideos: Boolean,
    ): List<MediaItem> = withContext(ioDispatcher) {
        if (relativePaths.isEmpty()) return@withContext emptyList()
        val types = buildList {
            add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
            if (includeVideos) add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
        }
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${types.joinToString { "?" }})" +
            " AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?" +
            " AND ${MediaStore.MediaColumns.RELATIVE_PATH} IN (${relativePaths.joinToString { "?" }})"
        val args = (types.map { it.toString() } + sinceSeconds.toString() + relativePaths.toList()).toTypedArray()
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} ASC"
        val items = ArrayList<MediaItem>()
        resolver.query(collectionUri, projection, selection, args, sortOrder)?.use { cursor ->
            val reader = CursorReader(cursor, isApi30)
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                reader.read()?.let(items::add)
            }
        }
        Timber.d("auto-backup scan since=%d paths=%d → %d items", sinceSeconds, relativePaths.size, items.size)
        items
    }

    // ---------- 상세 정보 ----------

    override suspend fun readDetails(item: MediaItem): MediaDetails = withContext(ioDispatcher) {
        if (item.isVideo) return@withContext MediaDetails()
        try {
            // 위치 정보가 지워지지 않은 원본을 요청한다 (ACCESS_MEDIA_LOCATION 권한 필요)
            val uri = runCatching { MediaStore.setRequireOriginal(item.uri) }.getOrDefault(item.uri)
            resolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = FloatArray(2)
                val hasLocation = exif.getLatLong(latLong)
                MediaDetails(
                    cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE),
                    cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL),
                    aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER),
                    exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
                    isoSensitivity = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS),
                    focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH),
                    latitude = if (hasLocation) latLong[0].toDouble() else null,
                    longitude = if (hasLocation) latLong[1].toDouble() else null,
                )
            } ?: MediaDetails()
        } catch (e: IOException) {
            Timber.w(e, "EXIF 읽기 실패: %s", item.displayName)
            MediaDetails()
        } catch (e: SecurityException) {
            Timber.w(e, "EXIF 원본 접근 거부: %s", item.displayName)
            MediaDetails()
        }
    }

    // ---------- 편집 ----------

    override suspend fun requestDelete(items: List<MediaItem>): MediaMutation = withContext(ioDispatcher) {
        if (isApi30) {
            MediaMutation.NeedsConsent(MediaStore.createDeleteRequest(resolver, items.uris()).intentSender)
        } else {
            legacyMutation(items) { resolver.delete(it.uri, null, null) }
        }
    }

    override suspend fun requestTrash(items: List<MediaItem>, trashed: Boolean): MediaMutation =
        withContext(ioDispatcher) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaMutation.NeedsConsent(MediaStore.createTrashRequest(resolver, items.uris(), trashed).intentSender)
            } else {
                error(UNSUPPORTED_MESSAGE)
            }
        }

    override suspend fun requestFavorite(items: List<MediaItem>, favorite: Boolean): MediaMutation =
        withContext(ioDispatcher) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaMutation.NeedsConsent(
                    MediaStore.createFavoriteRequest(resolver, items.uris(), favorite).intentSender,
                )
            } else {
                error(UNSUPPORTED_MESSAGE)
            }
        }

    override suspend fun requestWrite(items: List<MediaItem>): MediaMutation = withContext(ioDispatcher) {
        if (isApi30) {
            MediaMutation.NeedsConsent(MediaStore.createWriteRequest(resolver, items.uris()).intentSender)
        } else {
            MediaMutation.Done(0)
        }
    }

    override suspend fun rename(item: MediaItem, newDisplayName: String): MediaMutation = withContext(ioDispatcher) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName) }
        legacyMutation(listOf(item)) { resolver.update(it.uri, values, null, null) }
    }

    override suspend fun move(items: List<MediaItem>, relativePath: String): MediaMutation =
        withContext(ioDispatcher) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }
            legacyMutation(items) { resolver.update(it.uri, values, null, null) }
        }

    /**
     * 항목별로 변경을 시도한다. API 29 에서 소유하지 않은 파일은 [RecoverableSecurityException] 이 나며,
     * 그 안의 PendingIntent 로 동의를 받은 뒤 같은 요청을 다시 호출해야 한다.
     * API 30+ 에서는 사전에 createWriteRequest 동의를 받았으므로 그대로 성공한다.
     */
    private inline fun legacyMutation(items: List<MediaItem>, op: (MediaItem) -> Int): MediaMutation {
        var affected = 0
        for (item in items) {
            try {
                affected += op(item)
            } catch (e: RecoverableSecurityException) {
                Timber.i("consent required for %s", item.displayName)
                return MediaMutation.NeedsConsent(e.userAction.actionIntent.intentSender)
            }
        }
        return MediaMutation.Done(affected)
    }

    private fun List<MediaItem>.uris() = map { it.uri }

    // ---------- 조회 ----------

    private fun mediaChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(collectionUri, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    private suspend fun queryAll(filter: MediaFilter): List<MediaItem> {
        if (filter != MediaFilter.All && !isApi30) return emptyList()

        val selection = StringBuilder("${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)")
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        if (filter == MediaFilter.Favorites) selection.append(" AND ${MediaStore.MediaColumns.IS_FAVORITE} = 1")
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        val cursor = if (isApi30) {
            queryApi30(selection.toString(), selectionArgs, sortOrder, filter == MediaFilter.Trashed)
        } else {
            resolver.query(collectionUri, projection, selection.toString(), selectionArgs, sortOrder)
        }

        val items = ArrayList<MediaItem>()
        cursor?.use {
            items.ensureCapacity(it.count)
            val reader = CursorReader(it, isApi30)
            while (it.moveToNext()) {
                currentCoroutineContext().ensureActive()
                reader.read()?.let(items::add)
            }
        }
        Timber.d("MediaStore query(%s): %d items", filter, items.size)
        // DATE_TAKEN 이 비어 있는 행은 DATE_ADDED 로 대체했으므로 메모리에서 다시 정렬
        return items.sortedByDescending { it.dateTakenMillis }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun queryApi30(selection: String, args: Array<String>, sortOrder: String, trashedOnly: Boolean): Cursor? {
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            if (trashedOnly) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        return resolver.query(collectionUri, projection, queryArgs, null)
    }

    private val projection: Array<String> = buildList {
        add(MediaStore.MediaColumns._ID)
        add(MediaStore.MediaColumns.DISPLAY_NAME)
        add(MediaStore.MediaColumns.MIME_TYPE)
        add(MediaStore.MediaColumns.SIZE)
        add(MediaStore.MediaColumns.DATE_TAKEN)
        add(MediaStore.MediaColumns.DATE_ADDED)
        add(MediaStore.MediaColumns.BUCKET_ID)
        add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        add(MediaStore.MediaColumns.RELATIVE_PATH)
        add(MediaStore.Files.FileColumns.MEDIA_TYPE)
        add(MediaStore.MediaColumns.WIDTH)
        add(MediaStore.MediaColumns.HEIGHT)
        add(MediaStore.MediaColumns.DURATION)
        if (isApi30) {
            add(MediaStore.MediaColumns.IS_FAVORITE)
            add(MediaStore.MediaColumns.IS_TRASHED)
        }
    }.toTypedArray()

    private class CursorReader(private val cursor: Cursor, hasApi30Columns: Boolean) {
        private val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        private val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        private val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        private val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        private val takenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        private val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        private val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
        private val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        private val relativePathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        private val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        private val widthCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        private val heightCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        private val durationCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        private val favoriteCol = optionalColumn(hasApi30Columns, MediaStore.MediaColumns.IS_FAVORITE)
        private val trashedCol = optionalColumn(hasApi30Columns, MediaStore.MediaColumns.IS_TRASHED)

        private fun optionalColumn(present: Boolean, name: String) = if (present) cursor.getColumnIndex(name) else -1

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
            val dateAdded = cursor.getLong(addedCol)
            val dateTaken = cursor.getLong(takenCol).takeIf { it > 0 } ?: dateAdded * MILLIS_PER_SECOND

            return MediaItem(
                id = id,
                uri = ContentUris.withAppendedId(baseUri, id),
                displayName = cursor.getString(nameCol) ?: "",
                type = type,
                mimeType = cursor.getString(mimeCol) ?: "",
                sizeBytes = cursor.getLong(sizeCol),
                dateTakenMillis = dateTaken,
                dateAddedSeconds = dateAdded,
                bucketId = cursor.getLong(bucketIdCol),
                bucketName = cursor.getString(bucketNameCol) ?: "",
                relativePath = cursor.getString(relativePathCol) ?: "",
                width = cursor.getInt(widthCol),
                height = cursor.getInt(heightCol),
                durationMillis = if (type == MediaType.VIDEO) cursor.getLong(durationCol) else null,
                isFavorite = favoriteCol >= 0 && cursor.getInt(favoriteCol) == 1,
                isTrashed = trashedCol >= 0 && cursor.getInt(trashedCol) == 1,
            )
        }
    }

    private companion object {
        const val UNSUPPORTED_MESSAGE = "휴지통·즐겨찾기는 Android 11 이상에서만 지원됩니다"
        const val CHANGE_DEBOUNCE_MILLIS = 300L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
