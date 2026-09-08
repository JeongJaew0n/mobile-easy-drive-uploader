package com.jjw.easygallery.core.data.remote

import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteModule {

    @Binds
    abstract fun bindsSecretStore(impl: KeystoreSecretStore): SecretStore

    /** 종류별 제공자 팩토리. S3·WebDAV 모듈이 `@IntoMap` 으로 채운다(없으면 빈 맵) */
    @Multibinds
    abstract fun storageFactories(): Map<RemoteAccountKind, RemoteStorageFactory>
}

/** `@IntoMap` 키 */
@dagger.MapKey
annotation class RemoteKindKey(val value: RemoteAccountKind)
