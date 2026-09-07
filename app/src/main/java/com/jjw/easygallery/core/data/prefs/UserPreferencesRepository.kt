package com.jjw.easygallery.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
    /** 사진 백업은 데이터 요금이 크므로 기본은 Wi-Fi 전용 */
    val uploadWifiOnly: Boolean = true,
    val uploadChargingOnly: Boolean = false,
) {
    val isSignedIn: Boolean get() = accountEmail != null
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
            uploadWifiOnly = prefs[KEY_UPLOAD_WIFI_ONLY] ?: true,
            uploadChargingOnly = prefs[KEY_UPLOAD_CHARGING_ONLY] ?: false,
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

    suspend fun setUploadFolder(id: String, name: String) {
        store.edit { prefs ->
            prefs[KEY_UPLOAD_FOLDER_ID] = id
            prefs[KEY_UPLOAD_FOLDER_NAME] = name
        }
    }

    suspend fun setUploadWifiOnly(enabled: Boolean) {
        store.edit { it[KEY_UPLOAD_WIFI_ONLY] = enabled }
    }

    suspend fun setUploadChargingOnly(enabled: Boolean) {
        store.edit { it[KEY_UPLOAD_CHARGING_ONLY] = enabled }
    }

    private companion object {
        val KEY_UPLOAD_WIFI_ONLY = booleanPreferencesKey("upload_wifi_only")
        val KEY_UPLOAD_CHARGING_ONLY = booleanPreferencesKey("upload_charging_only")
        val KEY_ACCOUNT_EMAIL = stringPreferencesKey("account_email")
        val KEY_ACCOUNT_NAME = stringPreferencesKey("account_name")
        val KEY_UPLOAD_FOLDER_ID = stringPreferencesKey("upload_folder_id")
        val KEY_UPLOAD_FOLDER_NAME = stringPreferencesKey("upload_folder_name")
    }
}
