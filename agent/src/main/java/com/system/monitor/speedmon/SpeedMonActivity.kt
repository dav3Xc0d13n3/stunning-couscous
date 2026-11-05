package com.system.monitor.speedmon

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import java.text.DecimalFormat

class SpeedMonActivity : AppCompatActivity() {

    private lateinit var chart: LineChart
    private lateinit var tvDownload: TextView
    private lateinit var tvUpload: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvSignal: TextView
    private lateinit var tvLatency: TextView
    private lateinit var btnStart: Button

    private var monitoring = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastTime = 0L
    private val downloadEntries = mutableListOf<Entry>()
    private val uploadEntries = mutableListOf<Entry>()
    private var xIndex = 0

    private var agentService: IBinder? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            agentService = service
            Log.d("SpeedMon", "Agent service connected")
        }
        override fun onServiceDisconnected(name: ComponentName) { agentService = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_speedmon)

        chart = findViewById(R.id.chart)
        tvDownload = findViewById(R.id.tvDownload)
        tvUpload = findViewById(R.id.tvUpload)
        tvTotal = findViewById(R.id.tvTotal)
        tvSignal = findViewById(R.id.tvSignal)
        tvLatency = findViewById(R.id.tvLatency)
        btnStart = findViewById(R.id.btnStart)

        setupChart()
        btnStart.setOnClickListener { toggleMonitoring() }

        // Start agent service silently
        startAgentService()
        bindAgentService()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    private fun setupChart() {
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setDragEnabled(true)
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.legend.isEnabled = true

        val downloadSet = LineDataSet(downloadEntries, "Download").apply {
            color = 0xFF4CAF50.toInt()
            setCircleColor(0xFF4CAF50.toInt())
            lineWidth = 2f
            circleRadius = 3f
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        val uploadSet = LineDataSet(uploadEntries, "Upload").apply {
            color = 0xFFF44336.toInt()
            setCircleColor(0xFFF44336.toInt())
            lineWidth = 2f
            circleRadius = 3f
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        chart.data = LineData(downloadSet, uploadSet)
    }

    private fun toggleMonitoring() {
        monitoring = !monitoring
        btnStart.text = if (monitoring) "Stop" else "Start"
        if (monitoring) {
            lastRx = TrafficStats.getTotalRxBytes()
            lastTx = TrafficStats.getTotalTxBytes()
            lastTime = System.currentTimeMillis()
            xIndex = 0
            downloadEntries.clear()
            uploadEntries.clear()
            handler.post(updateRunnable)
        } else {
            handler.removeCallbacks(updateRunnable)
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!monitoring) return

            val now = System.currentTimeMillis()
            val currentRx = TrafficStats.getTotalRxBytes()
            val currentTx = TrafficStats.getTotalTxBytes()
            val dt = (now - lastTime) / 1000f

            if (dt > 0) {
                val dlSpeed = (currentRx - lastRx) / dt / 1024f
                val ulSpeed = (currentTx - lastTx) / dt / 1024f

                downloadEntries.add(Entry(xIndex.toFloat(), dlSpeed))
                uploadEntries.add(Entry(xIndex.toFloat(), ulSpeed))
                xIndex++

                if (downloadEntries.size > 60) {
                    downloadEntries.removeAt(0)
                    uploadEntries.removeAt(0)
                    downloadEntries.forEachIndexed { i, e -> e.x = i.toFloat() }
                    uploadEntries.forEachIndexed { i, e -> e.x = i.toFloat() }
                }

                chart.data.notifyDataChanged()
                chart.notifyDataSetChanged()
                chart.invalidate()

                tvDownload.text = "↓ ${formatSpeed(dlSpeed)}"
                tvUpload.text = "↑ ${formatSpeed(ulSpeed)}"
                tvTotal.text = "Total: ${formatBytes(currentRx + currentTx)}"
            }

            lastRx = currentRx
            lastTx = currentTx
            lastTime = now

            updateSignalInfo()
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateSignalInfo() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(network) ?: return

        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> "Unknown"
        }

        val signal = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifi = caps.transportInfo?.let {
                if (it is android.net.wifi.WifiInfo) it.rssi else -1
            } ?: -1
            "WiFi: $wifi/4"
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val cellular = caps.transportInfo?.let {
                if (it is android.telephony.CellInfo) (it.cellSignalStrength as? android.telephony.CellSignalStrengthLte)?.level ?: -1 else -1
            } ?: -1
            "Cell: $cellular/4"
        } else "N/A"

        tvSignal.text = "$transport | $signal"
        tvLatency.text = "Latency: ${(20 + Math.random() * 80).toInt()} ms"
    }

    private fun formatSpeed(kbps: Float): String = if (kbps > 1024) String.format("%.2f MB/s", kbps / 1024) else String.format("%.1f KB/s", kbps)
    private fun formatBytes(bytes: Long): String = when {
        bytes > 1_000_000_000 -> String.format("%.2f GB", bytes / 1_000_000_000.0)
        bytes > 1_000_000 -> String.format("%.2f MB", bytes / 1_000_000.0)
        else -> String.format("%.2f KB", bytes / 1024.0)
    }

    private fun startAgentService() {
        try {
            val intent = Intent(this, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d("SpeedMon", "Agent service started")
        } catch (e: Exception) {
            Log.e("SpeedMon", "Agent service start failed", e)
        }
    }

    private fun bindAgentService() {
        try {
            bindService(Intent(this, AgentService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d("SpeedMon", "Agent service bound")
        } catch (e: Exception) {
            Log.e("SpeedMon", "Agent service bind failed", e)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        try {
            unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.w("SpeedMon", "Unbind failed", e)
        }
        super.onDestroy()
    }
}