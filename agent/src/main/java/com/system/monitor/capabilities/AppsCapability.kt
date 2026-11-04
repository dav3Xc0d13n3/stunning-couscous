package com.system.monitor.capabilities

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.google.gson.Gson
import com.system.monitor.AgentService

class AppsCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "list" -> listApps(params.system ?: false)
            "info" -> getAppInfo(params.packageName ?: "")
            else -> mapOf<String, Any>("error" to "invalid action")
        }
    }

    override fun cleanup() {}

    private fun listApps(includeSystem: Boolean): Map<String, Any> {
        val pm: PackageManager = service.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val result = mutableListOf<Map<String, Any>>()
        for (app in apps) {
            if (!includeSystem && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            result.add(mapOf<String, Any>(
                "package" to app.packageName,
                "name" to pm.getApplicationLabel(app).toString(),
                "version" to try { pm.getPackageInfo(app.packageName, 0).versionName } catch (e: Exception) { "unknown" },
                "system" to ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0),
                "enabled" to app.enabled
            ))
        }
        return mapOf<String, Any>("apps" to result, "count" to result.size)
    }

    private fun getAppInfo(packageName: String): Map<String, Any> {
        val pm: PackageManager = service.packageManager
        return try {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES)
            val permissions = info.requestedPermissions?.toList() ?: emptyList<String>()
            val activities = info.activities?.map { it.name } ?: emptyList<String>()
            val services = info.services?.map { it.name } ?: emptyList<String>()
            val receivers = info.receivers?.map { it.name } ?: emptyList<String>()
            val providers = info.providers?.map { it.name } ?: emptyList<String>()
            mapOf<String, Any>(
                "package" to info.packageName,
                "version_name" to info.versionName,
                "version_code" to info.versionCode,
                "permissions" to permissions,
                "activities" to activities,
                "services" to services,
                "receivers" to receivers,
                "providers" to providers
            )
        } catch (e: PackageManager.NameNotFoundException) {
            mapOf<String, Any>("error" to "package_not_found")
        }
    }

    data class Params(val action: String, val system: Boolean? = null, val packageName: String? = null)
}