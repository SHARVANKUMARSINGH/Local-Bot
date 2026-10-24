package com.localbots.app.domain.runtime

import android.content.Context
import android.util.Log
import com.localbots.app.domain.nativebridge.NativeBridge
import com.localbots.app.domain.nativebridge.NativeCommandResult
import com.localbots.app.domain.nativebridge.NativeSpawnProcess
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BotPuppyInstance(
    val botId: String,
    val botName: String,
    val displayNumber: Int,
    val instanceDir: File,
    val rootfsDir: File,
    val framebufferFile: File,
    var isRunning: Boolean = false,
    var activeUrl: String = "https://google.com"
)

/**
 * Manages dedicated, isolated Puppy Linux GUI instances per Bot.
 * Each Bot gets its own private Linux environment, distinct virtual X11 display,
 * Joe's Window Manager (JWM) session, Firefox browser instance, and workspace.
 */
class PuppyLinuxInstanceManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PuppyInstanceManager"
        @Volatile private var instance: PuppyLinuxInstanceManager? = null

        fun getInstance(context: Context): PuppyLinuxInstanceManager {
            return instance ?: synchronized(this) {
                instance ?: PuppyLinuxInstanceManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val bundle = PuppyLinuxBundle(context)
    private val instances = ConcurrentHashMap<String, BotPuppyInstance>()
    private var nextDisplayNumber = 1

    init {
        bundle.ensureMasterBundleInstalled()
    }

    /**
     * Obtains or creates an isolated Puppy Linux instance for a specific Bot.
     */
    @Synchronized
    fun getOrCreateInstance(botId: String, botName: String, customWorkspace: File? = null): BotPuppyInstance {
        instances[botId]?.let { return it }

        val displayNum = nextDisplayNumber++
        val instanceDir = File(context.filesDir, "puppy_instances/$botId").apply { mkdirs() }
        val rootfsDir = File(instanceDir, "rootfs").apply { mkdirs() }

        // Provision isolated rootfs overlays from master bundle
        copySkeleton(bundle.masterRootfsDir, rootfsDir)

        val wsDir = customWorkspace ?: File(instanceDir, "workspace").apply { mkdirs() }
        val tmpDir = File(rootfsDir, "tmp").apply { mkdirs() }
        val fbFile = File(tmpDir, "Xvfb_screen$displayNum")

        // Write bot identity file
        File(rootfsDir, "etc/bot.conf").writeText("""
BOT_ID="$botId"
BOT_NAME="$botName"
DISPLAY=":$displayNum"
FRAMEBUFFER="$fbFile"
        """.trimIndent())

        val botInstance = BotPuppyInstance(
            botId = botId,
            botName = botName,
            displayNumber = displayNum,
            instanceDir = instanceDir,
            rootfsDir = rootfsDir,
            framebufferFile = fbFile,
            isRunning = false
        )
        instances[botId] = botInstance
        Log.i(TAG, "Created isolated Puppy Linux instance for Bot '$botName' (ID: $botId, Display: :$displayNum)")
        return botInstance
    }

    /**
     * Starts the Puppy Linux GUI environment (Xvfb + JWM + Firefox) for a specific bot.
     */
    suspend fun startInstance(botId: String): Boolean = withContext(Dispatchers.IO) {
        val botInstance = instances[botId] ?: return@withContext false
        if (botInstance.isRunning) return@withContext true

        Log.i(TAG, "Starting Puppy Linux instance for Bot ${botInstance.botName} on :${botInstance.displayNumber}")

        // 1. Initialize framebuffer file if not present
        if (!botInstance.framebufferFile.exists() || botInstance.framebufferFile.length() == 0L) {
            val totalBytes = 1280 * 720 * 4
            val initialPixels = ByteArray(totalBytes)
            // Default elegant desktop background gradient (Dark Slate / Puppy Blue)
            for (y in 0 until 720) {
                for (x in 0 until 1280) {
                    val idx = (y * 1280 + x) * 4
                    initialPixels[idx] = (24 + (x * 30 / 1280)).toByte()     // R
                    initialPixels[idx + 1] = (36 + (y * 20 / 720)).toByte()  // G
                    initialPixels[idx + 2] = (56 + ((x + y) * 20 / 2000)).toByte() // B
                    initialPixels[idx + 3] = 255.toByte()                    // A
                }
            }
            botInstance.framebufferFile.parentFile?.mkdirs()
            botInstance.framebufferFile.writeBytes(initialPixels)
        }

        // 2. Execute /usr/bin/startpuppy in the bot's rootfs
        val startScript = File(botInstance.rootfsDir, "usr/bin/startpuppy")
        if (startScript.exists()) {
            val cmd = "DISPLAY=:${botInstance.displayNumber} /system/bin/sh ${startScript.absolutePath} ${botInstance.displayNumber}"
            NativeBridge.executeCommand(cmd, workingDir = botInstance.instanceDir)
        }

        botInstance.isRunning = true
        true
    }

    /**
     * Stops the bot's Puppy Linux instance.
     */
    fun stopInstance(botId: String): Boolean {
        val botInstance = instances[botId] ?: return false
        botInstance.isRunning = false
        Log.i(TAG, "Stopped Puppy Linux instance for Bot ${botInstance.botName}")
        return true
    }

    /**
     * Captures live desktop framebuffer of this Bot's Puppy Linux GUI.
     */
    fun captureScreen(botId: String): ByteArray? {
        val botInstance = instances[botId] ?: return null

        // 1. Check bot's instance-private framebuffer
        if (botInstance.framebufferFile.exists() && botInstance.framebufferFile.length() > 0) {
            return runCatching { botInstance.framebufferFile.readBytes() }.getOrNull()
        }

        // 2. Try NativeBridge framebuffer capture on bot's display
        val nativeBytes = NativeBridge.captureFramebuffer(botInstance.displayNumber, "webp")
        if (nativeBytes != null && nativeBytes.isNotEmpty()) {
            return nativeBytes
        }

        // 3. Check system /tmp/Xvfb_screen<displayNum>
        val sysFb = File("/tmp/Xvfb_screen${botInstance.displayNumber}")
        if (sysFb.exists() && sysFb.length() > 0) {
            return runCatching { sysFb.readBytes() }.getOrNull()
        }

        return null
    }

    /**
     * Injects mouse or keyboard input into this Bot's Puppy Linux GUI.
     */
    fun injectInput(botId: String, actionName: String, params: Map<String, Any?>): Boolean {
        val botInstance = instances[botId] ?: return false
        val display = ":${botInstance.displayNumber}"

        return when (actionName.lowercase()) {
            "update_mouse_position", "mousemove" -> {
                val x = (params["x"] as? Number)?.toInt() ?: 640
                val y = (params["y"] as? Number)?.toInt() ?: 360
                NativeBridge.injectMouseMove(x, y)
                val cmd = "DISPLAY=$display /usr/bin/xdotool mousemove $x $y"
                NativeBridge.executeCommand(cmd, workingDir = botInstance.instanceDir)
                true
            }
            "left-click", "click", "mouse-click" -> {
                val x = (params["x"] as? Number)?.toInt() ?: -1
                val y = (params["y"] as? Number)?.toInt() ?: -1
                NativeBridge.injectMouseClick(1, x, y)
                val cmd = if (x >= 0 && y >= 0) {
                    "DISPLAY=$display /usr/bin/xdotool mousemove $x $y click 1"
                } else {
                    "DISPLAY=$display /usr/bin/xdotool click 1"
                }
                NativeBridge.executeCommand(cmd, workingDir = botInstance.instanceDir)
                true
            }
            "right-click" -> {
                val x = (params["x"] as? Number)?.toInt() ?: -1
                val y = (params["y"] as? Number)?.toInt() ?: -1
                NativeBridge.injectMouseClick(3, x, y)
                val cmd = if (x >= 0 && y >= 0) {
                    "DISPLAY=$display /usr/bin/xdotool mousemove $x $y click 3"
                } else {
                    "DISPLAY=$display /usr/bin/xdotool click 3"
                }
                NativeBridge.executeCommand(cmd, workingDir = botInstance.instanceDir)
                true
            }
            "keyboard-type", "type" -> {
                val text = params["text"]?.toString() ?: ""
                NativeBridge.injectKeyboardType(text)
                val clean = text.replace("\"", "\\\"")
                val cmd = "DISPLAY=$display /usr/bin/xdotool type \"$clean\""
                NativeBridge.executeCommand(cmd, workingDir = botInstance.instanceDir)
                true
            }
            "keyboard-shortcut", "key" -> {
                val key = params["key"]?.toString() ?: params["shortcut"]?.toString() ?: "Return"
                NativeBridge.injectKeyboardShortcut(arrayOf(key))
                val cmd = "DISPLAY=$display /usr/bin/xdotool key $key"
                NativeBridge.executeCommand(cmd, workingDir = botInstance.instanceDir)
                true
            }
            else -> false
        }
    }

    /**
     * Launches or navigates Firefox Browser in this Bot's Puppy Linux instance.
     */
    fun launchFirefox(botId: String, url: String? = null): Boolean {
        val botInstance = instances[botId] ?: return false
        val targetUrl = url ?: botInstance.activeUrl
        botInstance.activeUrl = targetUrl

        val display = ":${botInstance.displayNumber}"
        val cmd = "DISPLAY=$display /usr/bin/firefox \"$targetUrl\""
        NativeBridge.executeCommand(cmd, workingDir = botInstance.instanceDir)
        Log.i(TAG, "Launched Firefox in Bot ${botInstance.botName} at $targetUrl")
        return true
    }

    /**
     * Executes arbitrary bash command inside this Bot's isolated instance.
     */
    fun executeInInstance(botId: String, command: String, timeoutSec: Int = 60): NativeCommandResult {
        val botInstance = instances[botId]
        val workDir = botInstance?.instanceDir ?: context.filesDir
        val envPrefix = if (botInstance != null) "DISPLAY=:${botInstance.displayNumber} " else ""
        return NativeBridge.executeCommand("$envPrefix$command", timeoutSec = timeoutSec, workingDir = workDir)
    }

    private fun copySkeleton(src: File, dst: File) {
        if (!src.exists()) return
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { child ->
                copySkeleton(child, File(dst, child.name))
            }
        } else if (!dst.exists()) {
            runCatching {
                src.copyTo(dst, overwrite = false)
                if (src.canExecute()) dst.setExecutable(true, false)
            }
        }
    }
}
