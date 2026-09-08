package com.jjw.easygallery.core.data.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jjw.easygallery.core.data.remote.RemoteStorageException
import com.jjw.easygallery.core.data.remote.StorageRegistry
import com.jjw.easygallery.core.data.upload.work.UploadNotifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/**
 * 원격 파일 하나를 기기 MediaStore 로 내려받는다(`docs/DRIVE_FILE_CRUD.md` §9).
 * 파일마다 워크 하나(유니크 이름 `download-<entryId>`), 네트워크 오류는 [MAX_ATTEMPTS] 까지 백오프 재시도.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val storages: StorageRegistry,
    private val saver: MediaStoreSaver,
    private val notifications: UploadNotifications,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entryId = inputData.getString(KEY_ENTRY_ID) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: entryId
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "application/octet-stream"
        val size = inputData.getLong(KEY_SIZE, -1L)
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        notifications.ensureChannel()
        updateForeground(name, 0f)
        return try {
            val storage = storages.storage(accountId)
            val copiedBytes = MutableStateFlow(0L)
            coroutineScope {
                // 저장기는 블로킹 콜백만 주므로, 진행 알림은 별도 코루틴이 상태를 보고 갱신한다
                val progressJob = launch {
                    copiedBytes.collect { copied ->
                        if (size > 0) updateForeground(name, (copied.toFloat() / size).coerceIn(0f, 1f))
                    }
                }
                withContext(Dispatchers.IO) {
                    storage.openDownload(entryId).use { input ->
                        var lastShown = 0L
                        saver.save(name, mimeType, input) { copied ->
                            if (copied - lastShown > PROGRESS_STEP_BYTES) {
                                lastShown = copied
                                copiedBytes.value = copied
                            }
                        }
                    }
                }
                progressJob.cancel()
            }
            notifications.showDownloadResult(name, success = true)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteStorageException) {
            fail(name, e, retry = e.httpCode == null || e.httpCode >= HTTP_SERVER_ERROR)
        } catch (e: IOException) {
            fail(name, e, retry = true)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            fail(name, e, retry = false)
        }
    }

    private fun fail(name: String, e: Exception, retry: Boolean): Result {
        Timber.w(e, "download failed: %s (attempt %d)", name, runAttemptCount)
        if (retry && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
        notifications.showDownloadResult(name, success = false)
        return Result.failure()
    }

    private suspend fun updateForeground(name: String, fraction: Float) {
        try {
            setForeground(notifications.downloadForegroundInfo(name, fraction))
        } catch (e: IllegalStateException) {
            Timber.w(e, "setForeground rejected")
        }
    }

    companion object {
        const val KEY_ACCOUNT_ID = "accountId"
        const val KEY_ENTRY_ID = "entryId"
        const val KEY_NAME = "name"
        const val KEY_MIME_TYPE = "mimeType"
        const val KEY_SIZE = "size"
        const val MAX_ATTEMPTS = 3
        private const val HTTP_SERVER_ERROR = 500
        private const val PROGRESS_STEP_BYTES = 512 * 1024L

        fun inputData(accountId: String?, entryId: String, name: String, mimeType: String, size: Long?) = workDataOf(
            KEY_ACCOUNT_ID to accountId,
            KEY_ENTRY_ID to entryId,
            KEY_NAME to name,
            KEY_MIME_TYPE to mimeType,
            KEY_SIZE to (size ?: -1L),
        )
    }
}
