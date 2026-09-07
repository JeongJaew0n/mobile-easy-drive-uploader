package com.jjw.easygallery.core.data.drive

import com.jjw.easygallery.BuildConfig
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
                    addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.HEADERS))
                }
            }
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 대용량 PUT 은 쓰기 시간이 길다
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

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
