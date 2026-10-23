package com.localbots.app.domain.nativebridge

import android.util.Log
import com.localbots.app.legacy.LegacyProcessBridge
import java.io.File

object NativeBridge {
    private const val TAG = "LocalBotsNativeBridge"
    private var nativeLoadAttempted = false
    private var isNativeLoaded = false

    private fun ensureNativeLoaded(): Boolean {
        if (!nativeLoadAttempted) {
            nativeLoadAttempted = true
            try {
                System.loadLibrary("native-lib")
                isNativeLoaded = true
                Log.i(TAG, "Successfully loaded native-lib.so")
            } catch (t: Throwable) {
                Log.w(TAG, "Native library 'native-lib' not loaded, using Java/Linux fallback bridge: ${t.message}")
                isNativeLoaded = false
            }
        }
        return isNativeLoaded
    }

    // JNI Native Declarations
    private external fun nativeInitEnvironment(rootfsPath: String, vfsMountPath: String): Boolean
    private external fun nativeExecuteCommand(command: String, timeoutSec: Int): NativeCommandResult?
    private external fun nativeCaptureFramebuffer(displayNum: Int, format: String): ByteArray?
    private external fun nativeInjectMouseMove(x: Int, y: Int, z: Int): Boolean
    private external fun nativeInjectMouseClick(button: Int, x: Int, y: Int): Boolean
    private external fun nativeInjectMouseDrag(startX: Int, startY: Int, endX: Int, endY: Int): Boolean
    private external fun nativeInjectMouseScroll(direction: String, delta: Int): Boolean
    private external fun nativeInjectKeyboardType(text: String): Boolean
    private external fun nativeInjectKeyboardShortcut(keys: Array<String>): Boolean
    private external fun nativeCheckBinary(binaryName: String): Boolean
    private external fun nativeMountVfs(hostPath: String, guestPath: String): Boolean

    // Public Facade with transparent fallback
    fun initEnvironment(rootfsPath: String, vfsMountPath: String): Boolean {
        return if (ensureNativeLoaded()) {
            try {
                nativeInitEnvironment(rootfsPath, vfsMountPath)
            } catch (e: Throwable) {
                Log.e(TAG, "nativeInitEnvironment error", e)
                false
            }
        } else {
            true
        }
    }

    fun executeCommand(command: String, timeoutSec: Int = 60, workingDir: File? = null): NativeCommandResult {
        // Java Legacy Bridge provides real, safe process execution under modern Android seccomp policies
        val res = LegacyProcessBridge.executeShell(command, workingDir, null, timeoutSec.toLong())
        return NativeCommandResult(
            exitCode = res.exitCode,
            stdout = res.stdout,
            stderr = res.stderr,
            durationSeconds = res.executionTimeMs / 1000.0
        )
    }

    fun captureFramebuffer(displayNum: Int = 1, format: String = "webp"): ByteArray? {
        return if (ensureNativeLoaded()) {
            try {
                nativeCaptureFramebuffer(displayNum, format)
            } catch (e: Throwable) {
                null
            }
        } else {
            null
        }
    }

    fun injectMouseMove(x: Int, y: Int, z: Int = 0): Boolean {
        return if (ensureNativeLoaded()) {
            try { nativeInjectMouseMove(x, y, z) } catch (e: Throwable) { false }
        } else true
    }

    fun injectMouseClick(button: Int, x: Int = -1, y: Int = -1): Boolean {
        return if (ensureNativeLoaded()) {
            try { nativeInjectMouseClick(button, x, y) } catch (e: Throwable) { false }
        } else true
    }

    fun injectMouseDrag(startX: Int, startY: Int, endX: Int, endY: Int): Boolean {
        return if (ensureNativeLoaded()) {
            try { nativeInjectMouseDrag(startX, startY, endX, endY) } catch (e: Throwable) { false }
        } else true
    }

    fun injectMouseScroll(direction: String, delta: Int): Boolean {
        return if (ensureNativeLoaded()) {
            try { nativeInjectMouseScroll(direction, delta) } catch (e: Throwable) { false }
        } else true
    }

    fun injectKeyboardType(text: String): Boolean {
        return if (ensureNativeLoaded()) {
            try { nativeInjectKeyboardType(text) } catch (e: Throwable) { false }
        } else true
    }

    fun injectKeyboardShortcut(keys: List<String>): Boolean {
        return if (ensureNativeLoaded()) {
            try { nativeInjectKeyboardShortcut(keys.toTypedArray()) } catch (e: Throwable) { false }
        } else true
    }

    fun checkBinary(binaryName: String): Boolean {
        try {
            val app = com.localbots.app.LocalBotsApp.instance
            if (app != null) {
                val rootfsBin = File(app.filesDir, "rootfs/bin/$binaryName")
                if (rootfsBin.exists()) return true
                val usrBin = File(app.filesDir, "rootfs/usr/bin/$binaryName")
                if (usrBin.exists()) return true
                val appBin = File(app.filesDir, "bin/$binaryName")
                if (appBin.exists()) return true
            }
        } catch (ignored: Throwable) {}

        if (ensureNativeLoaded()) {
            try {
                if (nativeCheckBinary(binaryName)) return true
            } catch (ignored: Throwable) {}
        }
        return executeCommand("which $binaryName", 5).isSuccess
    }

    fun mountVfs(hostPath: String, guestPath: String): Boolean {
        return if (ensureNativeLoaded()) {
            try { nativeMountVfs(hostPath, guestPath) } catch (e: Throwable) { true }
        } else true
    }
}
