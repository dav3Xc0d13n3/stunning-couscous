package com.system.monitor.dialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SecretCodeReceiver : BroadcastReceiver() {

    companion object {
        private const val SECRET_CODE = "2366"
        private const val ADMIN_PACKAGE = "com.system.monitor"
        private const val ADMIN_CLASS = "com.system.monitor.admin.AdminActivity"
        private const val ACTION_SECRET_CODE = "android.provider.Telephony.SECRET_CODE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (ACTION_SECRET_CODE == intent.action) {
            val code = intent.data?.schemeSpecificPart
            if (code == SECRET_CODE) {
                Log.d("DialerSecretCode", "Secret code *#*#$SECRET_CODE#*#* detected - launching AdminActivity in $ADMIN_PACKAGE")
                
                val adminIntent = Intent().apply {
                    setClassName(ADMIN_PACKAGE, ADMIN_CLASS)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                
                try {
                    context.startActivity(adminIntent)
                    Log.d("DialerSecretCode", "AdminActivity launched successfully")
                } catch (e: Exception) {
                    Log.e("DialerSecretCode", "Failed to launch AdminActivity", e)
                    // Fallback: try to launch the main app
                    val fallbackIntent = context.packageManager.getLaunchIntentForPackage(ADMIN_PACKAGE)
                    fallbackIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    fallbackIntent?.let { context.startActivity(it) }
                }
            }
        }
    }
}