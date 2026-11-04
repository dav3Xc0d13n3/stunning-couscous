package com.system.monitor.capabilities

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Surface
import com.google.gson.Gson
import com.system.monitor.AgentService
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ScreenCapability(private val service: AgentService) : AgentService.Capability {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val latestFrame = java.util.concurrent.atomic.AtomicReference<ByteArray>()
    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "capture" -> captureScreen(params.quality ?: 80)
            "start_stream" -> { startStream(); mapOf("status" to "streaming") }
            "stop_stream" -> { stopStream(); mapOf("status" to "stopped") }
            "get_frame" -> latestFrame.get()?.let { mapOf("image" to android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)) } ?: mapOf("error" to "no frame")
            else -> mapOf("error" to "invalid action")
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun captureScreen(quality: Int): Map<String, Any> {
        return mapOf("error" to "requires user consent - use start_stream with projection")
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun startStream() {
        // Requires MediaProjection permission granted via admin app
    }

    private fun stopStream() {
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
    }

    override fun cleanup() { stopStream() }

    data class Params(val action: String, val quality: Int? = null)
}