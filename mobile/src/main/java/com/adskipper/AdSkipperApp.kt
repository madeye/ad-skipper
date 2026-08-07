package com.adskipper

import android.app.Application
import com.adskipper.core.data.SettingsRepository
import com.adskipper.core.data.StatsRepository
import com.adskipper.core.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class AdSkipperApp : Application() {

    lateinit var settingsRepo: SettingsRepository
        private set
    lateinit var statsRepo: StatsRepository
        private set
    lateinit var modelManager: ModelManager
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        settingsRepo = SettingsRepository(this)
        statsRepo = StatsRepository(this)
        modelManager = ModelManager(this)
        appScope.launch { settingsRepo.seedDefaultLauncher() }
    }

    companion object {
        fun get(context: android.content.Context): AdSkipperApp =
            context.applicationContext as AdSkipperApp
    }
}
