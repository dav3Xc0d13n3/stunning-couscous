package com.system.monitor.capabilities

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.Gson
import com.system.monitor.AgentService
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RemoteControlCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val latestFrame = AtomicReference<ByteArray>()
    private val streaming = AtomicBoolean(false)
    private var streamExecutor: ScheduledExecutorService? = null
    private var frameRate = 15
    private var quality = 70
    private var scale = 1.0f
    private var accessibilityService: RemoteAccessibilityService? = null

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "start_stream" -> startStream(params.fps ?: 15, params.quality ?: 70, params.scale ?: 1.0f)
            "stop_stream" -> { stopStream(); mapOf("status" to "stopped") }
            "get_frame" -> latestFrame.get()?.let { mapOf("image" to android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)) } ?: mapOf("error" to "no frame")
            "tap" -> injectTap(params.x!!, params.y!!)
            "swipe" -> injectSwipe(params.x1!!, params.y1!!, params.x2!!, params.y2!!, params.duration ?: 300)
            "key" -> injectKey(params.keyCode!!, params.action ?: "down")
            "text" -> injectText(params.text!!)
            "launch_app" -> launchApp(params.packageName!!)
            "kill_app" -> killApp(params.packageName!!)
            "list_running" -> listRunningApps()
            "get_ui_tree" -> getUITree()
            "click_by_id" -> clickById(params.resourceId!!)
            "click_by_text" -> clickByText(params.text!!)
            "scroll" -> injectScroll(params.direction!!, params.x!!, params.y!!)
            "long_press" -> injectLongPress(params.x!!, params.y!!, params.duration ?: 1000)
            else -> mapOf("error" to "invalid action")
        }
    }

    private fun startStream(fps: Int, qual: Int, sc: Float): Map<String, Any> {
        if (streaming.get()) return mapOf("error" to "already streaming")
        frameRate = fps.coerceIn(1, 30)
        quality = qual.coerceIn(10, 100)
        scale = sc.coerceIn(0.1f, 1.0f)

        val projectionMgr = service.getSystemService(MediaProjectionManager::class.java)
        val intent = projectionMgr.createScreenCaptureIntent()
        return mapOf(
            "status" to "requires_projection_permission",
            "intent" to android.util.Base64.encodeToString(intent.toUri(0).toByteArray(), android.util.Base64.NO_WRAP)
        )
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun onProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            streaming.set(false)
            return
        }
        val projectionMgr = service.getSystemService(MediaProjectionManager::class.java)
        projection = projectionMgr.getMediaProjection(resultCode, data)
        setupVirtualDisplay()
        startStreaming()
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun setupVirtualDisplay() {
        val metrics = DisplayMetrics()
        service.getSystemService(DisplayManager::class.java).getDisplay(0).getRealMetrics(metrics)
        val width = (metrics.widthPixels * scale).toInt()
        val height = (metrics.heightPixels * scale).toInt()

        imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection!!.createVirtualDisplay(
            "RemoteControl", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, Handler(Looper.getMainLooper())
        )
    }

    private fun startStreaming() {
        streaming.set(true)
        streamExecutor = Executors.newSingleThreadScheduledExecutor()
        val interval = 1000 / frameRate
        streamExecutor!!.scheduleAtFixedRate({ captureFrame() }, 0, interval, TimeUnit.MILLISECONDS)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun captureFrame() {
        if (!streaming.get()) return
        val image = imageReader?.acquireLatestImage() ?: return
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()

        val compressed = compressJpeg(bytes, imageReader!!.width, imageReader!!.height, quality)
        latestFrame.set(compressed)
    }

    private fun compressJpeg(data: ByteArray, width: Int, height: Int, quality: Int): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data))
        val os = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, os)
        bitmap.recycle()
        return os.toByteArray()
    }

    private fun stopStream() {
        streaming.set(false)
        streamExecutor?.shutdownNow()
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        projection = null
    }

    private fun injectTap(x: Float, y: Float): Map<String, Any> {
        return accessibilityService?.injectTap(x, y) ?: mapOf("error" to "accessibility not enabled")
    }

    private fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Int): Map<String, Any> {
        return accessibilityService?.injectSwipe(x1, y1, x2, y2, duration) ?: mapOf("error" to "accessibility not enabled")
    }

    private fun injectKey(keyCode: Int, action: String): Map<String, Any> {
        return accessibilityService?.injectKey(keyCode, action) ?: mapOf("error" to "accessibility not enabled")
    }

    private fun injectText(text: String): Map<String, Any> {
        return accessibilityService?.injectText(text) ?: mapOf("error" to "accessibility not enabled")
    }

    private fun launchApp(packageName: String): Map<String, Any> {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent != null) { service.startActivity(intent); mapOf("status" to "launched") } else mapOf("error" to "no launcher")
    }

    private fun killApp(packageName: String): Map<String, Any> {
        val am = service.getSystemService(android.app.ActivityManager::class.java)
        am.killBackgroundProcesses(packageName)
        return mapOf("status" to "killed")
    }

    private fun listRunningApps(): Map<String, Any> {
        val am = service.getSystemService(android.app.ActivityManager::class.java)
        val tasks = am.getRunningTasks(50)
        val apps = tasks?.map { mapOf("package" to it.topActivity.packageName, "class" to it.topActivity.className, "id" to it.id) } ?: emptyList()
        return mapOf("apps" to apps)
    }

    private fun getUITree(): Map<String, Any> {
        return accessibilityService?.getUITree() ?: mapOf("error" to "accessibility not enabled")
    }

    private fun clickById(resourceId: String): Map<String, Any> {
        return accessibilityService?.clickById(resourceId) ?: mapOf("error" to "accessibility not enabled")
    }

    private fun clickByText(text: String): Map<String, Any> {
        return accessibilityService?.clickByText(text) ?: mapOf("error" to "accessibility not enabled")
    }

    private fun injectScroll(direction: String, x: Float, y: Float): Map<String, Any> {
        return accessibilityService?.injectScroll(direction, x, y) ?: mapOf("error" to "accessibility not enabled")
    }

    private fun injectLongPress(x: Float, y: Float, duration: Int): Map<String, Any> {
        return accessibilityService?.injectLongPress(x, y, duration) ?: mapOf("error" to "accessibility not enabled")
    }

    override fun cleanup() {
        stopStream()
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
    inner class RemoteAccessibilityService : AccessibilityService() {

        private var rootNode: AccessibilityNodeInfo? = null
        private val handler = Handler(Looper.getMainLooper())

        override fun onAccessibilityEvent(event: AccessibilityEvent) {
            rootNode = getRootInActiveWindow()
        }

        override fun onInterrupt() {}

        fun injectTap(x: Float, y: Float): Map<String, Any> {
            val gesture = GestureDescription.Builder()
            val path = Path().apply { moveTo(x, y) }
            gesture.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            dispatchGesture(gesture.build(), object : GestureResultCallback() {}, handler)
            return mapOf("status" to "tap_injected")
        }

        fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Int): Map<String, Any> {
            val gesture = GestureDescription.Builder()
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            gesture.addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong()))
            dispatchGesture(gesture.build(), object : GestureResultCallback() {}, handler)
            return mapOf("status" to "swipe_injected")
        }

        fun injectKey(keyCode: Int, action: String): Map<String, Any> {
            val event = KeyEvent(
                if (action == "down") KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                keyCode
            )
            return if (performGlobalAction(event)) mapOf("status" to "key_injected") else mapOf("error" to "key_failed")
        }

        fun injectText(text: String): Map<String, Any> {
            val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            rootNode?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            return mapOf("status" to "text_injected")
        }

        fun getUITree(): Map<String, Any> {
            val root = getRootInActiveWindow() ?: return mapOf("error" to "no_root")
            return mapOf("tree" to nodeToJson(root))
        }

        fun clickById(resourceId: String): Map<String, Any> {
            val root = getRootInActiveWindow() ?: return mapOf("error" to "no_root")
            val node = findNodeById(root, resourceId)
            return if (node != null) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); mapOf("status" to "clicked") } else mapOf("error" to "not_found")
        }

        fun clickByText(text: String): Map<String, Any> {
            val root = getRootInActiveWindow() ?: return mapOf("error" to "no_root")
            val node = findNodeByText(root, text)
            return if (node != null) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); mapOf("status" to "clicked") } else mapOf("error" to "not_found")
        }

        fun injectScroll(direction: String, x: Float, y: Float): Map<String, Any> {
            val action = when (direction) {
                "up" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "down" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "left" -> AccessibilityNodeInfo.ACTION_SCROLL_LEFT
                "right" -> AccessibilityNodeInfo.ACTION_SCROLL_RIGHT
                else -> return mapOf("error" to "invalid_direction")
            }
            rootNode?.performAction(action)
            return mapOf("status" to "scrolled")
        }

        fun injectLongPress(x: Float, y: Float, duration: Int): Map<String, Any> {
            val gesture = GestureDescription.Builder()
            val path = Path().apply { moveTo(x, y) }
            gesture.addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong()))
            dispatchGesture(gesture.build(), object : GestureResultCallback() {}, handler)
            return mapOf("status" to "long_press_injected")
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
            return mapOf(
                "class" to node.className.toString(),
                "id" to node.viewIdResourceName,
                "text" to node.text.toString(),
                "content_desc" to node.contentDescription.toString(),
                "bounds" to node.boundsInScreen.toShortString(),
                "clickable" to node.isClickable,
                "enabled" to node.isEnabled,
                "focusable" to node.isFocusable,
                "children" to children
            )
        }
    }

    data class Params(
        val action: String,
        val fps: Int? = null, val quality: Int? = null, val scale: Float? = null,
        val x: Float? = null, val y: Float? = null,
        val x1: Float? = null, val y1: Float? = null, val x2: Float? = null, val y2: Float? = null,
        val duration: Int? = null, val keyCode: Int? = null, val actionParam: String? = null,
        val packageName: String? = null, val resourceId: String? = null,
        val direction: String? = null, val text: String? = null
    )
}