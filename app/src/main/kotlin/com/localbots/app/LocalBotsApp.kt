package com.localbots.app

import android.app.Application
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

    lateinit var projectRepository: ProjectRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var vfsRepository: VfsRepository
        private set
    lateinit var secureKeyVault: SecureKeyVault
        private set
    lateinit var cliWrapper: AntigravityCliWrapper
        private set
    lateinit var actionDispatcher: ActionDispatcher
        private set
    lateinit var agentOrchestrator: AgentOrchestrator
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Initialize Theme Engine & Local Time Ticker
        ThemeManager.initialize(this)

        // 2. Initialize Repositories & Key Vault
        projectRepository = ProjectRepository(this)
        settingsRepository = SettingsRepository(this)
        vfsRepository = VfsRepository()
        secureKeyVault = SecureKeyVault(this)

        // 3. Initialize Agent Engine & Native Bridge
        cliWrapper = AntigravityCliWrapper(this, settingsRepository)
        actionDispatcher = ActionDispatcher(this, vfsRepository)
        agentOrchestrator = AgentOrchestrator(
            context = this,
            projectRepository = projectRepository,
            settingsRepository = settingsRepository,
            vfsRepository = vfsRepository,
            secureKeyVault = secureKeyVault,
            actionDispatcher = actionDispatcher
        )

        // 4. Initialize Native Proot Environment paths
        val rootfsDir = File(filesDir, "rootfs")
        val vfsDir = File(filesDir, "vfs")
        NativeBridge.initEnvironment(rootfsDir.absolutePath, vfsDir.absolutePath)
    }

    companion object {
        lateinit var instance: LocalBotsApp
            private set
    }
}
