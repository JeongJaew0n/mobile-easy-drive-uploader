package com.jjw.easygallery.core.data.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.jjw.easygallery.core.domain.model.DriveEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 한 번에 받은 것들의 결과. [lastMediaId] 는 "보기" 로 열 사진(사진·영상이 아니면 null) */
data class DownloadBatchResult(val succeeded: Int, val failed: Int, val lastMediaId: Long?)

/** 원격 파일 → 기기 다운로드 워크 등록. 같은 파일을 두 번 누르면 진행 중인 것을 유지(KEEP) */
@Singleton
class DownloadScheduler @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    /**
     * [entryIds] 가 **모두** 끝날 때까지 기다려 결과를 모은다(`docs/plans/drive-feedback/spec.md` §3).
     * 화면이 "N장을 갤러리에 저장했습니다 [보기]" 를 띄우는 데 쓴다. 화면을 떠나면 호출자의 코루틴과 함께 멈춘다 —
     * 그때는 완료 알림이 알린다.
     */
    suspend fun awaitBatch(entryIds: List<String>): DownloadBatchResult {
        if (entryIds.isEmpty()) return DownloadBatchResult(0, 0, null)
        val flows = entryIds.map { id -> workManager.getWorkInfosForUniqueWorkFlow(uniqueName(id)) }
        val infos = combine(flows) { lists -> lists.map { it.lastOrNull() } }
            .first { list -> list.all { it == null || it.state.isFinished } }
        val succeeded = infos.filter { it?.state == WorkInfo.State.SUCCEEDED }
        val lastMediaId = succeeded.lastOrNull()?.outputData?.getLong(DownloadWorker.KEY_MEDIA_ID, -1L)
            ?.takeIf { it >= 0 }
        return DownloadBatchResult(
            succeeded = succeeded.size,
            failed = infos.count { it?.state == WorkInfo.State.FAILED },
            lastMediaId = lastMediaId,
        )
    }

    fun enqueue(accountId: String?, entry: DriveEntry) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(DownloadWorker.inputData(accountId, entry.id, entry.name, entry.mimeType, entry.sizeBytes))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(uniqueName(entry.id), ExistingWorkPolicy.KEEP, request)
    }

    private fun uniqueName(entryId: String) = "download-$entryId"

    private companion object {
        const val BACKOFF_SECONDS = 15L
    }
}
