package com.jjw.easygallery.core.data.remote

import android.content.Context
import com.jjw.easygallery.core.common.di.AppDispatcher
import com.jjw.easygallery.core.common.di.Dispatcher
import com.jjw.easygallery.core.data.auth.GuestTokenProvider
import com.jjw.easygallery.core.data.drive.AuthInterceptor
import com.jjw.easygallery.core.data.drive.DriveApi
import com.jjw.easygallery.core.data.drive.DriveHttpClient
import com.jjw.easygallery.core.data.drive.DriveRepository
import com.jjw.easygallery.core.data.drive.DriveRestRepository
import com.jjw.easygallery.core.data.drive.TokenAuthenticator
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.upload.DriveUploader
import com.jjw.easygallery.core.domain.model.Capability
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountInfo
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "다른 계정 업로드" 의 B 쪽 Drive 한 벌(`docs/plans/guest-account-upload/spec.md` §4.2).
 *
 * [storage] 는 워커가 올릴 때, [drive] 는 시작할 때(폴더 찾기·공유) 쓴다. 둘 다 같은 B 토큰을 쓴다.
 */
class GuestDrive(
    val email: String,
    val drive: DriveRepository,
    val storage: RemoteStorage,
)

/**
 * B 전용 Drive 를 조립한다. **기존 Drive 코드를 그대로 쓰고 토큰 공급자만 갈아 끼운다** —
 * 올리는 방식(multipart·resumable·재개)이 A 와 한 글자도 다르지 않아야 같은 버그를 두 번 고치지 않는다.
 *
 * OkHttp 는 A 의 클라이언트에서 갈라 나온다. 연결 풀과 스레드를 함께 쓰고, 인증만 B 로 바꾼다.
 */
@Singleton
class GuestDriveFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:DriveHttpClient private val primaryClient: OkHttpClient,
    private val json: Json,
    private val prefs: UserPreferencesRepository,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {
    fun create(email: String): GuestDrive {
        val tokens = GuestTokenProvider(context, email)
        val client = primaryClient.newBuilder()
            .apply { interceptors().removeAll { it is AuthInterceptor } }
            .addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens))
            .build()
        val api = apiWith(client)
        val drive = DriveRestRepository(api)
        val uploader = DriveUploader(context, api, client, json, prefs, ioDispatcher)
        return GuestDrive(email, drive, GuestDriveStorage(email, drive, uploader))
    }

    /**
     * 선택 창에서 막 받은 토큰이 **누구 것인지.** Play 서비스의 결과에는 고른 계정이 들어 있지 않다.
     * 앱 전체가 계정 정보를 Drive `about` 으로 얻는 것과 같은 방법이다.
     */
    suspend fun emailOf(accessToken: String): String {
        val client = primaryClient.newBuilder()
            .apply { interceptors().removeAll { it is AuthInterceptor } }
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $accessToken").build())
            }
            .build()
        return apiWith(client).about(fields = "user").user.emailAddress
    }

    private fun apiWith(client: OkHttpClient): DriveApi = Retrofit.Builder()
        .baseUrl(DriveApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(DriveApi::class.java)
}

/**
 * 워커가 B 로 올릴 때 보는 [RemoteStorage]. 올리기만 한다 — B 를 탐색하는 화면은 없다
 * (A 는 공유받은 폴더를 보기 전용 폴더로 본다).
 */
internal class GuestDriveStorage(
    email: String,
    private val drive: DriveRepository,
    private val uploader: RemoteUploader,
) : RemoteStorage {

    override val account: RemoteAccount = RemoteAccount(
        id = RemoteAccount.guestDriveId(email),
        kind = RemoteAccountKind.GOOGLE_DRIVE,
        displayName = email,
    )

    override val capabilities: Set<Capability> = setOf(Capability.RESUMABLE_UPLOAD)

    override suspend fun about(): RemoteAccountInfo {
        val a = drive.getAccount()
        return RemoteAccountInfo(a.displayName ?: a.email, a.email, a.storageUsedBytes, a.storageLimitBytes)
    }

    override suspend fun listChildren(parentId: String, pageToken: String?, foldersOnly: Boolean): RemotePage =
        drive.listChildren(parentId, pageToken, foldersOnly)

    override suspend fun createFolder(name: String, parentId: String): RemoteFolder = drive.createFolder(name, parentId)

    override suspend fun rename(entryId: String, name: String): RemoteEntry = unsupported()

    override suspend fun move(entryId: String, fromParentId: String, toParentId: String): RemoteEntry = unsupported()

    override suspend fun delete(entryId: String) = unsupported()

    override suspend fun restore(entryId: String) = unsupported()

    override fun uploader(): RemoteUploader = uploader

    private fun unsupported(): Nothing = throw UnsupportedOperationException("다른 계정 업로드는 올리기만 합니다")
}
