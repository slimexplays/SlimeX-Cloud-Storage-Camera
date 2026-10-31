package com.slimex.cloudcamera.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.slimex.cloudcamera.core.constants.AppConstants
import com.slimex.cloudcamera.core.network.AndroidConnectivityObserver
import com.slimex.cloudcamera.core.network.ConnectivityObserver
import com.slimex.cloudcamera.core.util.DefaultDispatchersProvider
import com.slimex.cloudcamera.core.util.DispatchersProvider
import com.slimex.cloudcamera.security.AesGcmMediaCrypto
import com.slimex.cloudcamera.security.EncryptedSecureStore
import com.slimex.cloudcamera.security.MediaCrypto
import com.slimex.cloudcamera.security.SecureStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** App-wide singletons: dispatchers, networking, storage primitives. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val Context.settingsDataStore: DataStore<Preferences> by
        preferencesDataStore(name = AppConstants.SETTINGS_DATASTORE_FILE)

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // No overall call timeout: a 500 MB video on a slow connection must be allowed to finish.
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideDispatchers(): DispatchersProvider = DefaultDispatchersProvider()
}

/** Interface → implementation bindings. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    @Singleton
    abstract fun bindSecureStore(impl: EncryptedSecureStore): SecureStore

    @Binds
    @Singleton
    abstract fun bindConnectivityObserver(impl: AndroidConnectivityObserver): ConnectivityObserver

    @Binds
    @Singleton
    abstract fun bindMediaCrypto(impl: AesGcmMediaCrypto): MediaCrypto
}
