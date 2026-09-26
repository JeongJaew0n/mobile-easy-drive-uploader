package com.jjw.easygallery.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jjw.easygallery.core.domain.model.PickedFolder
import com.jjw.easygallery.core.domain.model.VideoCompression
import com.jjw.easygallery.core.domain.model.ViewFolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userPreferencesStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

/** 자동 태그 기본 시각: 새벽 4시 */
const val DEFAULT_AUTO_TAG_MINUTE = 4 * 60
private const val MINUTES_PER_DAY = 24 * 60

data class UserPreferences(
    val accountEmail: String? = null,
    val accountName: String? = null,
    val uploadFolderId: String? = null,
    val uploadFolderName: String? = null,
    /** 피커로 지정한 남의 Drive 폴더들(`docs/DRIVE_FILE_SCOPE.md` §4) */
    val pickedFolders: List<PickedFolder> = emptyList(),
    /** 보기 전용으로 추가한 폴더들. `drive.readonly` 를 옵트인해야 채워진다(§10) */
    val viewFolders: List<ViewFolder> = emptyList(),
    /** 사용자가 `drive.readonly` 를 허락했다. 토큰을 받을 때 이 scope 를 함께 요청한다 */
    val driveViewScopeGranted: Boolean = false,
    /** 업로드 대상 저장소 계정. null = Google Drive(`docs/MULTI_CLOUD.md` §3) */
    val uploadAccountId: String? = null,
    /** 사진 백업은 데이터 요금이 크므로 기본은 Wi-Fi 전용 */
    val uploadWifiOnly: Boolean = true,
    val uploadChargingOnly: Boolean = false,
    val autoBackupEnabled: Boolean = false,
    /** 자동 백업 대상 앨범의 RELATIVE_PATH 집합 (예: "DCIM/Camera/") */
    val autoBackupPaths: Set<String> = emptySet(),
    val autoBackupIncludeVideos: Boolean = true,
    /** 이 시각(초, DATE_ADDED 기준) 이후 추가된 항목만 자동 백업. 0 = 미설정 */
    val autoBackupSinceSeconds: Long = 0,
    val autoBackupLastRunMillis: Long = 0,
    /** 업로드 전 영상 압축 프리셋. 원본 파일은 건드리지 않고 업로드 사본만 변환 */
    val videoCompression: VideoCompression = VideoCompression.ORIGINAL,
    /** 썸네일 오른쪽 위 카테고리 색 점. 배지가 많으면 시끄러울 수 있어 끌 수 있다 */
    val showCategoryBadges: Boolean = true,
    /** 새 사진을 매일 한 번 자동으로 분석한다(`docs/AUTO_TAGGING.md` §5.4) */
    val autoTagEnabled: Boolean = false,
    /** 자동 분석 시각(하루 중 분, 0~1439). 기본 04:00 */
    val autoTagMinuteOfDay: Int = DEFAULT_AUTO_TAG_MINUTE,
    val autoTagLastRunMillis: Long = 0,
    /** 자동 태그 목록에서 감춘 라벨(ML Kit 영어 원문) */
    val autoTagHiddenLabels: Set<String> = emptySet(),
) {
    val isSignedIn: Boolean get() = accountEmail != null

    /** 업로드 가능: Drive 대상이면 로그인, 다른 계정 대상이면 항상 */
    val canUpload: Boolean get() = uploadAccountId != null || isSignedIn
}

// TooManyFunctions: 설정 항목 하나에 setter 하나라 항목이 늘면 함수도 는다. 나누면 "어느 저장소에 있더라" 를 매번 찾게 된다
@Suppress("TooManyFunctions")
@Singleton
class UserPreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val store get() = context.userPreferencesStore
    private val json = Json { ignoreUnknownKeys = true }

    /** 저장된 값이 깨졌으면 빈 목록으로 본다 — 폴더 목록 하나 때문에 설정 전체를 못 읽으면 안 된다. */
    private fun decodeFolders(raw: String?): List<PickedFolder> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<PickedFolder>>(raw) }.getOrElse { emptyList() }
    }

    /** 같은 이유로 보기 폴더 목록도 깨지면 비운다. */
    private fun decodeViewFolders(raw: String?): List<ViewFolder> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<ViewFolder>>(raw) }.getOrElse { emptyList() }
    }

    val preferences: Flow<UserPreferences> = store.data.map { prefs ->
        UserPreferences(
            accountEmail = prefs[KEY_ACCOUNT_EMAIL],
            accountName = prefs[KEY_ACCOUNT_NAME],
            uploadFolderId = prefs[KEY_UPLOAD_FOLDER_ID],
            uploadFolderName = prefs[KEY_UPLOAD_FOLDER_NAME],
            pickedFolders = decodeFolders(prefs[KEY_PICKED_FOLDERS]),
            viewFolders = decodeViewFolders(prefs[KEY_VIEW_FOLDERS]),
            driveViewScopeGranted = prefs[KEY_DRIVE_VIEW_SCOPE] ?: false,
            uploadAccountId = prefs[KEY_UPLOAD_ACCOUNT_ID],
            uploadWifiOnly = prefs[KEY_UPLOAD_WIFI_ONLY] ?: true,
            uploadChargingOnly = prefs[KEY_UPLOAD_CHARGING_ONLY] ?: false,
            autoBackupEnabled = prefs[KEY_AUTO_BACKUP_ENABLED] ?: false,
            autoTagEnabled = prefs[KEY_AUTO_TAG_ENABLED] ?: false,
            autoTagMinuteOfDay = prefs[KEY_AUTO_TAG_MINUTE] ?: DEFAULT_AUTO_TAG_MINUTE,
            autoTagLastRunMillis = prefs[KEY_AUTO_TAG_LAST_RUN] ?: 0L,
            autoTagHiddenLabels = prefs[KEY_AUTO_TAG_HIDDEN] ?: emptySet(),
            autoBackupPaths = prefs[KEY_AUTO_BACKUP_PATHS] ?: emptySet(),
            autoBackupIncludeVideos = prefs[KEY_AUTO_BACKUP_VIDEOS] ?: true,
            autoBackupSinceSeconds = prefs[KEY_AUTO_BACKUP_SINCE] ?: 0L,
            autoBackupLastRunMillis = prefs[KEY_AUTO_BACKUP_LAST_RUN] ?: 0L,
            videoCompression = prefs[KEY_VIDEO_COMPRESSION]?.let { VideoCompression.fromStorageKey(it) }
                ?: VideoCompression.ORIGINAL,
            showCategoryBadges = prefs[KEY_SHOW_CATEGORY_BADGES] ?: true,
        )
    }

    suspend fun current(): UserPreferences = preferences.first()

    /**
     * 지정 폴더를 더한다. 같은 폴더를 또 고르면 별칭만 갈아끼운다 —
     * 사용자가 이름을 고치려고 다시 고르는 경우가 그렇다.
     */
    suspend fun addPickedFolder(folder: PickedFolder) = editFolders { current ->
        current.filterNot { it.id == folder.id } + folder
    }

    /**
     * 지정을 풀면 업로드 대상도 함께 되돌린다.
     *
     * 풀어도 업로드 폴더가 그대로 남으면, 앱은 **볼 수 없는 곳으로 계속 올린다** —
     * `drive.file` 에서 지정이 풀린 폴더는 화면에 나오지 않기 때문이다. 사용자에게는
     * "올렸다는데 Drive 어디에도 없다" 로 보인다
     * (`docs/troubleshootings/project-specific/uploaded-but-not-visible.md`).
     */
    suspend fun removePickedFolder(id: String) {
        editFolders { current -> current.filterNot { it.id == id } }
        store.edit {
            if (it[KEY_UPLOAD_FOLDER_ID] == id) {
                it.remove(KEY_UPLOAD_FOLDER_ID)
                it.remove(KEY_UPLOAD_FOLDER_NAME)
            }
        }
    }

    /** 보기 폴더를 더한다. 같은 폴더를 또 고르면 이름만 새로 고친다(Drive 에서 이름이 바뀌었을 수 있다). */
    suspend fun addViewFolder(folder: ViewFolder) = editViewFolders { current ->
        current.filterNot { it.id == folder.id } + folder
    }

    /**
     * 목록에서만 뺀다. Drive 의 폴더는 건드리지 않는다 —
     * 읽기 권한뿐이라 지울 수도 없고, 사용자가 기대하는 것도 "내 목록에서 치우기" 다.
     */
    suspend fun removeViewFolder(id: String) = editViewFolders { current -> current.filterNot { it.id == id } }

    /**
     * `drive.readonly` 옵트인 상태. 끄면 보기 폴더 목록도 함께 비운다 —
     * 권한 없이 남아 있으면 들어갈 때마다 실패하는 행이 된다.
     */
    suspend fun setDriveViewScopeGranted(granted: Boolean) {
        store.edit { prefs ->
            if (granted) {
                prefs[KEY_DRIVE_VIEW_SCOPE] = true
            } else {
                prefs.remove(KEY_DRIVE_VIEW_SCOPE)
                prefs.remove(KEY_VIEW_FOLDERS)
            }
        }
    }

    private suspend fun editViewFolders(transform: (List<ViewFolder>) -> List<ViewFolder>) {
        store.edit { prefs ->
            val next = transform(decodeViewFolders(prefs[KEY_VIEW_FOLDERS]))
            if (next.isEmpty()) prefs.remove(KEY_VIEW_FOLDERS) else prefs[KEY_VIEW_FOLDERS] = json.encodeToString(next)
        }
    }

    private suspend fun editFolders(transform: (List<PickedFolder>) -> List<PickedFolder>) {
        store.edit { prefs ->
            val next = transform(decodeFolders(prefs[KEY_PICKED_FOLDERS]))
            if (next.isEmpty()) {
                prefs.remove(KEY_PICKED_FOLDERS)
            } else {
                prefs[KEY_PICKED_FOLDERS] = json.encodeToString(next)
            }
        }
    }

    suspend fun setAccount(email: String, displayName: String?) {
        store.edit { prefs ->
            prefs[KEY_ACCOUNT_EMAIL] = email
            if (displayName != null) prefs[KEY_ACCOUNT_NAME] = displayName else prefs.remove(KEY_ACCOUNT_NAME)
        }
    }

    /** 계정과 함께 업로드 폴더도 지운다 — 폴더 ID 는 계정에 종속된 값. 보기 폴더와 읽기 권한도 같다. */
    suspend fun clearAccount() {
        store.edit { prefs ->
            prefs.remove(KEY_ACCOUNT_EMAIL)
            prefs.remove(KEY_ACCOUNT_NAME)
            prefs.remove(KEY_UPLOAD_FOLDER_ID)
            prefs.remove(KEY_UPLOAD_FOLDER_NAME)
            prefs.remove(KEY_VIEW_FOLDERS)
            prefs.remove(KEY_DRIVE_VIEW_SCOPE)
        }
    }

    /** 업로드 대상 계정과 폴더를 함께 바꾼다. [accountId] null 은 Google Drive */
    suspend fun setUploadTarget(accountId: String?, folderId: String, folderName: String) {
        store.edit {
            if (accountId == null) it.remove(KEY_UPLOAD_ACCOUNT_ID) else it[KEY_UPLOAD_ACCOUNT_ID] = accountId
            it[KEY_UPLOAD_FOLDER_ID] = folderId
            it[KEY_UPLOAD_FOLDER_NAME] = folderName
        }
    }

    /** 계정을 지우면 그 계정을 향하던 업로드 대상도 Drive 기본값으로 되돌린다 */
    suspend fun clearUploadTargetIfAccount(accountId: String) {
        store.edit {
            if (it[KEY_UPLOAD_ACCOUNT_ID] == accountId) {
                it.remove(KEY_UPLOAD_ACCOUNT_ID)
                it.remove(KEY_UPLOAD_FOLDER_ID)
                it.remove(KEY_UPLOAD_FOLDER_NAME)
            }
        }
    }

    suspend fun setUploadFolder(id: String, name: String) {
        store.edit { prefs ->
            prefs[KEY_UPLOAD_FOLDER_ID] = id
            prefs[KEY_UPLOAD_FOLDER_NAME] = name
        }
    }

    /** 켤 때 기준 시점을 지금으로 잡아 기존 사진이 한꺼번에 큐에 들어가지 않게 한다 */
    suspend fun setAutoBackupEnabled(enabled: Boolean, nowSeconds: Long) {
        store.edit {
            it[KEY_AUTO_BACKUP_ENABLED] = enabled
            if (enabled && (it[KEY_AUTO_BACKUP_SINCE] ?: 0L) == 0L) it[KEY_AUTO_BACKUP_SINCE] = nowSeconds
            if (!enabled) it.remove(KEY_AUTO_BACKUP_SINCE)
        }
    }

    suspend fun setAutoTagEnabled(enabled: Boolean) {
        store.edit { it[KEY_AUTO_TAG_ENABLED] = enabled }
    }

    /** [minuteOfDay] 는 0~1439. 범위를 벗어나면 무시한다 */
    suspend fun setAutoTagMinuteOfDay(minuteOfDay: Int) {
        if (minuteOfDay !in 0 until MINUTES_PER_DAY) return
        store.edit { it[KEY_AUTO_TAG_MINUTE] = minuteOfDay }
    }

    suspend fun setAutoTagLastRun(millis: Long) {
        store.edit { it[KEY_AUTO_TAG_LAST_RUN] = millis }
    }

    /** 화면에 "숨긴 N개" 토글이 있어 사용자는 유지되는 설정으로 읽는다 — 메모리에만 두면 안 된다 */
    suspend fun setAutoTagHiddenLabels(labels: Set<String>) {
        store.edit { it[KEY_AUTO_TAG_HIDDEN] = labels }
    }

    suspend fun setAutoBackupPaths(paths: Set<String>) {
        store.edit { it[KEY_AUTO_BACKUP_PATHS] = paths }
    }

    suspend fun setAutoBackupIncludeVideos(enabled: Boolean) {
        store.edit { it[KEY_AUTO_BACKUP_VIDEOS] = enabled }
    }

    suspend fun markAutoBackupRun(sinceSeconds: Long, nowMillis: Long) {
        store.edit {
            it[KEY_AUTO_BACKUP_SINCE] = sinceSeconds
            it[KEY_AUTO_BACKUP_LAST_RUN] = nowMillis
        }
    }

    suspend fun setVideoCompression(preset: VideoCompression) {
        store.edit { it[KEY_VIDEO_COMPRESSION] = preset.name }
    }

    suspend fun setUploadWifiOnly(enabled: Boolean) {
        store.edit { it[KEY_UPLOAD_WIFI_ONLY] = enabled }
    }

    suspend fun setUploadChargingOnly(enabled: Boolean) {
        store.edit { it[KEY_UPLOAD_CHARGING_ONLY] = enabled }
    }

    suspend fun setShowCategoryBadges(enabled: Boolean) {
        store.edit { it[KEY_SHOW_CATEGORY_BADGES] = enabled }
    }

    private companion object {
        val KEY_AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val KEY_AUTO_TAG_ENABLED = booleanPreferencesKey("auto_tag_enabled")
        val KEY_AUTO_TAG_MINUTE = intPreferencesKey("auto_tag_minute_of_day")
        val KEY_AUTO_TAG_LAST_RUN = longPreferencesKey("auto_tag_last_run")
        val KEY_AUTO_TAG_HIDDEN = stringSetPreferencesKey("auto_tag_hidden_labels")
        val KEY_AUTO_BACKUP_PATHS = stringSetPreferencesKey("auto_backup_paths")
        val KEY_AUTO_BACKUP_VIDEOS = booleanPreferencesKey("auto_backup_videos")
        val KEY_AUTO_BACKUP_SINCE = longPreferencesKey("auto_backup_since_seconds")
        val KEY_AUTO_BACKUP_LAST_RUN = longPreferencesKey("auto_backup_last_run")
        val KEY_VIDEO_COMPRESSION = stringPreferencesKey("video_compression")
        val KEY_UPLOAD_ACCOUNT_ID = stringPreferencesKey("upload_account_id")
        val KEY_SHOW_CATEGORY_BADGES = booleanPreferencesKey("show_category_badges")
        val KEY_UPLOAD_WIFI_ONLY = booleanPreferencesKey("upload_wifi_only")
        val KEY_UPLOAD_CHARGING_ONLY = booleanPreferencesKey("upload_charging_only")
        val KEY_ACCOUNT_EMAIL = stringPreferencesKey("account_email")
        val KEY_ACCOUNT_NAME = stringPreferencesKey("account_name")
        val KEY_UPLOAD_FOLDER_ID = stringPreferencesKey("upload_folder_id")
        val KEY_UPLOAD_FOLDER_NAME = stringPreferencesKey("upload_folder_name")
        val KEY_PICKED_FOLDERS = stringPreferencesKey("picked_folders")
        val KEY_VIEW_FOLDERS = stringPreferencesKey("view_folders")
        val KEY_DRIVE_VIEW_SCOPE = booleanPreferencesKey("drive_view_scope")
    }
}
