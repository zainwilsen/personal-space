package app.personalspace.vault.di

import app.personalspace.vault.core.common.SystemTimeProvider
import app.personalspace.vault.core.common.TimeProvider
import app.personalspace.vault.data.repository.PersonalSpaceRepositoryImpl
import app.personalspace.vault.data.sync.DriveRemoteDataSource
import app.personalspace.vault.data.sync.OkHttpDriveRemoteDataSource
import app.personalspace.vault.domain.repository.PersonalSpaceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {

    @Binds
    @Singleton
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider

    @Binds
    @Singleton
    abstract fun bindDriveRemoteDataSource(impl: OkHttpDriveRemoteDataSource): DriveRemoteDataSource

    @Binds
    @Singleton
    abstract fun bindPersonalSpaceRepository(impl: PersonalSpaceRepositoryImpl): PersonalSpaceRepository
}
