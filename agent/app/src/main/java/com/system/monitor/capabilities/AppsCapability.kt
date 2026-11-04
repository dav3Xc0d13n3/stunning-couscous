package com.system.monitor.capabilities

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import com.google.gson.Gson
import com.system.monitor.AgentService
import android.util.Base64
import java.io.ByteArrayOutputStream

class AppsCapability(private val service: AgentService) : AgentService.Capability {

    private val pm: PackageManager = service.packageManager
    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "list" -> listApps()
            "info" -> getAppInfo(params.packageName!!)
            "uninstall" -> uninstallApp(params.packageName!!)
            "launch" -> launchApp(params.packageName!!)
            else -> mapOf("error" to "invalid action")
        }
    }

    private fun listApps(): Map<String, Any> {
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val list = apps.map { app ->
            val icon = app.loadIcon(pm)
            val baos = ByteArrayOutputStream()
            (icon as? android.graphics.drawable.BitmapDrawable)?.bitmap?.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
            mapOf(
                "package" to app.packageName,
                "name" to pm.getApplicationLabel(app).toString(),
                "version" to pm.getPackageInfo(app.packageName, 0).versionName,
                "icon" to Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP),
                "system" to (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }
        return mapOf("apps" to list)
    }

    private fun getAppInfo(pkg: String): Map<String, Any> {
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            val pkgInfo = pm.getPackageInfo(pkg, 0)
            mapOf(
                "package" to pkg,
                "name" to pm.getApplicationLabel(info).toString(),
                "version" to pkgInfo.versionName,
                "version_code" to pkgInfo.versionCode,
                "install_time" to pkgInfo.firstInstallTime,
                "update_time" to pkgInfo.lastUpdateTime,
                "permissions" to pkgInfo.requestedPermissions?.toList(),
                "data_dir" to info.dataDir,
                "source_dir" to info.sourceDir
            )
        } catch (e: Exception) { mapOf("error" to e.message) }
    }

    private fun uninstallApp(pkg: String): Map<String, Any> {
        val intent = Intent(Intent.ACTION_DELETE).apply { data = Uri.parse("package:$pkg") }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        return mapOf("status" to "uninstall_intent_sent")
    }

    private fun launchApp(pkg: String): Map<String, Any> {
        val intent = pm.getLaunchIntentForPackage(pkg)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        intent?.let { service.startActivity(it) }
        return mapOf("status" to if (intent != null) "launched" else "no_launcher")
    }

    override fun cleanup() {}

    data class Params(val action: String, val packageName: String? = null)
}