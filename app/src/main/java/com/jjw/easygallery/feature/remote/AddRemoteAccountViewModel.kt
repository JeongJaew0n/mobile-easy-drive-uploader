package com.jjw.easygallery.feature.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.common.di.AppDispatcher
import com.jjw.easygallery.core.common.di.Dispatcher
import com.jjw.easygallery.core.data.remote.RemoteAccountRepository
import com.jjw.easygallery.core.data.remote.RemoteStorageFactory
import com.jjw.easygallery.core.data.remote.StorageRegistry
import com.jjw.easygallery.core.data.remote.fetchServerCertificateSha256
import com.jjw.easygallery.core.data.remote.isTlsFailure
import com.jjw.easygallery.core.data.remote.s3.PlainHttpClient
import com.jjw.easygallery.core.data.remote.sftp.fetchSshHostKeySha256
import com.jjw.easygallery.core.data.remote.smb.DiscoveredHost
import com.jjw.easygallery.core.data.remote.smb.HostDiscovery
import com.jjw.easygallery.core.data.remote.smb.NsdHostDiscovery
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
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
    /** 사용자가 신뢰하기로 한 자체 서명 인증서 지문(WebDAV) */
    val certSha256: String? = null,
    /** TLS 실패 후 서버에서 읽어 온 지문 — 신뢰 여부 다이얼로그용 */
    val pendingCertSha256: String? = null,
    /** SMB "네트워크에서 찾기" 결과(mDNS `_smb._tcp`) */
    val discoveredHosts: List<DiscoveredHost> = emptyList(),
    val isDiscovering: Boolean = false,
    /** 수정 중인 계정 id. null 이면 새 계정 */
    val editingId: String? = null,
) {
    val isEditing: Boolean get() = editingId != null

    /** 수정 중에는 비밀을 비워 둘 수 있다(기존 값 유지) */
    val canSubmit: Boolean
        get() = displayName.isNotBlank() && endpoint.isNotBlank() && username.isNotBlank() &&
            (secret.isNotBlank() || isEditing) &&
            (kind in KINDS_WITHOUT_CONTAINER || bucketOrRoot.isNotBlank())
}

/** 버킷/공유 이름이 필요 없는 종류(WebDAV 는 경로가 endpoint 에, SFTP 는 루트가 선택) */
private val KINDS_WITHOUT_CONTAINER = setOf(RemoteAccountKind.WEBDAV, RemoteAccountKind.SFTP)

sealed interface AddRemoteAccountEvent {
    data object Saved : AddRemoteAccountEvent
    data class Error(val message: String) : AddRemoteAccountEvent
}

@HiltViewModel
@Suppress("TooGenericExceptionCaught") // 연결 테스트는 어떤 예외든 사용자에게 메시지로
class AddRemoteAccountViewModel @Inject constructor(
    private val accounts: RemoteAccountRepository,
    private val factories: Map<RemoteAccountKind, @JvmSuppressWildcards RemoteStorageFactory>,
    @PlainHttpClient private val httpClient: OkHttpClient,
    @Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
    private val hostDiscovery: HostDiscovery,
    private val storages: StorageRegistry,
) : ViewModel() {

    private var discoveryJob: Job? = null
    private var loaded = false

    /** 수정 모드에서 비밀을 비워 둔 채 연결 테스트할 때 쓰는 저장된 비밀 */
    private var storedSecret: String? = null

    /** 수정 모드 진입 — 저장된 계정으로 폼을 채운다(비밀은 비움). 한 번만 */
    fun load(accountId: String?) {
        if (loaded || accountId == null) return
        loaded = true
        viewModelScope.launch {
            val account = accounts.get(accountId) ?: return@launch
            storedSecret = accounts.secretOf(account)
            _uiState.update {
                it.copy(
                    kind = account.kind,
                    preset = S3Preset.CUSTOM,
                    displayName = account.displayName,
                    endpoint = account.endpoint,
                    region = account.region.orEmpty(),
                    bucketOrRoot = account.bucketOrRoot.orEmpty(),
                    username = account.username.orEmpty(),
                    secret = "",
                    certSha256 = account.certSha256,
                    editingId = account.id,
                    testResult = null,
                )
            }
        }
    }

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
            bucketOrRoot = "", // 버킷(S3)·공유 이름(SMB) 은 의미가 달라 종류를 바꾸면 비운다
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
                val storage = factory.create(state.toAccount(id = "test"), state.effectiveSecret())
                storage.listChildren(storage.rootId, foldersOnly = true)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "connection test failed")
                Result.failure(e)
            }
            // 자체 서명 인증서(WebDAV)·처음 보는 호스트 키(SFTP)면 지문을 읽어 와 신뢰 여부를 묻는다
            val failure = result.exceptionOrNull()
            if (failure != null && state.certSha256 == null) {
                val fingerprint = fetchFingerprintOrNull(state, failure)
                if (fingerprint != null) {
                    _uiState.update { it.copy(isBusy = false, pendingCertSha256 = fingerprint) }
                    return@launch
                }
            }
            _uiState.update { it.copy(isBusy = false, testResult = result) }
        }
    }

    /** WebDAV 는 TLS 인증서, SFTP 는 SSH 호스트 키. 못 읽으면 null(일반 오류로 표시) */
    private suspend fun fetchFingerprintOrNull(state: AddRemoteAccountUiState, failure: Throwable): String? =
        when {
            state.kind == RemoteAccountKind.WEBDAV && failure.isTlsFailure() -> runCatching {
                withContext(ioDispatcher) { fetchServerCertificateSha256(httpClient, state.endpoint.trim()) }
            }.getOrNull()

            state.kind == RemoteAccountKind.SFTP -> runCatching {
                withContext(ioDispatcher) { fetchSshHostKeySha256(state.endpoint.trim()) }
            }.getOrNull()

            else -> null
        }

    /** 지문 다이얼로그에서 "신뢰" → 저장하고 바로 다시 테스트 */
    fun trustPendingCertificate() {
        val fingerprint = _uiState.value.pendingCertSha256 ?: return
        _uiState.update { it.copy(certSha256 = fingerprint, pendingCertSha256 = null) }
        testConnection()
    }

    fun dismissPendingCertificate() = _uiState.update { it.copy(pendingCertSha256 = null) }

    /** 같은 Wi-Fi 의 서버를 mDNS 로 [DISCOVERY_MILLIS] 동안 찾는다(SMB `_smb._tcp`, SFTP `_sftp-ssh._tcp`) */
    fun discoverSmbHosts() {
        discoveryJob?.cancel()
        val serviceType = if (_uiState.value.kind == RemoteAccountKind.SFTP) {
            NsdHostDiscovery.SFTP_SERVICE
        } else {
            NsdHostDiscovery.SMB_SERVICE
        }
        discoveryJob = viewModelScope.launch {
            _uiState.update { it.copy(isDiscovering = true, discoveredHosts = emptyList()) }
            try {
                withTimeoutOrNull(DISCOVERY_MILLIS) {
                    hostDiscovery.discover(serviceType).collect { hosts ->
                        _uiState.update { it.copy(discoveredHosts = hosts) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "smb discovery failed")
            } finally {
                _uiState.update { it.copy(isDiscovering = false) }
            }
        }
    }

    /** 찾은 서버를 주소 칸에 넣는다(표시 이름이 비어 있으면 서비스 이름으로 채움) */
    fun pickDiscoveredHost(host: DiscoveredHost) {
        discoveryJob?.cancel()
        _uiState.update {
            it.copy(
                endpoint = if (host.port in DEFAULT_PORTS) host.host else "${host.host}:${host.port}",
                displayName = it.displayName.ifBlank { host.name },
                isDiscovering = false,
                testResult = null,
            )
        }
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSubmit) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val editingId = state.editingId
                if (editingId != null) {
                    accounts.update(state.toAccount(id = editingId), state.secret.ifBlank { null })
                    storages.evict(editingId) // 바뀐 자격 증명으로 다시 만들도록
                } else {
                    accounts.add(state.toAccount(id = ""), state.secret)
                }
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

    private companion object {
        const val DISCOVERY_MILLIS = 8_000L
        val DEFAULT_PORTS = setOf(445, 22)
    }

    private fun AddRemoteAccountUiState.effectiveSecret(): String = secret.ifBlank { storedSecret.orEmpty() }

    private fun AddRemoteAccountUiState.toAccount(id: String) = RemoteAccount(
        id = id,
        kind = kind,
        displayName = displayName.trim(),
        endpoint = endpoint.trim().trimEnd('/'),
        region = region.trim().ifBlank { null },
        bucketOrRoot = bucketOrRoot.trim().ifBlank { null },
        username = username.trim(),
        certSha256 = certSha256,
    )
}
