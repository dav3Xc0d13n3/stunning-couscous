package com.system.monitor.capabilities

import com.google.gson.Gson
import com.system.monitor.AgentService
import java.io.BufferedReader
import java.io.InputStreamReader

class ShellCapability(private val service: AgentService) : AgentService.Capability {

    private val gson = Gson()

    override fun execute(args: String?): Any {
        val params = gson.fromJson(args ?: "{}", Params::class.java)
        return when (params.action) {
            "exec" -> executeCommand(params.command ?: "")
            else -> mapOf("error" to "invalid action")
        }
    }

    override fun cleanup() {}

    private fun executeCommand(command: String): Map<String, Any> {
        if (command.isBlank()) return mapOf("error" to "empty_command")
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = StringBuilder()
            val error = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.forEachLine { output.append(it).append("\n") }
            }
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                reader.forEachLine { error.append(it).append("\n") }
            }
            val exitCode = process.waitFor()
            return mapOf(
                "exit_code" to exitCode,
                "stdout" to output.toString().trim(),
                "stderr" to error.toString().trim()
            )
        } catch (e: Exception) {
            return mapOf("error" to e.message ?: "execution_failed")
        }
    }

    data class Params(val action: String, val command: String? = null)
}