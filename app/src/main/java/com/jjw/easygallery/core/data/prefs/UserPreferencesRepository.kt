package com.jjw.easygallery.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jjw.easygallery.core.domain.model.VideoCompression
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userPreferencesStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

data class UserPreferences(
    val accountEmail: String? = null,
    val accountName: String? = null,
    val uploadFolderId: String? = null,
    val uploadFolderName: String? = null,
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
) {
    val isSignedIn: Boolean get() = accountEmail != null

    /** 업로드 가능: Drive 대상이면 로그인, 다른 계정 대상이면 항상 */
    val canUpload: Boolean get() = uploadAccountId != null || isSignedIn
}

@Singleton
class UserPreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val store get() = context.userPreferencesStore

    val preferences: Flow<UserPreferences> = store.data.map { prefs ->
        UserPreferences(
            accountEmail = prefs[KEY_ACCOUNT_EMAIL],
            accountName = prefs[KEY_ACCOUNT_NAME],
            uploadFolderId = prefs[KEY_UPLOAD_FOLDER_ID],
            uploadFolderName = prefs[KEY_UPLOAD_FOLDER_NAME],
            uploadAccountId = prefs[KEY_UPLOAD_ACCOUNT_ID],
            uploadWifiOnly = prefs[KEY_UPLOAD_WIFI_ONLY] ?: true,
            uploadChargingOnly = prefs[KEY_UPLOAD_CHARGING_ONLY] ?: false,
            autoBackupEnabled = prefs[KEY_AUTO_BACKUP_ENABLED] ?: false,
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

    suspend fun setAccount(email: String, displayName: String?) {
        store.edit { prefs ->
            prefs[KEY_ACCOUNT_EMAIL] = email
            if (displayName != null) prefs[KEY_ACCOUNT_NAME] = displayName else prefs.remove(KEY_ACCOUNT_NAME)
        }
    }

    /** 계정과 함께 업로드 폴더도 지운다 — 폴더 ID 는 계정에 종속된 값. */
    suspend fun clearAccount() {
        store.edit { prefs ->
            prefs.remove(KEY_ACCOUNT_EMAIL)
            prefs.remove(KEY_ACCOUNT_NAME)
            prefs.remove(KEY_UPLOAD_FOLDER_ID)
            prefs.remove(KEY_UPLOAD_FOLDER_NAME)
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
    }
}
