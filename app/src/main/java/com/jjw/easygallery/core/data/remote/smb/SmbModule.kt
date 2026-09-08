package com.jjw.easygallery.core.data.remote.smb

import android.content.Context
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

@Module
@InstallIn(SingletonComponent::class)
object SmbModule {

    @Provides
    @IntoMap
    @RemoteKindKey(RemoteAccountKind.SMB)
    fun providesSmbFactory(
        @ApplicationContext context: Context,
        @Dispatcher(AppDispatcher.IO) ioDispatcher: CoroutineDispatcher,
    ): RemoteStorageFactory = RemoteStorageFactory { account, secret ->
        SmbStorage(context, account, secret, ioDispatcher)
    }

    @Provides
    fun providesHostDiscovery(@ApplicationContext context: Context): HostDiscovery = NsdHostDiscovery(context)
}
