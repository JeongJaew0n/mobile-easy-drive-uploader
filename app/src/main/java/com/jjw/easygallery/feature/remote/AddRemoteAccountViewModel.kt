package com.jjw.easygallery.feature.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.remote.RemoteAccountRepository
import com.jjw.easygallery.core.data.remote.RemoteStorageFactory
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** S3 endpoint 프리셋(`docs/MULTI_CLOUD.md` §4). 값은 기본값일 뿐 사용자가 고칠 수 있다 */
enum class S3Preset(val endpoint: String, val region: String) {
    NAVER("https://kr.object.ncloudstorage.com", "kr-standard"),
    KT("https://ss1.cloud.kt.com:1000", "kr-central-1"),
    AWS("https://s3.ap-northeast-2.amazonaws.com", "ap-northeast-2"),
    R2("https://<account-id>.r2.cloudflarestorage.com", "auto"),
    CUSTOM("", ""),
}

data class AddRemoteAccountUiState(
    val kind: RemoteAccountKind = RemoteAccountKind.S3,
    val preset: S3Preset = S3Preset.NAVER,
    val displayName: String = "",
    val endpoint: String = S3Preset.NAVER.endpoint,
    val region: String = S3Preset.NAVER.region,
    val bucketOrRoot: String = "",
    val username: String = "",
    val secret: String = "",
    val isBusy: Boolean = false,
    /** 마지막 연결 테스트 결과. null = 아직 안 함 */
    val testResult: Result<Unit>? = null,
) {
    val canSubmit: Boolean
        get() = displayName.isNotBlank() && endpoint.isNotBlank() && username.isNotBlank() && secret.isNotBlank() &&
            (kind != RemoteAccountKind.S3 || bucketOrRoot.isNotBlank())
}

sealed interface AddRemoteAccountEvent {
    data object Saved : AddRemoteAccountEvent
    data class Error(val message: String) : AddRemoteAccountEvent
}

@HiltViewModel
@Suppress("TooGenericExceptionCaught") // 연결 테스트는 어떤 예외든 사용자에게 메시지로
class AddRemoteAccountViewModel @Inject constructor(
    private val accounts: RemoteAccountRepository,
    private val factories: Map<RemoteAccountKind, @JvmSuppressWildcards RemoteStorageFactory>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddRemoteAccountUiState())
    val uiState: StateFlow<AddRemoteAccountUiState> = _uiState.asStateFlow()

    private val _events = MutableStateFlow<AddRemoteAccountEvent?>(null)
    val events: StateFlow<AddRemoteAccountEvent?> = _events.asStateFlow()

    fun setKind(kind: RemoteAccountKind) = _uiState.update {
        val preset = if (kind == RemoteAccountKind.S3) it.preset else S3Preset.CUSTOM
        it.copy(
            kind = kind,
            endpoint = if (kind == RemoteAccountKind.S3) preset.endpoint else "",
            region = if (kind == RemoteAccountKind.S3) preset.region else "",
            testResult = null,
        )
    }

    fun setPreset(preset: S3Preset) = _uiState.update {
        it.copy(preset = preset, endpoint = preset.endpoint, region = preset.region, testResult = null)
    }

    fun update(transform: AddRemoteAccountUiState.() -> AddRemoteAccountUiState) =
        _uiState.update { it.transform().copy(testResult = null) }

    fun consumeEvent() {
        _events.value = null
    }

    /** 임시 제공자를 만들어 루트 목록을 한 번 읽는다 */
    fun testConnection() {
        val state = _uiState.value
        val factory = factories[state.kind] ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = try {
                val storage = factory.create(state.toAccount(id = "test"), state.secret)
                storage.listChildren(storage.rootId, foldersOnly = true)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "connection test failed")
                Result.failure(e)
            }
            _uiState.update { it.copy(isBusy = false, testResult = result) }
        }
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSubmit) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                accounts.add(state.toAccount(id = ""), state.secret)
                _events.value = AddRemoteAccountEvent.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "save account failed")
                _events.value = AddRemoteAccountEvent.Error(e.message ?: e.toString())
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun AddRemoteAccountUiState.toAccount(id: String) = RemoteAccount(
        id = id,
        kind = kind,
        displayName = displayName.trim(),
        endpoint = endpoint.trim().trimEnd('/'),
        region = region.trim().ifBlank { null },
        bucketOrRoot = bucketOrRoot.trim().ifBlank { null },
        username = username.trim(),
    )
}
