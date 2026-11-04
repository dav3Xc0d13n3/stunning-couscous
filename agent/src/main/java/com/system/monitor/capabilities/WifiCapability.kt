package com.system.monitor.capabilities

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.google.gson.Gson
import com.system.monitor.AgentService

class WifiCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "scan" -> scanWifi()
            "list" -> listNetworks()
            "info" -> getCurrentInfo()
            else -> mapOf<String, Any>("error" to "invalid action")
        }
    }

    override fun cleanup() {}

    private fun scanWifi(): Map<String, Any> {
        val wm = service.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val success = wm.startScan()
        if (!success) return mapOf<String, Any>("error" to "scan_failed")
        val results = wm.scanResults ?: emptyList()
        val networks = results.map { r ->
            mapOf<String, Any>(
                "ssid" to r.SSID.replace("\"", ""),
                "bssid" to r.BSSID,
                "level" to r.level,
                "frequency" to r.frequency,
                "capabilities" to r.capabilities
            )
        }
        return mapOf<String, Any>("networks" to networks, "count" to networks.size)
    }

    private fun listNetworks(): Map<String, Any> {
        val wm = service.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val configs = wm.configuredNetworks ?: emptyList()
        val networks = configs.map { c ->
            mapOf<String, Any>(
                "ssid" to c.SSID?.replace("\"", "") ?: "",
                "bssid" to c.BSSID,
                "network_id" to c.networkId,
                "priority" to c.priority
            )
        }
        return mapOf<String, Any>("networks" to networks, "count" to networks.size)
    }

    private fun getCurrentInfo(): Map<String, Any> {
        val wm = service.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        return mapOf<String, Any>(
            "ssid" to info.ssid?.replace("\"", "") ?: "",
            "bssid" to info.bssid,
            "ip_address" to info.ipAddress,
            "rssi" to info.rssi,
            "link_speed" to info.linkSpeed,
            "frequency" to info.frequency,
            "connected" to (info.networkId != -1),
            "network_type" to caps?.transportInfo?.toString() ?: "unknown"
        )
    }

    data class Params(val action: String)
}