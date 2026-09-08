package com.jjw.easygallery.core.data.remote.webdav

import android.content.Context
import com.jjw.easygallery.core.common.di.AppDispatcher
import com.jjw.easygallery.core.common.di.Dispatcher
import com.jjw.easygallery.core.data.remote.RemoteKindKey
import com.jjw.easygallery.core.data.remote.RemoteStorageFactory
import com.jjw.easygallery.core.data.remote.s3.PlainHttpClient
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object WebDavModule {

    @Provides
    @IntoMap
    @RemoteKindKey(RemoteAccountKind.WEBDAV)
    fun providesWebDavFactory(
        @ApplicationContext context: Context,
        @PlainHttpClient client: OkHttpClient,
        @Dispatcher(AppDispatcher.IO) ioDispatcher: CoroutineDispatcher,
    ): RemoteStorageFactory = RemoteStorageFactory { account, secret ->
        WebDavStorage(context, account, secret, client, ioDispatcher)
    }
}
