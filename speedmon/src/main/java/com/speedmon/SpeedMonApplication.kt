package com.speedmon

import android.app.Application
import com.speedmon.CrashHandler

class SpeedMonApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
    }
}