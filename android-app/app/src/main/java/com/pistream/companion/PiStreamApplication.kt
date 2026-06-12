package com.pistream.companion

import android.app.Application
import com.pistream.companion.data.AppContainer

class PiStreamApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
