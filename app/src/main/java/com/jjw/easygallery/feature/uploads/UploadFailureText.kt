package com.jjw.easygallery.feature.uploads

import androidx.annotation.StringRes
import com.jjw.easygallery.R

/**
 * 서버가 준 실패 코드(`UploadTask.errorReason`) → 우리 문장. 모르는 코드면 null — 업로드 목록은 그때 원문을 보이고,
 * 백업된 사진 화면은 "업로드 실패" 한 묶음으로 모은다(`docs/plans/backed-up-photos/spec.md` §2.2).
 */
@StringRes
internal fun uploadFailureRes(reason: String?): Int? = when (reason) {
    "storageQuotaExceeded" -> R.string.upload_error_storage_full
    "rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded" -> R.string.upload_error_rate_limited
    "insufficientFilePermissions", "forbidden" -> R.string.upload_error_no_permission
    "notFound" -> R.string.upload_error_folder_missing
    "authError", "unauthorized" -> R.string.upload_error_sign_in
    "guestAccountUnavailable" -> R.string.upload_error_guest_unavailable
    else -> null
}
