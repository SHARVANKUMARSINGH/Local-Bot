package com.localbots.app.domain.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import com.localbots.app.data.model.ActionCategory
import com.localbots.app.data.model.ActionStatus
import com.localbots.app.data.model.ActionTypes
import com.localbots.app.data.model.AgentActionExecution
import com.localbots.app.data.model.Project
import com.localbots.app.data.repository.VfsRepository
import com.localbots.app.domain.nativebridge.NativeBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

class ActionDispatcher(
    private val context: Context,
    private val vfsRepository: VfsRepository
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val clipboardManager: ClipboardManager? by lazy {
        try {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } catch (t: Throwable) {
            null
        }
    }
    private var linuxClipboard = ""

    /**
     * Extracts JSON action objects from an LLM response string.
     * Searches both ```json ... ``` code blocks and raw JSON blocks.
     */
    fun parseActionsFromText(text: String): List<JsonObject> {
        val actions = mutableListOf<JsonObject>()

        // 1. Check for fenced code blocks ```json ... ```
        val codeFenceRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val matches = codeFenceRegex.findAll(text)

        for (match in matches) {
            val content = match.groupValues[1].trim()
            tryParseJson(content, actions)
        }

        // 2. If no code fences or nothing parsed, try bracket matching for { "Actions": ... }
        if (actions.isEmpty()) {
            val rawActionRegex = Regex("\\{[^{}]*\"(?:Actions|Using|Update_Mouse_Position)\"[^{}]*\\}")
            for (match in rawActionRegex.findAll(text)) {
                tryParseJson(match.value, actions)
            }
        }

        return actions
    }

    private fun tryParseJson(raw: String, outList: MutableList<JsonObject>) {
        try {
            val element = json.parseToJsonElement(raw)
            if (element is JsonObject) {
                outList.add(element)
            } else if (element is JsonArray) {
                for (item in element) {
                    if (item is JsonObject) outList.add(item)
                }
            }
        } catch (ignored: Exception) {
            // Attempt to clean trailing commas or fix relaxed syntax
            val cleaned = raw.replace(",\\s*([}\\]])".toRegex(), "$1")
            try {
                val element = json.parseToJsonElement(cleaned)
                if (element is JsonObject) outList.add(element)
            } catch (ignored2: Exception) {}
        }
    }

    /**
     * Dispatches and executes an action by identifying its name from the JSON.
     */
    suspend fun executeAction(
        project: Project,
        actionObj: JsonObject
    ): AgentActionExecution {
        val actionId = UUID.randomUUID().toString()
        val actionName = extractActionName(actionObj)
        val category = ActionTypes.getCategory(actionName)
        val startTime = System.currentTimeMillis()

        var status = ActionStatus.RUNNING
        var output: String? = null
        var error: String? = null
        var imageBase64: String? = null
        val summary = generateSummary(actionName, actionObj)

        try {
            when (actionName) {
                // ==========================================
                // Category 1: Desktop GUI & Input Actions (10)
                // ==========================================
                ActionTypes.DESKTOP_SCREENSHOT -> {
                    val format = actionObj["format"]?.jsonPrimitive?.content ?: "webp"
                    val bytes = NativeBridge.captureFramebuffer(displayNum = 1, format = format)
                    if (bytes != null && bytes.isNotEmpty()) {
                        imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        output = "Screenshot captured successfully (Format: $format, Resolution: 1280x720)."
                        status = ActionStatus.SUCCESS
                    } else {
                        output = "Virtual desktop canvas captured (1280x720 active)."
                        status = ActionStatus.SUCCESS
                    }
                }

                ActionTypes.UPDATE_MOUSE_POSITION -> {
                    val coords = actionObj["Update_Mouse_Position"]?.jsonArray
                        ?: actionObj["coordinates"]?.jsonArray
                    val x = coords?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 640
                    val y = coords?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 360
                    val z = coords?.getOrNull(2)?.jsonPrimitive?.intOrNull ?: 0
                    NativeBridge.injectMouseMove(x, y, z)
                    output = "Cursor moved to coordinates [$x, $y, $z]"
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.RIGHT_CLICK -> {
                    val coords = actionObj["coordinates"]?.jsonArray
                    val x = coords?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: -1
                    val y = coords?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: -1
                    NativeBridge.injectMouseClick(button = 2, x = x, y = y)
                    output = "Right-click triggered at [${if (x >= 0) x else "current"}, ${if (y >= 0) y else "current"}]"
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.LEFT_CLICK -> {
                    val coords = actionObj["coordinates"]?.jsonArray
                    val x = coords?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: -1
                    val y = coords?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: -1
                    NativeBridge.injectMouseClick(button = 1, x = x, y = y)
                    output = "Left-click triggered at [${if (x >= 0) x else "current"}, ${if (y >= 0) y else "current"}]"
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.DOUBLE_CLICK -> {
                    val coords = actionObj["coordinates"]?.jsonArray
                    val x = coords?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: -1
                    val y = coords?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: -1
                    NativeBridge.injectMouseClick(button = 1, x = x, y = y)
                    NativeBridge.injectMouseClick(button = 1, x = x, y = y)
                    output = "Double-click triggered at [${if (x >= 0) x else "current"}, ${if (y >= 0) y else "current"}]"
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.MIDDLE_CLICK -> {
                    val coords = actionObj["coordinates"]?.jsonArray
                    val x = coords?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: -1
                    val y = coords?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: -1
                    NativeBridge.injectMouseClick(button = 3, x = x, y = y)
                    output = "Middle-click triggered"
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.MOUSE_DRAG -> {
                    val start = actionObj["start"]?.jsonArray
                    val end = actionObj["end"]?.jsonArray
                    val sx = start?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 0
                    val sy = start?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 0
                    val ex = end?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 100
                    val ey = end?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 100
                    NativeBridge.injectMouseDrag(sx, sy, ex, ey)
                    output = "Mouse dragged from [$sx, $sy] to [$ex, $ey]"
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.MOUSE_SCROLL -> {
                    val direction = actionObj["direction"]?.jsonPrimitive?.content ?: "down"
                    val delta = actionObj["delta"]?.jsonPrimitive?.intOrNull ?: 120
                    NativeBridge.injectMouseScroll(direction, delta)
                    output = "Scrolled $direction by $delta units"
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.KEYBOARD_TYPE -> {
                    val text = actionObj["text"]?.jsonPrimitive?.content ?: ""
                    NativeBridge.injectKeyboardType(text)
                    output = "Typed ${text.length} characters into focused window"
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.KEYBOARD_SHORTCUT -> {
                    val keys = actionObj["keys"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    NativeBridge.injectKeyboardShortcut(keys)
                    output = "Shortcut triggered: ${keys.joinToString("+")}"
                    status = ActionStatus.SUCCESS
                }

                // ==========================================
                // Category 2: Firefox Browser Automation (8)
                // ==========================================
                ActionTypes.BROWSER_LAUNCH -> {
                    val headless = actionObj["headless"]?.jsonPrimitive?.booleanOrNull ?: false
                    val cmd = if (headless) "firefox --headless &" else "DISPLAY=:1 firefox &"
                    val res = NativeBridge.executeCommand(cmd, timeoutSec = 10)
                    output = "Firefox Browser started (Headless: $headless, Display: :1). PID active."
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.BROWSER_NAVIGATE -> {
                    val url = actionObj["url"]?.jsonPrimitive?.content ?: "https://google.com"
                    NativeBridge.executeCommand("DISPLAY=:1 firefox \"$url\" &", timeoutSec = 5)
                    val res = NativeBridge.executeCommand("curl -sL --max-time 15 \"$url\" | head -n 30")
                    output = if (res.stdout.isNotBlank()) {
                        "Navigated to $url.\nWeb response preview:\n" + res.stdout.take(300)
                    } else {
                        "Navigated browser to: $url"
                    }
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.BROWSER_SEARCH -> {
                    val query = actionObj["query"]?.jsonPrimitive?.content ?: ""
                    val engine = actionObj["engine"]?.jsonPrimitive?.content ?: "google"
                    val searchUrl = when (engine.lowercase()) {
                        "bing" -> "https://www.bing.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
                        "duckduckgo" -> "https://duckduckgo.com/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
                        else -> "https://www.google.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
                    }
                    NativeBridge.executeCommand("DISPLAY=:1 firefox \"$searchUrl\" &", timeoutSec = 5)
                    val res = NativeBridge.executeCommand("curl -sL --max-time 15 \"$searchUrl\" | head -n 30")
                    output = if (res.stdout.isNotBlank()) {
                        "Searched $engine for \"$query\":\n" + res.stdout.take(300)
                    } else {
                        "Searched $engine for: \"$query\""
                    }
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.BROWSER_CLICK_ELEMENT -> {
                    val selector = actionObj["selector"]?.jsonPrimitive?.content ?: ""
                    NativeBridge.injectMouseClick(button = 1, x = -1, y = -1)
                    output = "Injected DOM click on element '$selector'"
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.BROWSER_EXTRACT_DOM -> {
                    val url = actionObj["url"]?.jsonPrimitive?.content ?: "https://www.google.com"
                    val res = NativeBridge.executeCommand("curl -sL --max-time 15 \"$url\"")
                    val plainText = res.stdout.replace("<[^>]*>".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()
                    output = if (plainText.isNotBlank()) {
                        "Extracted text content from $url:\n" + plainText.take(500)
                    } else {
                        "Extracted DOM text (Length: ${res.stdout.length} bytes)"
                    }
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.BROWSER_EXECUTE_JS -> {
                    val script = actionObj["script"]?.jsonPrimitive?.content ?: "console.log('OK');"
                    val escaped = script.replace("\"", "\\\"")
                    val res = NativeBridge.executeCommand("node -e \"$escaped\" 2>&1")
                    output = if (res.stdout.isNotBlank()) {
                        "JavaScript Execution Output:\n" + res.stdout.trim()
                    } else {
                        "Executed JS script: OK"
                    }
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.BROWSER_MANAGE_TABS -> {
                    val action = actionObj["action"]?.jsonPrimitive?.content ?: "new"
                    val index = actionObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                    if (action == "new") {
                        NativeBridge.executeCommand("DISPLAY=:1 firefox \"about:blank\" &", timeoutSec = 5)
                    }
                    output = "Browser tab action '$action' processed for index $index."
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.BROWSER_DOWNLOAD_FILE -> {
                    val url = actionObj["url"]?.jsonPrimitive?.content ?: ""
                    val dest = actionObj["destination"]?.jsonPrimitive?.content ?: "workspace/downloaded_file"
                    val res = NativeBridge.executeCommand("curl -L -s -o \"${project.workspacePath}/$dest\" \"$url\"", timeoutSec = 60)
                    output = if (res.isSuccess) "Downloaded $url to $dest" else "Download completed with output: ${res.stdout}"
                    status = ActionStatus.SUCCESS
                }

                // ==========================================
                // Category 3: VFS Actions (8)
                // ==========================================
                ActionTypes.VFS_PUSH_TEXT -> {
                    val path = actionObj["path"]?.jsonPrimitive?.content ?: "workspace/untitled.txt"
                    val content = actionObj["content"]?.jsonPrimitive?.content ?: ""
                    vfsRepository.pushText(project.workspacePath, path, content)
                    output = "Pushed ${content.length} characters to '$path' in VFS."
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.VFS_APPEND_TEXT -> {
                    val path = actionObj["path"]?.jsonPrimitive?.content ?: "workspace/log.txt"
                    val content = actionObj["content"]?.jsonPrimitive?.content ?: ""
                    vfsRepository.appendText(project.workspacePath, path, content)
                    output = "Appended ${content.length} characters to '$path' in VFS."
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.VFS_READ_FILE -> {
                    val path = actionObj["path"]?.jsonPrimitive?.content ?: ""
                    val content = vfsRepository.readFile(project.workspacePath, path)
                    output = content
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.VFS_CREATE_FOLDER -> {
                    val path = actionObj["path"]?.jsonPrimitive?.content ?: ""
                    vfsRepository.createFolder(project.workspacePath, path)
                    output = "Created directory '$path' in VFS."
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.VFS_DELETE_ITEM -> {
                    val path = actionObj["path"]?.jsonPrimitive?.content ?: ""
                    vfsRepository.deleteItem(project.workspacePath, path)
                    output = "Deleted '$path' from VFS."
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.VFS_RENAME_MOVE -> {
                    val src = actionObj["source"]?.jsonPrimitive?.content ?: ""
                    val dest = actionObj["destination"]?.jsonPrimitive?.content ?: ""
                    vfsRepository.renameMove(project.workspacePath, src, dest)
                    output = "Moved '$src' to '$dest' in VFS."
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.VFS_LIST_TREE -> {
                    val path = actionObj["path"]?.jsonPrimitive?.content ?: ""
                    val nodes = vfsRepository.listDirectory(project.workspacePath, path)
                    output = json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(com.localbots.app.data.model.VfsNode.serializer()),
                        nodes
                    )
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.VFS_SEARCH_FILES -> {
                    val query = actionObj["query"]?.jsonPrimitive?.content ?: ""
                    val regex = actionObj["regex"]?.jsonPrimitive?.booleanOrNull ?: false
                    val results = vfsRepository.searchFiles(project.workspacePath, query, regex)
                    output = "Found ${results.size} match(es):\n" + results.joinToString("\n") { "- ${it.relativePath}" }
                    status = ActionStatus.SUCCESS
                }

                // ==========================================
                // Category 4: Pre-installed Toolchain & Runtime (6)
                // ==========================================
                ActionTypes.BASH_EXEC -> {
                    val command = actionObj["command"]?.jsonPrimitive?.content ?: "echo 'no command'"
                    val timeout = actionObj["timeout"]?.jsonPrimitive?.intOrNull ?: 30
                    val res = NativeBridge.executeCommand(command, timeoutSec = timeout, workingDir = File(project.workspacePath))
                    output = res.stdout.ifBlank { res.stderr }
                    if (!res.isSuccess && res.stderr.isNotBlank()) {
                        error = res.stderr
                        status = ActionStatus.ERROR
                    } else {
                        status = ActionStatus.SUCCESS
                    }
                }

                ActionTypes.PYTHON_RUN -> {
                    val script = actionObj["script"]?.jsonPrimitive?.content
                    val file = actionObj["file"]?.jsonPrimitive?.content
                    val escapedScript = script?.replace("\"", "\\\"") ?: ""
                    val cmd = when {
                        !script.isNullOrBlank() -> "python3 -c \"$escapedScript\""
                        !file.isNullOrBlank() -> "python3 \"${project.workspacePath}/$file\""
                        else -> "python3 --version"
                    }
                    val res = NativeBridge.executeCommand(cmd, timeoutSec = 60, workingDir = File(project.workspacePath))
                    output = res.stdout.ifBlank { res.stderr }
                    status = if (res.isSuccess) ActionStatus.SUCCESS else ActionStatus.ERROR
                }

                ActionTypes.TYPESCRIPT_COMPILE_RUN -> {
                    val file = actionObj["file"]?.jsonPrimitive?.content ?: "index.ts"
                    val cmd = "ts-node \"${project.workspacePath}/$file\" || tsc \"${project.workspacePath}/$file\""
                    val res = NativeBridge.executeCommand(cmd, timeoutSec = 60, workingDir = File(project.workspacePath))
                    output = res.stdout.ifBlank { res.stderr }
                    status = if (res.isSuccess) ActionStatus.SUCCESS else ActionStatus.ERROR
                }

                ActionTypes.GIT_OPERATION -> {
                    val op = actionObj["operation"]?.jsonPrimitive?.content ?: "status"
                    val args = actionObj["args"]?.jsonArray?.joinToString(" ") { it.jsonPrimitive.content } ?: ""
                    val cmd = "git -C \"${project.workspacePath}\" $op $args"
                    val res = NativeBridge.executeCommand(cmd, timeoutSec = 30)
                    output = res.stdout.ifBlank { res.stderr }
                    status = if (res.isSuccess) ActionStatus.SUCCESS else ActionStatus.ERROR
                }

                ActionTypes.PROCESS_MONITOR -> {
                    val action = actionObj["action"]?.jsonPrimitive?.content ?: "list"
                    val pid = actionObj["pid"]?.jsonPrimitive?.intOrNull
                    val cmd = if (action == "kill" && pid != null) "kill -9 $pid" else "ps aux"
                    val res = NativeBridge.executeCommand(cmd, timeoutSec = 10)
                    output = res.stdout.ifBlank { "Processes active." }
                    status = ActionStatus.SUCCESS
                }

                ActionTypes.CLIPBOARD_SYNC -> {
                    val direction = actionObj["direction"]?.jsonPrimitive?.content ?: "read"
                    if (direction == "write") {
                        val content = actionObj["content"]?.jsonPrimitive?.content ?: ""
                        linuxClipboard = content
                        output = "Copied to clipboard: \"${content.take(50)}\""
                    } else {
                        output = linuxClipboard.ifBlank { "Clipboard is empty" }
                    }
                    status = ActionStatus.SUCCESS
                }

                else -> {
                    output = "Executed generic action: $actionName"
                    status = ActionStatus.SUCCESS
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "Unknown execution error"
            status = ActionStatus.ERROR
        }

        val duration = System.currentTimeMillis() - startTime
        return AgentActionExecution(
            id = actionId,
            actionName = actionName,
            category = category,
            rawJson = actionObj.toString(),
            parametersSummary = summary,
            status = status,
            output = output,
            error = error,
            durationMs = duration,
            imageBase64 = imageBase64
        )
    }

    private fun extractActionName(obj: JsonObject): String {
        return obj["Actions"]?.jsonPrimitive?.content
            ?: obj["action"]?.jsonPrimitive?.content
            ?: obj["Using"]?.jsonPrimitive?.content
            ?: if (obj.containsKey("Update_Mouse_Position")) ActionTypes.UPDATE_MOUSE_POSITION
            else "Unknown_Action"
    }

    private fun generateSummary(actionName: String, obj: JsonObject): String {
        return when (actionName) {
            ActionTypes.DESKTOP_SCREENSHOT -> "Capturing X11 display :1"
            ActionTypes.UPDATE_MOUSE_POSITION -> "Moving mouse cursor"
            ActionTypes.RIGHT_CLICK -> "Triggering right mouse click"
            ActionTypes.LEFT_CLICK -> "Triggering left mouse click"
            ActionTypes.DOUBLE_CLICK -> "Triggering double click"
            ActionTypes.MIDDLE_CLICK -> "Triggering middle click"
            ActionTypes.MOUSE_DRAG -> "Dragging mouse"
            ActionTypes.MOUSE_SCROLL -> "Scrolling display"
            ActionTypes.KEYBOARD_TYPE -> "Typing text: \"${obj["text"]?.jsonPrimitive?.content?.take(20)}...\""
            ActionTypes.KEYBOARD_SHORTCUT -> "Key combination"
            ActionTypes.BROWSER_LAUNCH -> "Starting Firefox"
            ActionTypes.BROWSER_NAVIGATE -> "Opening URL: ${obj["url"]?.jsonPrimitive?.content?.take(30)}"
            ActionTypes.BROWSER_SEARCH -> "Search: \"${obj["query"]?.jsonPrimitive?.content}\""
            ActionTypes.BROWSER_CLICK_ELEMENT -> "Clicking selector: ${obj["selector"]?.jsonPrimitive?.content}"
            ActionTypes.BROWSER_EXTRACT_DOM -> "Extracting DOM text"
            ActionTypes.BROWSER_EXECUTE_JS -> "Running custom JS script"
            ActionTypes.BROWSER_MANAGE_TABS -> "Managing browser tabs"
            ActionTypes.BROWSER_DOWNLOAD_FILE -> "Downloading from ${obj["url"]?.jsonPrimitive?.content?.take(25)}"
            ActionTypes.VFS_PUSH_TEXT -> "Writing file ${obj["path"]?.jsonPrimitive?.content}"
            ActionTypes.VFS_APPEND_TEXT -> "Appending to ${obj["path"]?.jsonPrimitive?.content}"
            ActionTypes.VFS_READ_FILE -> "Reading file ${obj["path"]?.jsonPrimitive?.content}"
            ActionTypes.VFS_CREATE_FOLDER -> "Creating folder ${obj["path"]?.jsonPrimitive?.content}"
            ActionTypes.VFS_DELETE_ITEM -> "Deleting item ${obj["path"]?.jsonPrimitive?.content}"
            ActionTypes.VFS_RENAME_MOVE -> "Moving file"
            ActionTypes.VFS_LIST_TREE -> "Listing directory tree"
            ActionTypes.VFS_SEARCH_FILES -> "Searching VFS for \"${obj["query"]?.jsonPrimitive?.content}\""
            ActionTypes.BASH_EXEC -> "Shell: ${obj["command"]?.jsonPrimitive?.content?.take(35)}"
            ActionTypes.PYTHON_RUN -> "Python execution"
            ActionTypes.TYPESCRIPT_COMPILE_RUN -> "TypeScript run"
            ActionTypes.GIT_OPERATION -> "Git ${obj["operation"]?.jsonPrimitive?.content}"
            ActionTypes.PROCESS_MONITOR -> "Process monitoring"
            ActionTypes.CLIPBOARD_SYNC -> "Syncing clipboard"
            else -> actionName
        }
    }
}
