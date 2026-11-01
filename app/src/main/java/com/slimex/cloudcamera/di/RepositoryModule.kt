package com.slimex.cloudcamera.di

import com.slimex.cloudcamera.auth.SupabaseAuthManager
import com.slimex.cloudcamera.data.repository.CloudConfigRepositoryImpl
import com.slimex.cloudcamera.data.repository.CloudGalleryRepositoryImpl
import com.slimex.cloudcamera.data.repository.LocalMediaRepositoryImpl
import com.slimex.cloudcamera.data.repository.R2ConfigRepositoryImpl
import com.slimex.cloudcamera.data.repository.SettingsRepositoryImpl
import com.slimex.cloudcamera.data.repository.UploadRepositoryImpl
import com.slimex.cloudcamera.domain.repository.AuthRepository
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.domain.repository.CloudGalleryRepository
import com.slimex.cloudcamera.domain.repository.LocalMediaRepository
import com.slimex.cloudcamera.domain.repository.R2ConfigRepository
import com.slimex.cloudcamera.domain.repository.SettingsRepository
import com.slimex.cloudcamera.domain.repository.UploadRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: SupabaseAuthManager): AuthRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindCloudConfigRepository(impl: CloudConfigRepositoryImpl): CloudConfigRepository

    @Binds
    @Singleton
    abstract fun bindLocalMediaRepository(impl: LocalMediaRepositoryImpl): LocalMediaRepository

    @Binds
    @Singleton
    abstract fun bindCloudGalleryRepository(impl: CloudGalleryRepositoryImpl): CloudGalleryRepository

    @Binds
    @Singleton
    abstract fun bindUploadRepository(impl: UploadRepositoryImpl): UploadRepository

    @Binds
    @Singleton
    abstract fun bindR2ConfigRepository(impl: R2ConfigRepositoryImpl): R2ConfigRepository
}
