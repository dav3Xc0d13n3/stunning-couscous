package com.system.monitor

import android.app.Application
import android.content.Context
import android.content.Intent

class AgentApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        startService(Intent(this, AgentService::class.java))
    }

    companion object {
        @JvmStatic
        fun getContext(): Context? = instance
        private var instance: Context? = null
            @Suppress("UNUSED_PROPERTY")
            set(value) { field = value }
    }
}