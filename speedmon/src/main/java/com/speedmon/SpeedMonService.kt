package com.speedmon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

class SpeedMonService : Service() {

    companion object {
        const val CHANNEL_ID = "speedmon_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.speedmon.ACTION_START"
        const val ACTION_STOP = "com.speedmon.ACTION_STOP"
    }

    private val binder = LocalBinder()
    private var locationManager: LocationManager? = null
    private var gpsListener: LocationListener? = null
    private var networkListener: LocationListener? = null
    private var lastLocation: Location? = null

    inner class LocalBinder : Binder() {
        fun getService(): SpeedMonService = this@SpeedMonService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager?
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceCompat.FOREGROUND_SERVICE_TYPE_LOCATION)
        startLocationUpdates()
        return START_STICKY
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
                Log.d("SpeedMonService", "GPS location: ${location.latitude}, ${location.longitude}, speed: ${location.speed}")
            }
        }

        networkListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (lastLocation == null || location.accuracy < lastLocation!!.accuracy) {
                    lastLocation = location
                    Log.d("SpeedMonService", "Network location: ${location.latitude}, ${location.longitude}, speed: ${location.speed}")
                }
            }
        }

        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, gpsListener!!)
            }
        } catch (e: SecurityException) {
            Log.w("SpeedMonService", "GPS permission denied", e)
        }

        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1f, networkListener!!)
            }
        } catch (e: SecurityException) {
            Log.w("SpeedMonService", "Network location permission denied", e)
        }
    }

    private fun stopLocationUpdates() {
        gpsListener?.let { listener ->
            try {
                locationManager?.removeUpdates(listener)
            } catch (e: Exception) {
                Log.w("SpeedMonService", "Remove GPS updates failed", e)
            }
        }
        networkListener?.let { listener ->
            try {
                locationManager?.removeUpdates(listener)
            } catch (e: Exception) {
                Log.w("SpeedMonService", "Remove network updates failed", e)
            }
        }
    }

    fun getLastLocation(): Location? = lastLocation

    fun getLastSpeedKph(): Float = lastLocation?.speed?.times(3.6f) ?: 0f

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        stopLocationUpdates()
        stopForeground(true)
        super.onDestroy()
    }
}