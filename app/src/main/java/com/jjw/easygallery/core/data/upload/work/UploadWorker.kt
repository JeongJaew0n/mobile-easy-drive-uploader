package com.jjw.easygallery.core.data.upload.work

import android.content.Context
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jjw.easygallery.R
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
import com.jjw.easygallery.core.domain.model.RemoteAccount
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
     * 더 해봐야 소용없다고 판정된 오류. 남은 항목을 같은 이유로 접을 때 쓴다.
     * 큐에서 집어온 [UploadTask] 는 실패 **전**의 복사본이라 사유가 들어 있지 않다.
     */
    @Volatile
    private var hopelessError: RemoteStorageException? = null

    /** 이번 실행에서 손댄 "다른 계정 업로드" 계정들. 끝에 그 계정의 것이 다 끝났으면 알린다 */
    private val guestAccounts = Collections.synchronizedSet(mutableSetOf<String>())

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

        // 모든 코루틴이 끝난 뒤에 접는다 — 도는 중에 접으면 다른 코루틴이 올리고 있는 것까지 접는다
        hopelessError?.let { error -> stopHopeless(error) }
        announceFinishedGuests()
        return finishResult(stopResult.get())
    }

    /**
     * "다른 계정 업로드" 가 끝난 계정을 알린다(`docs/plans/guest-account-upload/spec.md` §4.5).
     *
     * 앱은 기기 계정을 지울 수 없다. 끝나는 순간 "지우려면 여기" 를 보여주는 것이 앱이 할 수 있는 전부라,
     * 알림에 더해 설정에도 남겨 갤러리 배너로 보인다 — 알림을 꺼 둔 사용자도 놓치지 않게.
     */
    private suspend fun announceFinishedGuests() {
        val finished = guestAccounts.toList().filter { queue.countUnfinishedFor(it) == 0 }
        for (accountId in finished) {
            val email = RemoteAccount.guestEmailOf(accountId) ?: continue
            val uploaded = queue.countCompletedFor(accountId)
            Timber.i("guest upload finished: uploaded=%d", uploaded)
            notifications.showGuestUploadDone(email, uploaded)
            prefs.setGuestCleanupEmail(email)
        }
    }

    /** 멈출 이유 > 미뤄둔 것 > 정상 종료 순으로 결과를 정한다 */
    private fun finishResult(stop: Result?): Result {
        if (stop != null) return stop
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
                // 용량 초과처럼 다시 해도 소용없는 이유다. 남은 것을 계속 시도하면 같은 실패가
                // 수백 번 쌓이고, 사용자는 "N개 실패" 만 보게 된다 — 한 건에서 멈추고 이유를 알린다
                // 접는 것은 여기서 하지 않는다 — 다른 코루틴이 아직 올리고 있는 항목까지
                // 잘못 접는다. 모두 끝난 뒤 doWork 가 한 번에 정리한다
                Outcome.Hopeless -> {
                    countFailure()
                    stopResult.compareAndSet(null, Result.failure())
                }
            }
        }
    }

    /**
     * 남은 것을 같은 이유로 접고 멈춘다.
     *
     * 대기로 두면 화면에는 "업로드 중" 으로 보이고, 하나씩 올려보면 같은 실패가 쌓인다 —
     * 용량이 찬 채로 1553건이 그랬다. 사용자가 공간을 비운 뒤 "실패 재시도" 를 누르면 그대로 간다.
     */
    private suspend fun stopHopeless(error: RemoteStorageException) {
        val folded = queue.failAllUnfinished(error.message, error.reason)
        Timber.w("upload stopped: %s, folded %d unfinished item(s)", error.reason, folded)
        notifications.showUploadBlocked(applicationContext.getString(R.string.upload_blocked_storage_full))
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
        task.accountId?.takeIf { RemoteAccount.guestEmailOf(it) != null }?.let { guestAccounts += it }
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

        val shortcut = tryShortcuts(uploader, task, source, folderId, length)
        if (shortcut != null) {
            Timber.d("timing short=%d total=%d", SystemClock.elapsedRealtime() - t1, SystemClock.elapsedRealtime() - t0)
            return shortcut
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
     * 세션을 만들지 않고 끝낼 수 있는 두 경우. 해당하지 않으면 null 을 돌려 재개 경로로 보낸다.
     *
     * 하나는 앱이 죽어 되살아난 항목이다. 한 번에 올리는 경로는 재개가 없어서, 서버에는 파일이
     * 생겼는데 우리 기록만 없는 상태로 다시 올리면 같은 파일이 두 벌 된다.
     *
     * 다른 하나는 작은 파일이다. 왕복 한 번으로 끝나 세션 생성 시간이 통째로 빠진다.
     */
    @Suppress("ReturnCount") // 빠른 경로를 찾는 즉시 돌려주는 편이 중첩보다 읽기 쉽다
    private suspend fun tryShortcuts(
        uploader: RemoteUploader,
        task: UploadTask,
        source: UploadSource,
        folderId: String,
        length: Long,
    ): Outcome? {
        if (task.sessionUri != null) return null

        if (task.attemptCount > 0) {
            val existing = uploader.findUploaded(task.mediaId, folderId)
            if (existing != null) {
                Timber.i("already on server, skipping: %s", task.displayName)
                return finish(task, source, existing)
            }
        }
        if (length !in 1..WHOLE_UPLOAD_LIMIT) return null
        val wholeId = uploader.uploadWhole(source, folderId, length) ?: return null
        return finish(task, source, wholeId)
    }

    private suspend fun finish(task: UploadTask, source: UploadSource, fileId: String): Outcome {
        markUploaded(task, fileId)
        compressor.cleanup(source)
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
        // "다른 계정 업로드" 의 B 가 기기에서 빠졌거나 권한이 없다. 주 계정 A 의 업로드까지 세울 이유가
        // 없다 — 그 항목만 접는다. 여기서 SignInRequired 를 내면 "A 로 다시 로그인하라" 는 엉뚱한 알림이 뜬다
        is AuthException if RemoteAccount.guestEmailOf(task.accountId) != null -> {
            Timber.w(e, "guest account unavailable")
            queue.fail(task.id, e.message, REASON_GUEST_UNAVAILABLE)
            Outcome.Failed
        }
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
            val isClientError = e.httpCode?.let { it in CLIENT_ERROR_RANGE } == true && !e.isRetryable
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
        queue.fail(task.id, e.message ?: e.toString(), (e as? RemoteStorageException)?.reason)
        compressor.cleanup(task.toSource())
        if (e !is RemoteStorageException || !e.isHopeless) return Outcome.Failed
        hopelessError = e
        return Outcome.Hopeless
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
            queue.fail(task.id, e.message ?: e.toString(), (e as? RemoteStorageException)?.reason)
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

    private enum class Outcome {
        Success,
        Failed,
        RetryLater,
        SignInRequired,

        /** 다시 해도 소용없다(Drive 용량 초과). 남은 항목을 건드리지 않고 멈춘다 */
        Hopeless,
    }

    companion object {
        /** B 가 기기에 없거나 권한이 없다. 화면이 이 코드로 문장을 고른다 */
        const val REASON_GUEST_UNAVAILABLE = "guestAccountUnavailable"

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
