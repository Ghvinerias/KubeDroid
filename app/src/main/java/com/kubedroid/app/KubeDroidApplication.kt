package com.kubedroid.app

import android.app.Application
import com.kubedroid.feature.widget.WidgetSyncBootstrap
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KubeDroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WidgetSyncBootstrap.onAppStart(this)
    }
}
