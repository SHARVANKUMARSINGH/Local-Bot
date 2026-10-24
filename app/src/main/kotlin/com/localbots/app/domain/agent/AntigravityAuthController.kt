package com.localbots.app.domain.agent

import android.content.Context
import com.localbots.app.domain.nativebridge.NativeSpawnProcess
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class AntigravityAuthStatus {
    SIGNED_OUT,
    STARTING,
    AWAITING_CODE,
    COMPLETING,
    SIGNED_IN,
    ERROR
}

data class AntigravityAuthState(
    val status: AntigravityAuthStatus = AntigravityAuthStatus.SIGNED_OUT,
    val authorizationUrl: String? = null,
    val message: String? = null,
    val accountEmail: String? = null
)

/**
 * Real Antigravity CLI Authentication Controller ported from Mobile-Harness.
 * Drives agy's official interactive terminal OAuth flow over a native PTY,
 * replies to DEC terminal handshakes, extracts the official Google OAuth sign-in URL,
 * submits the one-time approval code, automatically confirms terminal setup steps,
 * and tracks the resulting credential token at root/.gemini/antigravity-cli/antigravity-oauth-token.
 */
class AntigravityAuthController(
    private val context: Context,
    initiallySignedIn: Boolean = false,
    initialAccountEmail: String? = null,
    private val onSignedInChanged: ((Boolean, String?) -> Unit)? = null
) {
    private val mutableState = MutableStateFlow(
        AntigravityAuthState(
            status = if (initiallySignedIn) AntigravityAuthStatus.SIGNED_IN else AntigravityAuthStatus.SIGNED_OUT,
            message = initialAccountEmail?.takeIf { it.isNotBlank() }?.let { "Connected as $it" },
            accountEmail = initialAccountEmail?.takeIf { it.isNotBlank() }
        )
    )
    val state: StateFlow<AntigravityAuthState> = mutableState.asStateFlow()

    private val authOutput = File(context.cacheDir, "antigravity-auth-output.log")
    private val logoutOutput = File(context.cacheDir, "antigravity-logout-output.log")
    @Volatile private var process: Process? = null
    @Volatile private var codeSubmitted = false

    init {
        authOutput.delete()
        logoutOutput.delete()
    }

    fun officialCredentialFile(): File {
        val rootfsDir = File(context.filesDir, "runtime/rootfs")
        val altFile = File(context.filesDir, ".gemini/antigravity-cli/antigravity-oauth-token")
        val rootfsFile = File(rootfsDir, "root/.gemini/antigravity-cli/antigravity-oauth-token")
        return if (rootfsFile.exists()) rootfsFile else altFile
    }

    fun hasOfficialCredential(): Boolean = officialCredentialFile().isFile && officialCredentialFile().length() > 0

    fun getAgyBinary(): File {
        val inFiles = File(context.filesDir, "bin/agy")
        if (inFiles.exists()) return inFiles
        val inRootfs = File(context.filesDir, "runtime/rootfs/root/.local/bin/agy")
        if (inRootfs.exists()) return inRootfs
        val hostAgy = File("/root/.local/bin/agy")
        if (hostAgy.exists()) return hostAgy
        return inFiles
    }

    suspend fun beginLogin() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) return@withContext
        val agyBin = getAgyBinary()
        if (!agyBin.exists()) {
            mutableState.value = AntigravityAuthState(
                AntigravityAuthStatus.ERROR,
                message = "Antigravity CLI (agy) binary not installed. Please initialize runtime first."
            )
            return@withContext
        }

        mutableState.value = AntigravityAuthState(AntigravityAuthStatus.STARTING, message = "Starting Google sign-in...")
        codeSubmitted = false
        authOutput.delete()

        val workspace = File(context.filesDir, "workspaces/antigravity-auth").apply { mkdirs() }
        val ptyEnv = mapOf(
            "SSH_CONNECTION" to "127.0.0.1 1 127.0.0.1 1",
            "TERM" to "xterm-256color",
            "NO_COLOR" to "1",
            "HOME" to "/root",
            "LANG" to "C.UTF-8"
        )

        val running = NativeSpawnProcess.start(
            argv = listOf(agyBin.absolutePath),
            environment = ptyEnv,
            cwd = workspace.absolutePath,
            outputFile = authOutput,
            pseudoTerminal = true,
            ptyRows = 40,
            ptyColumns = 120
        )
        process = running

        var offset = 0L
        val output = StringBuilder()
        var handshakeReplies = 0
        var lastHandshakeReplyLength = 0
        var loginMenuAdvanced = false
        var colorScreenCompleted = false
        var renderingScreenCompleted = false
        var privacyScreenCompleted = false
        var workspaceTrustCompleted = false

        try {
            while (running.isAlive || running.outputFile.length() > offset) {
                val available = running.outputFile.length() - offset
                if (available <= 0) {
                    delay(80)
                    continue
                }
                val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                val count = RandomAccessFile(running.outputFile, "r").use { file ->
                    file.seek(offset)
                    file.read(bytes)
                }
                if (count <= 0) continue
                offset += count
                output.append(bytes.decodeToString(0, count))
                val clean = sanitizeTerminalOutput(output.toString()).takeLast(40_000)

                // Antigravity's renderer asks for DEC mode and Kitty keyboard protocol status.
                // Reply to the standard query \u001B[?u
                if (handshakeReplies < 5 &&
                    output.length - lastHandshakeReplyLength > 200 &&
                    output.substring(lastHandshakeReplyLength).contains("\u001B[?u")
                ) {
                    running.outputStream.write(TERMINAL_HANDSHAKE_REPLY.toByteArray())
                    running.outputStream.flush()
                    handshakeReplies++
                    lastHandshakeReplyLength = output.length
                }

                // agy TUI menu: Select login method > 1. Google OAuth
                if (handshakeReplies > 0 &&
                    !loginMenuAdvanced &&
                    clean.contains("Select login method", true) &&
                    clean.contains("> 1. Google OAuth", true) &&
                    extractGoogleOAuthUrl(clean) == null
                ) {
                    running.outputStream.write("\r\n".toByteArray())
                    running.outputStream.flush()
                    loginMenuAdvanced = true
                    if (mutableState.value.status == AntigravityAuthStatus.STARTING) {
                        mutableState.value = mutableState.value.copy(
                            message = "Google OAuth selected — waiting for the browser sign-in URL..."
                        )
                    }
                }

                // Extract official browser URL with PKCE
                val url = extractGoogleOAuthUrl(clean)
                if (url != null && mutableState.value.authorizationUrl == null) {
                    mutableState.value = AntigravityAuthState(
                        AntigravityAuthStatus.AWAITING_CODE,
                        authorizationUrl = url,
                        message = "Finish signing in with Google, then paste the one-time code."
                    )
                }

                // Detect signed-in state
                if (isSignedInScreen(clean)) {
                    val email = extractSignedInEmail(clean) ?: "user@google.com"
                    onSignedInChanged?.invoke(true, email)
                    mutableState.value = AntigravityAuthState(
                        AntigravityAuthStatus.SIGNED_IN,
                        message = "Connected as $email",
                        accountEmail = email
                    )
                    runCatching {
                        running.outputStream.write("/quit\r\n".toByteArray())
                        running.outputStream.flush()
                    }
                    repeat(20) {
                        if (!running.isAlive) return@repeat
                        delay(50)
                    }
                    if (running.isAlive) running.destroy()
                    break
                }

                // Screen confirmations:
                if (!colorScreenCompleted && clean.contains("Choose your color scheme", true)) {
                    running.outputStream.write("\r\n".toByteArray())
                    running.outputStream.flush()
                    colorScreenCompleted = true
                }
                if (!renderingScreenCompleted &&
                    clean.contains("no flickering (altscreen)", true) &&
                    clean.contains("Native Terminal experience (inline)", true)
                ) {
                    // Down arrow + Enter for inline
                    running.outputStream.write("\u001B[B\r\n".toByteArray())
                    running.outputStream.flush()
                    renderingScreenCompleted = true
                }
                if (!privacyScreenCompleted &&
                    clean.contains("Terms of Service & Data Use", true) &&
                    clean.contains("help improve Antigravity CLI", true)
                ) {
                    val optOutAndFinish = if (clean.contains("[x] Yes", true)) " \t\t\r\n" else "\t\t\r\n"
                    running.outputStream.write(optOutAndFinish.toByteArray())
                    running.outputStream.flush()
                    privacyScreenCompleted = true
                    mutableState.value = mutableState.value.copy(
                        message = "Google connected — finishing private Antigravity setup..."
                    )
                }
                if (!workspaceTrustCompleted &&
                    clean.contains("Do you trust the contents of this project", true) &&
                    clean.contains("Yes, I trust this folder", true)
                ) {
                    running.outputStream.write("\r\n".toByteArray())
                    running.outputStream.flush()
                    workspaceTrustCompleted = true
                }
                if (clean.contains("authentication failed", true) || clean.contains("failed to exchange", true)) {
                    throw IllegalStateException("Google authentication failed. Start a new sign-in attempt.")
                }
            }

            if (!codeSubmitted && mutableState.value.status == AntigravityAuthStatus.STARTING) {
                val exit = running.waitFor()
                throw IllegalStateException("Antigravity login closed before producing an authorization URL (exit $exit).")
            }
        } catch (error: Throwable) {
            if (mutableState.value.status != AntigravityAuthStatus.SIGNED_IN) {
                mutableState.value = AntigravityAuthState(
                    AntigravityAuthStatus.ERROR,
                    message = error.message?.take(240) ?: "Antigravity sign-in failed"
                )
            }
        } finally {
            if (running.isAlive) running.destroy()
            runCatching { running.outputStream.close() }
            running.outputFile.delete()
            process = null
            codeSubmitted = false
        }
    }

    fun submitCode(code: String) {
        val value = code.trim()
        require(value.isNotBlank()) { "Paste the authorization code from Google" }
        val running = process ?: error("Start Google sign-in again")
        check(running.isAlive) { "The sign-in session expired. Start again." }
        running.outputStream.write((value + "\r\n").toByteArray())
        running.outputStream.flush()
        codeSubmitted = true
        mutableState.value = mutableState.value.copy(
            status = AntigravityAuthStatus.COMPLETING,
            message = "Completing Google sign-in..."
        )
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        process?.destroy()
        val previousEmail = mutableState.value.accountEmail
        mutableState.value = AntigravityAuthState(
            status = AntigravityAuthStatus.STARTING,
            message = "Signing out of Antigravity...",
            accountEmail = previousEmail
        )
        try {
            val credential = officialCredentialFile()
            if (credential.exists()) {
                credential.delete()
            }
            onSignedInChanged?.invoke(false, null)
            mutableState.value = AntigravityAuthState(AntigravityAuthStatus.SIGNED_OUT, message = "Signed out")
        } catch (error: Throwable) {
            mutableState.value = AntigravityAuthState(
                status = AntigravityAuthStatus.SIGNED_IN,
                message = error.message?.take(240) ?: "Could not log out of Antigravity",
                accountEmail = previousEmail
            )
            throw error
        } finally {
            process = null
            logoutOutput.delete()
        }
    }

    fun cancel() {
        process?.destroy()
        mutableState.value = AntigravityAuthState(AntigravityAuthStatus.SIGNED_OUT)
    }
}

internal fun extractGoogleOAuthUrl(output: String): String? {
    val compact = output.replace(Regex("[\\r\\n\\t ]+"), "")
    GOOGLE_OAUTH_URL.find(compact)?.value
        ?.takeIf { "client_id=" in it && "code_challenge=" in it }
        ?.let { return it }

    if (!output.contains("Select login method", true) &&
        (output.contains("browser", true) || output.contains("visit", true) ||
         output.contains("open", true) || output.contains("code", true) ||
         output.contains("paste", true))
    ) {
        val candidates = Regex("https://[^\\s\"']{20,}")
            .findAll(compact)
            .map { it.value.trimEnd { char -> char !in URL_CHARACTERS } }
            .filter { it.length >= 30 && "." in it }
            .toList()
        return candidates.firstOrNull { "google" in it } ?: candidates.firstOrNull()
    }
    return null
}

private fun isSignedInScreen(output: String): Boolean =
    output.contains("for shortcuts", true) ||
    (output.contains("Antigravity CLI", true) && output.contains("Google AI", true))

private fun extractSignedInEmail(output: String): String? =
    Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        .findAll(output)
        .map { it.value }
        .firstOrNull { !it.endsWith(".apps.googleusercontent.com", ignoreCase = true) }

private val URL_CHARACTERS = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
    "-._~:/?#[]@!$&'()*+,;=%").toSet()

private val GOOGLE_OAUTH_URL = Regex(
    "https://accounts\\.google\\.com/[^\\s\\\"'<>]*?[?&]state=[A-Za-z0-9._~-]+",
)

private const val TERMINAL_HANDSHAKE_REPLY = "\u001B[?2026;1\$y\u001B[?2027;1\$y\u001B[?1u\n"

private val AUTH_ANSI = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")
private fun sanitizeTerminalOutput(text: String): String = text
    .replace(AUTH_ANSI, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }
