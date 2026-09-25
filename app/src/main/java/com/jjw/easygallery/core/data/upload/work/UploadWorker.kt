package com.jjw.easygallery.core.data.upload.work

import android.content.Context
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jjw.easygallery.core.data.auth.AuthException
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.remote.RemoteStorageException
import com.jjw.easygallery.core.data.remote.RemoteUploader
import com.jjw.easygallery.core.data.remote.StorageRegistry
import com.jjw.easygallery.core.data.upload.CompressionException
import com.jjw.easygallery.core.data.upload.SessionExpiredException
import com.jjw.easygallery.core.data.upload.SessionStatus
import com.jjw.easygallery.core.data.upload.UploadEvent
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.data.upload.UploadSource
import com.jjw.easygallery.core.data.upload.VideoCompressor
import com.jjw.easygallery.core.domain.model.UploadTask
import com.jjw.easygallery.core.domain.usecase.GetUploadFolderUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

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
    private val ledger: UploadLedgerRepository,
    private val storages: StorageRegistry,
    private val getUploadFolder: GetUploadFolderUseCase,
    private val notifications: UploadNotifications,
    private val compressor: VideoCompressor,
    private val prefs: UserPreferencesRepository,
) : CoroutineWorker(appContext, params) {

    private var succeeded = 0
    private var failed = 0
    private var total = 0
    private var lastProgressAt = 0L

    /** 이번 실행에서 일시 오류로 미뤄둔 항목. 다시 집어 제자리걸음 하지 않도록 기억한다 */
    private val deferred = Collections.synchronizedSet(mutableSetOf<Long>())

    /**
     * 큐를 [PARALLELISM] 개 코루틴이 나눠 비운다. 장당 시간의 대부분이 서버를 기다리는 시간이라
     * 동시에 보내면 그대로 처리량이 는다 — `docs/UPLOAD_PERFORMANCE.md` §2-2.
     *
     * 멈춰야 하는 결과(재시도·로그인 필요)가 하나라도 나오면 나머지도 곧 멈춘다.
     */
    override suspend fun doWork(): Result {
        notifications.ensureChannel()
        // 앱이 죽어 RUNNING 인 채 남은 항목을 되살린다. 그러지 않으면 claimNext 가 영영 건너뛴다
        queue.releaseRunning()
        total = queue.countUnfinished()
        val stopResult = AtomicReference<Result?>(null)

        coroutineScope {
            repeat(PARALLELISM) {
                launch { drainQueue(stopResult) }
            }
        }

        stopResult.get()?.let { return it }
        // 일시 오류로 미뤄둔 것이 남았으면 백오프 후 이 워커가 다시 돈다
        if (deferred.isNotEmpty()) {
            Timber.i("upload deferred %d item(s) (succeeded=%d failed=%d)", deferred.size, succeeded, failed)
            return Result.retry()
        }
        if (succeeded + failed > 0) notifications.showSummary(succeeded, failed)
        return Result.success()
    }

    /**
     * 큐가 비거나 멈출 이유가 생길 때까지 한 건씩 집어 처리한다.
     *
     * 일시 오류는 그 항목만 미뤄두고 **계속 간다**. 병렬에서 한 건의 타임아웃으로 전체를 세우면
     * 나머지 수백 장이 백오프를 함께 기다리게 된다. 로그인 필요는 다르다 — 모든 항목이 같은
     * 이유로 실패할 것이므로 그때는 멈춘다.
     */
    private suspend fun drainQueue(stopResult: AtomicReference<Result?>) {
        while (stopResult.get() == null) {
            val task = queue.claimNext(deferred.toList()) ?: return
            when (process(task)) {
                Outcome.Success -> countSuccess()
                Outcome.Failed -> countFailure()
                Outcome.RetryLater -> deferred.add(task.id)
                Outcome.SignInRequired -> {
                    notifications.showSignInRequired()
                    stopResult.compareAndSet(null, Result.failure())
                }
            }
        }
    }

    @Synchronized
    private fun countSuccess() {
        succeeded++
    }

    @Synchronized
    private fun countFailure() {
        failed++
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
        val storage = storages.storage(task.accountId)
        val uploader = storage.uploader()
        // Drive 는 지정 폴더가 없으면 앱 루트 폴더를 만든다. 다른 저장소는 루트에 올린다
        val folderId = task.folderId ?: when (task.accountId) {
            null -> getUploadFolder().also { queue.setFolder(task.id, it) }.id
            else -> storage.rootId
        }
        // 영상은 설정에 따라 압축 사본을 올린다. 캐시가 재사용되므로 재시도·세션 재개에서도 같은 바이트.
        val source = compressor.compress(task.toSource(), prefs.current().videoCompression) { fraction ->
            updateForeground(task, fraction, compressing = true)
        } ?: task.toSource()
        val t0 = SystemClock.elapsedRealtime()
        val length = uploader.resolveLength(source)
        val t1 = SystemClock.elapsedRealtime()

        // 작은 파일은 왕복 한 번으로 끝낸다. 이어올리기를 포기하는 대신 세션 생성이 빠진다
        // (`docs/UPLOAD_PERFORMANCE.md` §2-1). 이미 세션이 있으면 그쪽을 이어간다.
        if (task.sessionUri == null && length in 1..WHOLE_UPLOAD_LIMIT) {
            val wholeId = uploader.uploadWhole(source, folderId, length)
            if (wholeId != null) {
                markUploaded(task, wholeId)
                compressor.cleanup(source)
                Timber.d(
                    "timing whole=%d total=%d",
                    SystemClock.elapsedRealtime() - t1,
                    SystemClock.elapsedRealtime() - t0,
                )
                return Outcome.Success
            }
        }

        val (sessionUri, offset) = resolveSession(uploader, task, source, folderId, length) ?: run {
            compressor.cleanup(source)
            return Outcome.Success
        }
        val t2 = SystemClock.elapsedRealtime()

        var driveFileId: String? = null
        uploader.upload(source, sessionUri, offset, length).collect { event ->
            when (event) {
                is UploadEvent.Progress -> reportProgress(task, event)
                is UploadEvent.Completed -> driveFileId = event.driveFileId
            }
        }
        val t3 = SystemClock.elapsedRealtime()
        val fileId = requireNotNull(driveFileId) { "업로드가 파일 ID 없이 끝났습니다" }
        markUploaded(task, fileId)
        compressor.cleanup(source)
        val t4 = SystemClock.elapsedRealtime()
        Timber.d("timing len=%d session=%d put=%d finish=%d total=%d", t1 - t0, t2 - t1, t3 - t2, t4 - t3, t4 - t0)
        return Outcome.Success
    }

    /**
     * 이어 올릴 (세션 URI, 시작 오프셋). 기존 세션이 이미 완료 상태면 완료 처리 후 null.
     * 세션이 만료됐거나 없으면 새로 만든다.
     */
    private suspend fun resolveSession(
        uploader: RemoteUploader,
        task: UploadTask,
        source: UploadSource,
        folderId: String,
        length: Long,
    ): Pair<String, Long>? {
        val existing = task.sessionUri
        if (existing != null) {
            when (val status = uploader.queryStatus(existing, length)) {
                is SessionStatus.Complete -> {
                    markUploaded(task, status.driveFileId)
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
        // 인코더 문제는 다시 해도 같으므로 영구 실패 (사용자가 압축을 끄면 원본으로 올릴 수 있음)
        is CompressionException -> failPermanently(task, e)
        is SessionExpiredException -> {
            queue.setSession(task.id, null)
            retryTransient(task, e)
        }
        is RemoteStorageException -> {
            // 한도 초과는 4xx 지만 다시 하면 되는 오류다. 병렬로 올리면 실제로 닿는다
            val isClientError = e.httpCode?.let { it in CLIENT_ERROR_RANGE } == true && !e.isRateLimited
            if (isClientError) failPermanently(task, e) else retryTransient(task, e)
        }
        is IOException -> retryTransient(task, e)
        else -> failPermanently(task, e)
    }

    /** 큐 완료 처리 + 영구 원장 기록(자동 백업 중복 방지·업로드됨 표시의 근거) */
    private suspend fun markUploaded(task: UploadTask, driveFileId: String) {
        queue.complete(task.id, driveFileId)
        ledger.record(task.mediaId, driveFileId, task.folderId, task.accountId)
    }

    private suspend fun failPermanently(task: UploadTask, e: Exception): Outcome {
        Timber.e(e, "upload failed permanently: %s", task.displayName)
        task.sessionUri?.let { session ->
            runCatching { storages.storage(task.accountId).uploader().abort(session) }
                .onFailure { Timber.w(it, "abort session failed") }
        }
        queue.fail(task.id, e.message ?: e.toString())
        compressor.cleanup(task.toSource())
        return Outcome.Failed
    }

    private suspend fun retryTransient(task: UploadTask, e: Exception): Outcome {
        // 한도에 걸린 코루틴은 잠깐 쉰다. 곧바로 다음 항목을 집으면 그것도 같은 한도에 걸린다.
        // 넷 중 하나씩 쉬면서 전체 속도가 자연히 조절된다.
        if (e is RemoteStorageException && e.isRateLimited) {
            val backoff = (RATE_LIMIT_BACKOFF_MILLIS shl task.attemptCount.coerceAtMost(MAX_BACKOFF_SHIFT))
            Timber.i("rate limited, pausing %dms: %s", backoff, task.displayName)
            delay(backoff)
        }
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

    private suspend fun updateForeground(task: UploadTask, fraction: Float, compressing: Boolean = false) {
        try {
            setForeground(
                notifications.progressForegroundInfo(
                    done = succeeded + failed,
                    total = total,
                    currentName = task.displayName,
                    fraction = fraction,
                    compressing = compressing,
                ),
            )
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
        width = width,
        height = height,
    )

    private enum class Outcome { Success, Failed, RetryLater, SignInRequired }

    companion object {
        /** 이 크기 이하만 왕복 한 번(multipart). 넘으면 끊겼을 때 다시 올리는 비용이 더 크다 */
        private const val WHOLE_UPLOAD_LIMIT = 4L * 1024 * 1024

        /**
         * 동시에 올리는 개수. OkHttp 의 호스트당 기본 동시 요청이 5라 그 안에 두어 커넥션을 재사용하고,
         * 더 늘리면 종량제·약전계에서 역효과가 크다 — `docs/UPLOAD_PERFORMANCE.md` §2-2
         */
        private const val PARALLELISM = 4

        /** 한도에 걸렸을 때 첫 대기. 재시도마다 두 배가 된다 */
        private const val RATE_LIMIT_BACKOFF_MILLIS = 1_000L
        private const val MAX_BACKOFF_SHIFT = 3

        const val UNIQUE_WORK_NAME = "upload-queue"
        const val MAX_ATTEMPTS = 5
        private const val PROGRESS_INTERVAL_MILLIS = 1_000L
        private val CLIENT_ERROR_RANGE = 400..499
    }
}
