package com.jjw.easygallery.core.data.auth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    abstract fun bindsAuthRepository(impl: GoogleAuthRepository): AuthRepository

    @Binds
    abstract fun bindsTokenProvider(impl: GoogleAuthRepository): TokenProvider
}
