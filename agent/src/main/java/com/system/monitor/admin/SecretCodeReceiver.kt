package com.system.monitor.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SecretCodeReceiver : BroadcastReceiver() {

    companion object {
        private const val SECRET_CODE = "2366"
        private const val ACTION_SECRET_CODE = "android.provider.Telephony.SECRET_CODE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (ACTION_SECRET_CODE == intent.action) {
            val code = intent.data?.schemeSpecificPart
            if (code == SECRET_CODE) {
                Log.d("SecretCode", "Secret code *#*#$SECRET_CODE#*#* detected - launching AdminActivity")
                val adminIntent = Intent(context, AdminActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(adminIntent)
            }
        }
    }
}