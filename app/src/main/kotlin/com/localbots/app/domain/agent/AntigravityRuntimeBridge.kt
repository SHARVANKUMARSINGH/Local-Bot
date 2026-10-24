package com.localbots.app.domain.agent

import android.content.Context
import android.util.Log
import com.localbots.app.domain.nativebridge.NativeSpawnProcess
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

sealed interface AntigravityParsedEvent {
    data class Initialized(val conversationId: String) : AntigravityParsedEvent
    data class Text(val value: String) : AntigravityParsedEvent
    data class ToolStarted(val name: String, val detail: String) : AntigravityParsedEvent
    data class ToolCompleted(val name: String, val detail: String) : AntigravityParsedEvent
    data class Result(
        val conversationId: String?,
        val status: String,
        val response: String?,
        val error: String?
    ) : AntigravityParsedEvent
}

object AntigravityEventParser {
    fun parse(line: String): AntigravityParsedEvent? {
        val root = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (root.optString("event")) {
            "init" -> root.optString("conversation_id")
                .takeIf { it.isNotBlank() }
                ?.let { AntigravityParsedEvent.Initialized(it) }
            "step_update" -> {
                val step = root.optJSONObject("step_update") ?: return null
                val delta = step.optString("text_delta")
                if (delta.isNotEmpty()) return AntigravityParsedEvent.Text(delta)
                val type = step.optString("step_type")
                if (type.contains("tool", true) || type.contains("command", true)) {
                    val rawName = step.optString("tool_name").ifBlank {
                        step.optJSONObject("tool_info")?.optString("name").orEmpty()
                    }.ifBlank { type.ifBlank { "Tool" } }
                    val name = antigravityToolDisplayName(rawName)
                    val detail = antigravityToolDetail(step, rawName).ifBlank { name }
                    if (step.optString("state") in setOf("DONE", "ERROR")) {
                        val output = step.optJSONObject("tool_info")
                            ?.optString("output")
                            ?.takeIf { it.isNotBlank() }
                        val error = step.optJSONObject("tool_info")
                            ?.optJSONObject("error")
                            ?.optString("message")
                            ?.takeIf { it.isNotBlank() }
                        AntigravityParsedEvent.ToolCompleted(name, output ?: error ?: detail)
                    } else {
                        AntigravityParsedEvent.ToolStarted(name, detail)
                    }
                } else null
            }
            "result" -> {
                val result = root.optJSONObject("result") ?: root
                AntigravityParsedEvent.Result(
                    result.optString("conversation_id").takeIf { it.isNotBlank() },
                    result.optString("status", "SUCCESS"),
                    result.optString("response").takeIf { it.isNotBlank() },
                    result.optString("error").takeIf { it.isNotBlank() }
                )
            }
            else -> null
        }
    }
}

private fun antigravityToolDisplayName(name: String): String = when (name.lowercase()) {
    "run_command" -> "Bash"
    "write_to_file", "replace_file_content", "multi_replace_file_content" -> "Write"
    "view_file" -> "Read"
    "list_dir" -> "List files"
    "grep_search", "search_files" -> "Search"
    "schedule" -> "Wait"
    "manage_task" -> "Manage task"
    else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun antigravityToolDetail(step: JSONObject, rawName: String): String {
    step.optString("description").takeIf { it.isNotBlank() }?.let { return it }
    val info = step.optJSONObject("tool_info")
    val parameters = info?.optJSONObject("parameters")
    val preferredKeys = when (rawName.lowercase()) {
        "run_command" -> listOf("CommandLine", "command", "cmd")
        "write_to_file", "replace_file_content", "multi_replace_file_content" ->
            listOf("TargetFile", "AbsolutePath", "path", "file")
        "view_file" -> listOf("AbsolutePath", "TargetFile", "path", "file")
        "list_dir" -> listOf("DirectoryPath", "AbsolutePath", "path")
        "grep_search", "search_files" -> listOf("Query", "Pattern", "query", "pattern")
        "schedule" -> listOf("Prompt", "DurationSeconds")
        "manage_task" -> listOf("Action", "TaskId")
        else -> listOf("path", "file", "command", "target")
    }
    parameters?.let { p ->
        for (key in preferredKeys) {
            val v = p.optString(key)
            if (v.isNotBlank()) return v
        }
    }
    return ""
}

class AntigravityRuntimeBridge(
    private val context: Context
) {
    private val TAG = "AntigravityBridge"

    fun getAgyBinary(): File {
        val inFiles = File(context.filesDir, "bin/agy")
        if (inFiles.exists()) return inFiles
        val inRootfs = File(context.filesDir, "runtime/rootfs/root/.local/bin/agy")
        if (inRootfs.exists()) return inRootfs
        val hostAgy = File("/root/.local/bin/agy")
        if (hostAgy.exists()) return hostAgy
        return inFiles
    }

    suspend fun executePrompt(
        workspaceDir: File,
        prompt: String,
        model: String = "gemini-2.0-flash",
        conversationId: String? = null,
        onDelta: ((String) -> Unit)? = null,
        onToolAction: ((name: String, detail: String, isDone: Boolean) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val agyBin = getAgyBinary()
        if (!agyBin.exists()) {
            throw IllegalStateException("Antigravity CLI (agy) binary not installed at ${agyBin.absolutePath}")
        }

        workspaceDir.mkdirs()
        val command = buildList {
            add(agyBin.absolutePath)
            addAll(listOf("--input-format", "stream-json"))
            addAll(listOf("--output-format", "stream-json"))
            addAll(listOf("--print-timeout", "2m"))
            add("--dangerously-skip-permissions")
            if (model.isNotBlank()) {
                add("--model")
                add(model)
            }
            if (!conversationId.isNullOrBlank()) {
                add("--conversation-id")
                add(conversationId)
            } else {
                add("--new-project")
            }
        }

        val outputFile = File(context.cacheDir, "agy-run-${System.nanoTime()}.log")
        val env = mapOf(
            "HOME" to "/root",
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color"
        )

        val process = NativeSpawnProcess.start(
            argv = command,
            environment = env,
            cwd = workspaceDir.absolutePath,
            outputFile = outputFile,
            pseudoTerminal = false
        )

        try {
            val userRequest = JSONObject()
                .put("event", "user")
                .put("message", JSONObject().put("content", prompt))
                .toString() + "\n"

            process.outputStream.write(userRequest.toByteArray())
            process.outputStream.flush()
            process.outputStream.close()

            var offset = 0L
            val pending = StringBuilder()
            val textBuilder = StringBuilder()
            var resultResponse: String? = null
            var done = false

            fun handleLine(line: String): Boolean {
                when (val event = AntigravityEventParser.parse(line)) {
                    is AntigravityParsedEvent.Text -> {
                        textBuilder.append(event.value)
                        onDelta?.invoke(event.value)
                    }
                    is AntigravityParsedEvent.ToolStarted -> {
                        onToolAction?.invoke(event.name, event.detail, false)
                    }
                    is AntigravityParsedEvent.ToolCompleted -> {
                        onToolAction?.invoke(event.name, event.detail, true)
                    }
                    is AntigravityParsedEvent.Result -> {
                        resultResponse = event.response ?: event.error
                        return true
                    }
                    else -> Unit
                }
                return false
            }

            while (!done && (process.isAlive || outputFile.length() > offset)) {
                val available = outputFile.length() - offset
                if (available <= 0) {
                    delay(80)
                    continue
                }
                val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                val count = RandomAccessFile(outputFile, "r").use { file ->
                    file.seek(offset)
                    file.read(bytes)
                }
                if (count <= 0) continue
                offset += count
                pending.append(bytes.decodeToString(0, count))
                var newline = pending.indexOf("\n")
                while (newline >= 0) {
                    val line = pending.substring(0, newline).trimEnd('\r')
                    pending.delete(0, newline + 1)
                    if (handleLine(line)) {
                        done = true
                        break
                    }
                    newline = pending.indexOf("\n")
                }
            }

            if (!done && pending.isNotEmpty()) {
                handleLine(pending.toString().trim())
            }

            val finalReply = resultResponse?.takeIf { it.isNotBlank() }
                ?: textBuilder.toString().takeIf { it.isNotBlank() }
                ?: "Task processed successfully by Antigravity CLI."

            finalReply
        } finally {
            if (process.isAlive) process.destroy()
            runCatching { outputFile.delete() }
        }
    }
}
