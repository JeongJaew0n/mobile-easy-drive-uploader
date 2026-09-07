package com.jjw.easygallery.feature.settings

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.auth.AuthRepository
import com.jjw.easygallery.core.data.auth.SignInCancelledException
import com.jjw.easygallery.core.data.auth.SignInStep
import com.jjw.easygallery.core.data.drive.DriveRepository
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.domain.model.DriveAccount
import com.jjw.easygallery.core.domain.usecase.ManageUploadQueueUseCase
import com.jjw.easygallery.core.domain.usecase.SignInUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
@Suppress("TooGenericExceptionCaught") // UI 경계: 인증·네트워크 오류를 모두 메시지로 보여준다
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val signInUseCase: SignInUseCase,
    private val auth: AuthRepository,
    private val drive: DriveRepository,
    private val manageQueue: ManageUploadQueueUseCase,
) : ViewModel() {

    private val isBusy = MutableStateFlow(false)
    private val account = MutableStateFlow<DriveAccount?>(null)
    private val events = Channel<SettingsEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState> = combine(prefs.preferences, account, isBusy) { p, acc, busy ->
        SettingsUiState(
            isSignedIn = p.isSignedIn,
            accountEmail = p.accountEmail,
            accountName = acc?.displayName ?: p.accountName,
            storageUsedBytes = acc?.storageUsedBytes,
            storageLimitBytes = acc?.storageLimitBytes,
            uploadFolderName = p.uploadFolderName,
            uploadWifiOnly = p.uploadWifiOnly,
            uploadChargingOnly = p.uploadChargingOnly,
            isBusy = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState())

    init {
        viewModelScope.launch {
            prefs.preferences.collect { p ->
                if (p.isSignedIn && account.value == null) refreshAccount() else if (!p.isSignedIn) account.value = null
            }
        }
    }

    fun signIn() = runBusy {
        when (val step = signInUseCase.begin()) {
            is SignInStep.NeedsConsent -> events.send(SettingsEvent.LaunchConsent(step.pendingIntent))
            SignInStep.Completed -> events.send(SettingsEvent.SignedIn)
        }
    }

    fun onConsentResult(resultCode: Int, data: Intent?) = runBusy {
        if (resultCode != Activity.RESULT_OK) {
            events.send(SettingsEvent.SignInCancelled)
            return@runBusy
        }
        signInUseCase.complete(data)
        events.send(SettingsEvent.SignedIn)
    }

    fun signOut() = runBusy {
        manageQueue.cancelAll()
        auth.signOut()
        account.value = null
    }

    fun setUploadWifiOnly(enabled: Boolean) = viewModelScope.launch {
        prefs.setUploadWifiOnly(enabled)
        manageQueue.rescheduleWithCurrentConstraints()
    }

    fun setUploadChargingOnly(enabled: Boolean) = viewModelScope.launch {
        prefs.setUploadChargingOnly(enabled)
        manageQueue.rescheduleWithCurrentConstraints()
    }

    private suspend fun refreshAccount() {
        try {
            account.value = drive.getAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "account refresh failed")
        }
    }

    private fun runBusy(block: suspend () -> Unit) {
        if (isBusy.value) return
        viewModelScope.launch {
            isBusy.update { true }
            try {
                block()
            } catch (e: SignInCancelledException) {
                events.send(SettingsEvent.SignInCancelled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "settings action failed")
                events.send(SettingsEvent.Error(e.message ?: e.toString()))
            } finally {
                isBusy.update { false }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

data class SettingsUiState(
    val isSignedIn: Boolean = false,
    val accountEmail: String? = null,
    val accountName: String? = null,
    val storageUsedBytes: Long? = null,
    val storageLimitBytes: Long? = null,
    val uploadFolderName: String? = null,
    val uploadWifiOnly: Boolean = true,
    val uploadChargingOnly: Boolean = false,
    val isBusy: Boolean = false,
)

sealed interface SettingsEvent {
    data class LaunchConsent(val pendingIntent: PendingIntent) : SettingsEvent
    data object SignedIn : SettingsEvent
    data object SignInCancelled : SettingsEvent
    data class Error(val message: String) : SettingsEvent
}
