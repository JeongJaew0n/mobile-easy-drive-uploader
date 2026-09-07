package com.jjw.easygallery.core.data.upload.work

import android.content.Context
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jjw.easygallery.core.data.auth.AuthException
import com.jjw.easygallery.core.data.upload.DriveUploadException
import com.jjw.easygallery.core.data.upload.DriveUploader
import com.jjw.easygallery.core.data.upload.SessionExpiredException
import com.jjw.easygallery.core.data.upload.SessionStatus
import com.jjw.easygallery.core.data.upload.UploadEvent
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.data.upload.UploadSource
import com.jjw.easygallery.core.domain.model.UploadTask
import com.jjw.easygallery.core.domain.usecase.GetUploadFolderUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 업로드 큐를 순서대로 비운다. 유니크 워크 하나가 큐 전체를 담당하고,
 * 일시적 오류(네트워크·5xx)는 Result.retry() 로 백오프 후 이어서 처리한다.
 */
@HiltWorker
@Suppress("TooGenericExceptionCaught") // 알 수 없는 오류는 해당 항목만 FAILED 처리하고 다음으로 넘어간다
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val queue: UploadQueueRepository,
    private val uploader: DriveUploader,
    private val getUploadFolder: GetUploadFolderUseCase,
    private val notifications: UploadNotifications,
) : CoroutineWorker(appContext, params) {

    private var succeeded = 0
    private var failed = 0
    private var total = 0
    private var lastProgressAt = 0L

    override suspend fun doWork(): Result {
        notifications.ensureChannel()
        total = queue.countUnfinished()
        var result: Result? = null
        while (result == null) {
            val task = queue.nextUnfinished()
            if (task == null) {
                if (succeeded + failed > 0) notifications.showSummary(succeeded, failed)
                result = Result.success()
            } else {
                when (process(task)) {
                    Outcome.Success -> succeeded++
                    Outcome.Failed -> failed++
                    Outcome.RetryLater -> {
                        Timber.i("upload paused for retry (succeeded=%d failed=%d)", succeeded, failed)
                        result = Result.retry()
                    }
                    Outcome.SignInRequired -> {
                        notifications.showSignInRequired()
                        result = Result.failure()
                    }
                }
            }
        }
        return result
    }

    private suspend fun process(task: UploadTask): Outcome {
        queue.markRunning(task.id, task.attemptCount)
        updateForeground(task, task.fraction)
        return try {
            uploadTask(task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFailure(task, e)
        }
    }

    private suspend fun uploadTask(task: UploadTask): Outcome {
        val folderId = task.folderId ?: getUploadFolder().also { queue.setFolder(task.id, it) }.id
        val source = task.toSource()
        val length = uploader.resolveLength(source)
        val (sessionUri, offset) = resolveSession(task, source, folderId, length) ?: return Outcome.Success

        var driveFileId: String? = null
        uploader.upload(source, sessionUri, offset, length).collect { event ->
            when (event) {
                is UploadEvent.Progress -> reportProgress(task, event)
                is UploadEvent.Completed -> driveFileId = event.driveFileId
            }
        }
        queue.complete(task.id, requireNotNull(driveFileId) { "업로드가 파일 ID 없이 끝났습니다" })
        return Outcome.Success
    }

    /**
     * 이어 올릴 (세션 URI, 시작 오프셋). 기존 세션이 이미 완료 상태면 완료 처리 후 null.
     * 세션이 만료됐거나 없으면 새로 만든다.
     */
    private suspend fun resolveSession(
        task: UploadTask,
        source: UploadSource,
        folderId: String,
        length: Long,
    ): Pair<String, Long>? {
        val existing = task.sessionUri
        if (existing != null) {
            when (val status = uploader.queryStatus(existing, length)) {
                is SessionStatus.Complete -> {
                    queue.complete(task.id, status.driveFileId)
                    return null
                }
                is SessionStatus.Incomplete -> return existing to status.nextByte
                SessionStatus.Expired -> Timber.w("session expired, restarting: %s", task.displayName)
            }
        }
        val fresh = uploader.startSession(source, folderId, length)
        queue.setSession(task.id, fresh)
        return fresh to 0L
    }

    private suspend fun handleFailure(task: UploadTask, e: Exception): Outcome = when (e) {
        is AuthException -> {
            Timber.w(e, "sign-in required")
            queue.markPending(task.id, task.attemptCount)
            Outcome.SignInRequired
        }
        // 원본이 삭제됨 — 재시도 의미 없음
        is FileNotFoundException -> failPermanently(task, e)
        is SessionExpiredException -> {
            queue.setSession(task.id, null)
            retryTransient(task, e)
        }
        is DriveUploadException -> {
            val isClientError = e.httpCode?.let { it in CLIENT_ERROR_RANGE } == true
            if (isClientError) failPermanently(task, e) else retryTransient(task, e)
        }
        is IOException -> retryTransient(task, e)
        else -> failPermanently(task, e)
    }

    private suspend fun failPermanently(task: UploadTask, e: Exception): Outcome {
        Timber.e(e, "upload failed permanently: %s", task.displayName)
        queue.fail(task.id, e.message ?: e.toString())
        return Outcome.Failed
    }

    private suspend fun retryTransient(task: UploadTask, e: Exception): Outcome {
        val attempts = task.attemptCount + 1
        if (attempts >= MAX_ATTEMPTS) {
            Timber.e(e, "upload gave up after %d attempts: %s", attempts, task.displayName)
            queue.fail(task.id, e.message ?: e.toString())
            return Outcome.Failed
        }
        Timber.w(e, "upload transient failure (%d/%d): %s", attempts, MAX_ATTEMPTS, task.displayName)
        queue.markPending(task.id, attempts)
        return Outcome.RetryLater
    }

    private suspend fun reportProgress(task: UploadTask, event: UploadEvent.Progress) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressAt < PROGRESS_INTERVAL_MILLIS && event.bytesSent < event.totalBytes) return
        lastProgressAt = now
        queue.updateProgress(task.id, event.bytesSent)
        updateForeground(task, event.fraction)
    }

    private suspend fun updateForeground(task: UploadTask, fraction: Float) {
        try {
            setForeground(notifications.progressForegroundInfo(succeeded + failed, total, task.displayName, fraction))
        } catch (e: IllegalStateException) {
            // 백그라운드 FGS 시작 제한 등 — 알림 없이 계속 진행한다
            Timber.w(e, "setForeground rejected")
        }
    }

    private fun UploadTask.toSource() = UploadSource(
        mediaId = mediaId,
        uri = uri,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
    )

    private enum class Outcome { Success, Failed, RetryLater, SignInRequired }

    companion object {
        const val UNIQUE_WORK_NAME = "upload-queue"
        const val MAX_ATTEMPTS = 5
        private const val PROGRESS_INTERVAL_MILLIS = 1_000L
        private val CLIENT_ERROR_RANGE = 400..499
    }
}
