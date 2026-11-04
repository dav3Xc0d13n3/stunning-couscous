package com.system.monitor.capabilities

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.google.gson.Gson
import com.system.monitor.AgentService

class LocationCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()
    private var lastLocation: Location? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { lastLocation = loc }
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "get" -> getLocation()
            "start" -> startUpdates()
            "stop" -> stopUpdates()
            else -> mapOf<String, Any>("error" to "invalid action")
        }
    }

    override fun cleanup() {
        stopUpdates()
    }

    private fun getLocation(): Map<String, Any> {
        val lm = service.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.allProviders
        for (provider in providers) {
            val loc = lm.getLastKnownLocation(provider)
            if (loc != null) {
                return locationToMap(loc)
            }
        }
        return mapOf<String, Any>("error" to "no_location_available")
    }

    private fun startUpdates(): Map<String, Any> {
        val lm = service.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = LocationManager.GPS_PROVIDER
        try {
            lm.requestLocationUpdates(provider, 5000, 10f, listener)
            return mapOf<String, Any>("status" to "updates_started")
        } catch (e: SecurityException) {
            return mapOf<String, Any>("error" to "permission_denied")
        }
    }

    private fun stopUpdates(): Map<String, Any> {
        val lm = service.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(listener)
        return mapOf<String, Any>("status" to "updates_stopped")
    }

    private fun locationToMap(loc: Location): Map<String, Any> {
        return mapOf<String, Any>(
            "latitude" to loc.latitude,
            "longitude" to loc.longitude,
            "accuracy" to loc.accuracy,
            "altitude" to loc.altitude,
            "speed" to loc.speed,
            "bearing" to loc.bearing,
            "time" to loc.time,
            "provider" to (loc.provider ?: "unknown")
        )
    }

    data class Params(val action: String)
}