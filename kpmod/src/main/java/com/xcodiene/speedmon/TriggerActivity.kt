package com.xcodiene.speedmon

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class TriggerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val action = intent.action
        Log.d("TriggerActivity", "Triggered with action: $action")
        
        when (action) {
            "com.xcodiene.speedmon.ON" -> {
                startService(Intent(this, MonitorService::class.java).apply {
                    action = MonitorService.ACTION_START
                })
                Toast.makeText(this, "SpeedMon Started", Toast.LENGTH_SHORT).show()
            }
            "com.xcodiene.speedmon.OFF" -> {
                startService(Intent(this, MonitorService::class.java).apply {
                    action = MonitorService.ACTION_STOP
                })
                Toast.makeText(this, "SpeedMon Stopped", Toast.LENGTH_SHORT).show()
            }
            "com.xcodiene.speedmon.TOGGLE" -> {
                Toast.makeText(this, "Toggle not implemented", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Toast.makeText(this, "Unknown trigger: $action", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}