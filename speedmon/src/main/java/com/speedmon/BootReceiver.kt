package com.speedmon

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
            
            Log.d("SpeedMonBoot", "Boot completed, starting SpeedMonService")
            val serviceIntent = Intent(context, SpeedMonService::class.java)
            serviceIntent.action = SpeedMonService.ACTION_START
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}