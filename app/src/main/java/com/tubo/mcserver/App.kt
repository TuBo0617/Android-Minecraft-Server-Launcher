package com.tubo.mcserver

import android.app.Application
import android.content.Context

class App : Application() {
    companion object {
        lateinit var ctx: Context
            private set
    }
    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
    }
}
