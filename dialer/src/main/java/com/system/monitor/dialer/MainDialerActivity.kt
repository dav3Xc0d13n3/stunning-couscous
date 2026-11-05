package com.system.monitor.dialer

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainDialerActivity : AppCompatActivity() {

    private lateinit var etCode: EditText
    private lateinit var btnLaunch: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_dialer)

        etCode = findViewById(R.id.etCode)
        btnLaunch = findViewById(R.id.btnLaunch)
        tvStatus = findViewById(R.id.tvStatus)

        // Pre-fill with the secret code
        etCode.setText("*#*#2366#*#*")

        btnLaunch.setOnClickListener { launchAdmin() }

        // Also handle secret code intent if launched via dialer
        handleSecretCodeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSecretCodeIntent(intent)
    }

    private fun handleSecretCodeIntent(intent: Intent) {
        if (intent.action == "android.provider.Telephony.SECRET_CODE") {
            val code = intent.data?.schemeSpecificPart
            if (code == "2366") {
                Log.d("MainDialerActivity", "Received secret code intent, launching admin")
                launchAdmin()
                finish()
            }
        }
    }

    private fun launchAdmin() {
        val code = etCode.text.toString().trim()
        
        // Validate code format
        if (!code.startsWith("*#*#") || !code.endsWith("#*#*")) {
            Toast.makeText(this, "Invalid format. Use *#*#CODE#*#*", Toast.LENGTH_SHORT).show()
            return
        }

        val extractedCode = code.substring(4, code.length - 4)
        
        if (extractedCode != "2366") {
            Toast.makeText(this, "Wrong code. Expected 2366", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "Launching Admin Panel..."
        btnLaunch.isEnabled = false

        val adminIntent = Intent().apply {
            setClassName("com.system.monitor", "com.system.monitor.admin.AdminActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            startActivity(adminIntent)
            tvStatus.text = "Admin Panel Launched Successfully"
            Toast.makeText(this, "Admin panel opened", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainDialerActivity", "Failed to launch AdminActivity", e)
            tvStatus.text = "Error: ${e.message}"
            Toast.makeText(this, "Failed to launch admin: ${e.message}", Toast.LENGTH_LONG).show()
            
            // Try fallback
            val fallbackIntent = packageManager.getLaunchIntentForPackage("com.system.monitor")
            fallbackIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            fallbackIntent?.let { startActivity(it) }
        } finally {
            btnLaunch.isEnabled = true
        }
    }
}