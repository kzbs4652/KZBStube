package com.kzbs.kzbstube

import android.app.Application
import android.content.Context

class KZBStubeApp : Application() {
    companion object {
        lateinit var appContext: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }
}
