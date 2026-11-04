package com.system.monitor.capabilities

import android.content.Context
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.ScanResult
import com.google.gson.Gson
import com.system.monitor.AgentService

class WifiCapability(private val service: AgentService) : AgentService.Capability {

    private val wifiManager: WifiManager = service.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "current" -> getCurrentNetwork()
            "scan" -> scanNetworks()
            "saved" -> getSavedNetworks()
            else -> mapOf("error" to "invalid action")
        }
    }

    private fun getCurrentNetwork(): Map<String, Any> {
        val info: WifiInfo? = wifiManager.connectionInfo
        return info?.let {
            mapOf(
                "ssid" to it.ssid.replace("\"", ""),
                "bssid" to it.bssid,
                "ip" to intToIp(it.ipAddress),
                "rssi" to it.rssi,
                "frequency" to it.frequency,
                "speed" to it.linkSpeed
            )
        } ?: mapOf("error" to "no wifi connection")
    }

    private fun scanNetworks(): Map<String, Any> {
        val results = wifiManager.scanResults
        val networks = results?.map { r ->
            mapOf("ssid" to r.SSID, "bssid" to r.BSSID, "level" to r.level, "frequency" to r.frequency, "capabilities" to r.capabilities)
        } ?: emptyList()
        return mapOf("networks" to networks)
    }

    private fun getSavedNetworks(): Map<String, Any> {
        val configs = wifiManager.configuredNetworks
        val networks = configs?.map { c ->
            mapOf("ssid" to c.SSID?.replace("\"", ""), "bssid" to c.BSSID, "priority" to c.priority)
        } ?: emptyList()
        return mapOf("networks" to networks)
    }

    private fun intToIp(ip: Int): String = (ip and 0xFF).toString() + "." + (ip shr 8 and 0xFF) + "." + (ip shr 16 and 0xFF) + "." + (ip shr 24 and 0xFF)

    override fun cleanup() {}

    data class Params(val action: String)
}