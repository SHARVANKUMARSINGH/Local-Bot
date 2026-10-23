package com.localbots.app

import android.app.Application
import android.util.Log
import com.localbots.app.data.repository.ProjectRepository
import com.localbots.app.data.repository.SettingsRepository
import com.localbots.app.data.repository.VfsRepository
import com.localbots.app.data.vault.SecureKeyVault
import com.localbots.app.domain.agent.ActionDispatcher
import com.localbots.app.domain.agent.AgentOrchestrator
import com.localbots.app.domain.agent.AntigravityCliWrapper
import com.localbots.app.domain.nativebridge.NativeBridge
import com.localbots.app.theme.ThemeManager
import java.io.File

class LocalBotsApp : Application() {

    val projectRepository: ProjectRepository by lazy { ProjectRepository(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val vfsRepository: VfsRepository by lazy { VfsRepository() }
    val secureKeyVault: SecureKeyVault by lazy { SecureKeyVault(this) }
    val cliWrapper: AntigravityCliWrapper by lazy { AntigravityCliWrapper(this, settingsRepository) }
    val actionDispatcher: ActionDispatcher by lazy { ActionDispatcher(this, vfsRepository) }
    val agentOrchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(
            context = this,
            projectRepository = projectRepository,
            settingsRepository = settingsRepository,
            vfsRepository = vfsRepository,
            secureKeyVault = secureKeyVault,
            actionDispatcher = actionDispatcher
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Install defensive global uncaught exception logger
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("LocalBotsApp", "FATAL EXCEPTION in thread: ${thread.name}", throwable)
            try {
                val crashFile = File(filesDir, "crash.log")
                crashFile.writeText(
                    "Timestamp: ${System.currentTimeMillis()}\nThread: ${thread.name}\n${throwable.stackTraceToString()}\n"
                )
            } catch (ignored: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            // 1. Initialize Theme Engine & Local Time Ticker
            ThemeManager.initialize(this)
        } catch (t: Throwable) {
            Log.e("LocalBotsApp", "ThemeManager init error", t)
        }

        try {
            // 2. Pre-warm key repositories safely
            settingsRepository
            projectRepository
            vfsRepository
            secureKeyVault
        } catch (t: Throwable) {
            Log.e("LocalBotsApp", "Repositories pre-warm notice", t)
        }

        try {
            // 3. Initialize Native Proot Environment paths
            val rootfsDir = File(filesDir, "rootfs")
            val vfsDir = File(filesDir, "vfs")
            rootfsDir.mkdirs()
            vfsDir.mkdirs()
            NativeBridge.initEnvironment(rootfsDir.absolutePath, vfsDir.absolutePath)
        } catch (t: Throwable) {
            Log.e("LocalBotsApp", "NativeBridge init notice", t)
        }
    }

    companion object {
        @Volatile
        var instance: LocalBotsApp? = null
            private set

        fun getInstance(): LocalBotsApp? = instance
    }
}
