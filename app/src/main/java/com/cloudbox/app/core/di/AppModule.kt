package com.cloudbox.app.core.di

import android.content.Context
import androidx.work.WorkManager
import com.cloudbox.app.core.data.remote.LanzouApiClient
import com.cloudbox.app.core.data.remote.LanzouApiService
import com.cloudbox.app.core.data.repository.AuthRepositoryImpl
import com.cloudbox.app.core.data.repository.DirectLinkRepositoryImpl
import com.cloudbox.app.core.data.repository.DomainRepositoryImpl
import com.cloudbox.app.core.data.repository.DownloadRepositoryImpl
import com.cloudbox.app.core.data.repository.FileRepositoryImpl
import com.cloudbox.app.core.data.repository.SearchRepositoryImpl
import com.cloudbox.app.core.data.repository.ShareRepositoryImpl
import com.cloudbox.app.core.data.repository.UploadRepositoryImpl
import com.cloudbox.app.core.domain.repository.AuthRepository
import com.cloudbox.app.core.domain.repository.DirectLinkRepository
import com.cloudbox.app.core.domain.repository.DomainRepository
import com.cloudbox.app.core.domain.repository.DownloadRepository
import com.cloudbox.app.core.domain.repository.FileRepository
import com.cloudbox.app.core.domain.repository.SearchRepository
import com.cloudbox.app.core.domain.repository.ShareRepository
import com.cloudbox.app.core.domain.repository.UploadRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * 网络层提供者：统一从 [LanzouApiClient] 单例取实例。
 * 额外绑定 Hilt 不默认提供的 Context 与 WorkManager（UploadWorker 队列调度）。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideOkHttpClient(client: LanzouApiClient): OkHttpClient = client.okHttpClient

    @Provides
    @Singleton
    fun provideRetrofit(client: LanzouApiClient): Retrofit = client.retrofit

    @Provides
    @Singleton
    fun provideApiService(client: LanzouApiClient): LanzouApiService = client.apiService
}

/** Repository 接口绑定 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindDomainRepository(impl: DomainRepositoryImpl): DomainRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindFileRepository(impl: FileRepositoryImpl): FileRepository

    @Binds
    @Singleton
    abstract fun bindUploadRepository(impl: UploadRepositoryImpl): UploadRepository

    @Binds
    @Singleton
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository

    @Binds
    @Singleton
    abstract fun bindShareRepository(impl: ShareRepositoryImpl): ShareRepository

    @Binds
    @Singleton
    abstract fun bindDirectLinkRepository(impl: DirectLinkRepositoryImpl): DirectLinkRepository

    @Binds
    @Singleton
    abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository
}
