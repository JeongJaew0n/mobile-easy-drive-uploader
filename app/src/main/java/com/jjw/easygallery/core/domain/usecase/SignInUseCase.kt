package com.jjw.easygallery.core.domain.usecase

import android.content.Intent
import com.jjw.easygallery.core.data.auth.AuthRepository
import com.jjw.easygallery.core.data.auth.SignInStep
import com.jjw.easygallery.core.data.drive.DriveRepository
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import javax.inject.Inject

/** 인증 → 계정 정보 조회 → 저장을 하나의 흐름으로 묶는다. */
class SignInUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val drive: DriveRepository,
    private val prefs: UserPreferencesRepository,
) {
    suspend fun begin(): SignInStep {
        val step = auth.beginSignIn()
        if (step is SignInStep.Completed) finish()
        return step
    }

    suspend fun complete(data: Intent?) {
        auth.completeSignIn(data)
        finish()
    }

    private suspend fun finish() {
        val account = drive.getAccount()
        prefs.setAccount(account.email, account.displayName)
    }
}
