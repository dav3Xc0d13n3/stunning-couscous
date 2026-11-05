package com.xcodiene.speedmon

import android.app.Application
import android.content.Context
import android.content.Intent
import com.xcodiene.speedmon.CrashHandler

class SpeedMonApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
        startService(Intent(this, MonitorService::class.java))
    }

    companion object {
        @JvmStatic
        fun getContext(): Context? = instance
        private var instance: Context? = null
            @Suppress("UNUSED_PROPERTY")
            set(value) { field = value }
    }
}