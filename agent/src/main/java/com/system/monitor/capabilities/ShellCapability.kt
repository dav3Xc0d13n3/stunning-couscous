package com.system.monitor.capabilities

import com.google.gson.Gson
import com.system.monitor.AgentService
import java.io.BufferedReader
import java.io.InputStreamReader

class ShellCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return try {
            val pb = if (params.shell != null) arrayOf(params.shell, "-c", params.command) else arrayOf("sh", "-c", params.command)
            val proc = ProcessBuilder(*pb).redirectErrorStream(true).start()
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val exitCode = proc.waitFor()
            mapOf("output" to output, "exit_code" to exitCode)
        } catch (e: Exception) { mapOf("error" to e.message) }
    }

    override fun cleanup() {}

    data class Params(val command: String, val shell: String? = null, val timeout: Int = 30)
}