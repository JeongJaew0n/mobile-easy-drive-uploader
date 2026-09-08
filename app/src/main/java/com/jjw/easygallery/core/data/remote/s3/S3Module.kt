package com.jjw.easygallery.core.data.remote.s3

import android.content.Context
import com.jjw.easygallery.BuildConfig
import com.jjw.easygallery.core.common.di.AppDispatcher
import com.jjw.easygallery.core.common.di.Dispatcher
import com.jjw.easygallery.core.data.remote.RemoteKindKey
import com.jjw.easygallery.core.data.remote.RemoteStorageFactory
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** 인증 인터셉터가 없는 공용 OkHttp(제공자별 서명/Basic 인증은 각자 붙인다) */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class PlainHttpClient

@Module
@InstallIn(SingletonComponent::class)
object S3Module {

    @Provides
    @Singleton
    @PlainHttpClient
    fun providesPlainClient(): OkHttpClient = OkHttpClient.Builder()
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
        }
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @Provides
    @IntoMap
    @RemoteKindKey(RemoteAccountKind.S3)
    fun providesS3Factory(
        @ApplicationContext context: Context,
        @PlainHttpClient client: OkHttpClient,
        @Dispatcher(AppDispatcher.IO) ioDispatcher: CoroutineDispatcher,
    ): RemoteStorageFactory = RemoteStorageFactory { account, secret ->
        S3Storage(context, account, secret, client, ioDispatcher)
    }

    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 60L
    private const val WRITE_TIMEOUT_SECONDS = 120L
}
