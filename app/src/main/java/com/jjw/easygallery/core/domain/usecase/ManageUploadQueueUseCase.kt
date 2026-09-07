package com.jjw.easygallery.core.domain.usecase

import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.data.upload.work.UploadScheduler
import javax.inject.Inject

class ManageUploadQueueUseCase @Inject constructor(
    private val queue: UploadQueueRepository,
    private val scheduler: UploadScheduler,
) {
    suspend fun retryFailed(): Int {
        val count = queue.retryFailed()
        if (count > 0) scheduler.schedule()
        return count
    }

    suspend fun clearCompleted(): Int = queue.deleteCompleted()

    /** 진행 중 워커를 멈추고 대기·진행 항목을 모두 지운다. Drive 에 부분 업로드된 세션은 서버가 만료시킨다. */
    suspend fun cancelAll() {
        scheduler.cancel()
        queue.deleteUnfinished()
    }

    suspend fun remove(taskId: Long) {
        queue.delete(taskId)
    }

    /** 앱 시작 시 남은 큐가 있으면 워커가 살아 있도록 보장 */
    suspend fun ensureScheduled() {
        if (queue.countUnfinished() > 0) scheduler.schedule()
    }

    suspend fun rescheduleWithCurrentConstraints() {
        if (queue.countUnfinished() > 0) scheduler.schedule(replace = true)
    }
}
