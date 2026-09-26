package com.jjw.easygallery.core.data.drive

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.jjw.easygallery.BuildConfig
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Drive API 용 인증된 OkHttpClient 를 구분하는 한정자 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class DriveHttpClient

/** Drive 인증이 붙은 이미지 로더 — 일반 이미지 로더와 섞이면 안 된다 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DriveImages

/** Drive 인증이 붙은 ExoPlayer 데이터 소스 — 기기 영상용 기본 소스와 섞이면 안 된다 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DriveMedia

@Module
@InstallIn(SingletonComponent::class)
abstract class DriveModule {

    @Binds
    abstract fun bindsDriveRepository(impl: DriveRestRepository): DriveRepository

    companion object {
        @Provides
        @Singleton
        fun providesJson(): Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }

        @Provides
        @Singleton
        @DriveHttpClient
        fun providesOkHttpClient(
            authInterceptor: AuthInterceptor,
            tokenAuthenticator: TokenAuthenticator,
        ): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .apply {
                if (BuildConfig.DEBUG) {
                    // 헤더를 찍되 액세스 토큰은 가린다 — logcat 에 그대로 남으면 그 자체가 자격 증명이다
                    val logging = HttpLoggingInterceptor()
                        .setLevel(HttpLoggingInterceptor.Level.HEADERS)
                        .apply { redactHeader("Authorization") }
                    addInterceptor(logging)
                }
            }
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 대용량 PUT 은 쓰기 시간이 길다
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        /**
         * Drive 파일을 **앱 안에서** 보여줄 때 쓰는 로더.
         *
         * 링크를 외부 Drive 앱으로 넘기면 기기에 여러 Google 계정이 있을 때 파일마다 계정을
         * 고르라고 묻는다(`authuser` 를 실어도 Drive 앱은 무시한다). 우리가 직접 받아
         * 그리면 그 물음 자체가 없어진다 — 인증은 이미 붙어 있는 [DriveHttpClient] 가 한다.
         */
        @Provides
        @Singleton
        @DriveImages
        fun providesDriveImageLoader(
            @ApplicationContext context: Context,
            @DriveHttpClient client: OkHttpClient,
        ): ImageLoader = ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()

        /**
         * Drive 영상을 **앱 안에서** 재생할 때 쓴다.
         *
         * 이미지와 같은 이유다(위 [providesDriveImageLoader]) — 링크를 외부로 넘기면 계정을
         * 고르라고 묻는다. 다만 영상은 통째로 받아두지 않고 스트리밍한다: ExoPlayer 가
         * Range 요청으로 필요한 만큼만 당겨 간다.
         *
         * Drive 는 `alt=media` 로 **원본을 그대로** 준다. 트랜스코딩이 없으므로 기기가 못 여는
         * 코덱이면 재생이 실패한다 — 화면이 그때 "기기에 저장해서 보라" 고 안내한다.
         */
        @Provides
        @Singleton
        @DriveMedia
        @OptIn(UnstableApi::class)
        fun providesDriveDataSourceFactory(@DriveHttpClient client: OkHttpClient): DataSource.Factory =
            OkHttpDataSource.Factory(client)

        @Provides
        @Singleton
        fun providesDriveApi(@DriveHttpClient client: OkHttpClient, json: Json): DriveApi =
            Retrofit.Builder()
                .baseUrl(DriveApi.BASE_URL)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(DriveApi::class.java)

        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 60L
        private const val WRITE_TIMEOUT_SECONDS = 120L
    }
}
