package com.system.monitor.dialer

import android.app.Application
import android.content.Context

class DialerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
    }

    companion object {
        @JvmStatic
        fun getContext(): Context? = instance
        private var instance: Context? = null
            @Suppress("UNUSED_PROPERTY")
            set(value) { field = value }
    }
}