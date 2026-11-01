package com.slimex.cloudcamera

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SlimeXApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            // Release builds keep WorkManager quiet so no payload detail reaches logcat.
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.INFO else Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: SlimeXApplication
            private set
    }
}
