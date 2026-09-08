package com.jjw.easygallery.core.domain.usecase

import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.data.upload.work.UploadScheduler
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.MediaItem
import timber.log.Timber
import javax.inject.Inject

/**
 * 자동 백업: 선택한 앨범에 새로 추가된 항목을 업로드 큐에 넣는다.
 * 중복은 (1) 영구 원장(이미 올라감) (2) 큐(대기·실패 포함) 두 곳으로 막는다.
 */
class AutoBackupUseCase internal constructor(
    private val prefs: UserPreferencesRepository,
    private val media: MediaRepository,
    private val ledger: UploadLedgerRepository,
    private val queue: UploadQueueRepository,
    private val scheduler: UploadScheduler,
    private val clock: () -> Long,
) {
    // Hilt 는 함수 타입을 주입할 수 없으므로 시계는 테스트 전용 생성자에서만 받는다
    @Inject
    constructor(
        prefs: UserPreferencesRepository,
        media: MediaRepository,
        ledger: UploadLedgerRepository,
        queue: UploadQueueRepository,
        scheduler: UploadScheduler,
    ) : this(prefs, media, ledger, queue, scheduler, System::currentTimeMillis)

    data class Result(val scanned: Int, val enqueued: Int, val skipped: Int) {
        companion object {
            val Skipped = Result(0, 0, 0)
        }
    }

    /** 기준 시점 이후 추가된 항목을 큐에 넣고 기준 시점을 앞으로 옮긴다. 꺼져 있거나 로그인 전이면 아무것도 하지 않는다. */
    suspend fun scanAndEnqueue(): Result {
        val p = prefs.current()
        if (!p.autoBackupEnabled || !p.isSignedIn || p.autoBackupPaths.isEmpty()) return Result.Skipped
        val since = p.autoBackupSinceSeconds.takeIf { it > 0 } ?: (clock() / MILLIS_PER_SECOND)
        val candidates = media.queryAddedSince(since, p.autoBackupPaths, p.autoBackupIncludeVideos)
        val result = enqueueNew(candidates, p.uploadFolder(), p.uploadAccountId)
        // 같은 초에 여러 장이 들어와도 놓치지 않도록 '>=' 로 조회하고, 원장·큐로 중복을 걸러낸다
        val nextSince = candidates.maxOfOrNull { it.dateAddedSeconds }?.coerceAtLeast(since) ?: since
        prefs.markAutoBackupRun(nextSince, clock())
        Timber.i("auto-backup: scanned=%d enqueued=%d skipped=%d", result.scanned, result.enqueued, result.skipped)
        return result
    }

    /** 선택한 앨범의 기존 항목 중 아직 안 올라간 것의 개수 (확인 다이얼로그용) */
    suspend fun pendingBackfillCount(): Int {
        val p = prefs.current()
        if (p.autoBackupPaths.isEmpty()) return 0
        val all = media.queryAddedSince(0, p.autoBackupPaths, p.autoBackupIncludeVideos)
        return filterNew(all).size
    }

    /** 선택한 앨범의 기존 항목을 전부 큐에 넣는다 (사용자가 명시적으로 눌렀을 때만) */
    suspend fun backfill(): Result {
        val p = prefs.current()
        if (!p.canUpload || p.autoBackupPaths.isEmpty()) return Result.Skipped
        val all = media.queryAddedSince(0, p.autoBackupPaths, p.autoBackupIncludeVideos)
        return enqueueNew(all, p.uploadFolder(), p.uploadAccountId)
    }

    private suspend fun enqueueNew(candidates: List<MediaItem>, folder: DriveFolder?, accountId: String?): Result {
        if (candidates.isEmpty()) return Result(0, 0, 0)
        val fresh = filterNew(candidates)
        val added = if (fresh.isEmpty()) 0 else queue.enqueue(fresh, folder, accountId)
        if (added > 0) scheduler.schedule()
        return Result(scanned = candidates.size, enqueued = added, skipped = candidates.size - added)
    }

    private suspend fun filterNew(items: List<MediaItem>): List<MediaItem> {
        if (items.isEmpty()) return items
        val ids = items.map { it.id }
        val done = ledger.uploadedAmong(ids)
        val queued = queue.queuedAmong(ids)
        return items.filter { it.id !in done && it.id !in queued }
    }

    private fun com.jjw.easygallery.core.data.prefs.UserPreferences.uploadFolder(): DriveFolder? =
        uploadFolderId?.let { DriveFolder(it, uploadFolderName ?: "") }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
