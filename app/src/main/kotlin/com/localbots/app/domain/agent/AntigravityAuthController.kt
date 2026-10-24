package com.localbots.app.domain.agent

import android.content.Context
import android.util.Base64
import android.util.Log
import com.localbots.app.domain.nativebridge.NativeSpawnProcess
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

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
 * Antigravity CLI Authentication Controller for Android.
 *
 * Implements the official Google OAuth 2.0 PKCE protocol for Gemini / Antigravity CLI,
 * with automatic localhost loopback callback capture and manual code entry fallback.
 * Also supports native PTY execution with DEC handshake when an official agy ELF binary
 * is present in a Linux guest environment, automatically falling back to direct PKCE
 * to ensure users never experience terminal exit 127.
 */
class AntigravityAuthController(
    private val context: Context,
    initiallySignedIn: Boolean = false,
    initialAccountEmail: String? = null,
    private val onSignedInChanged: ((Boolean, String?, String?) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AntigravityAuthController"
        const val OFFICIAL_CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
        const val GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val GOOGLE_USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v3/userinfo"
        const val OAUTH_SCOPES = "openid email profile https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/aicode"
        private const val TERMINAL_HANDSHAKE_REPLY = "\u001B[?2026;1\$y\u001B[?2027;1\$y\u001B[?1u\n"
    }

    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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

    @Volatile private var loopbackServer: ServerSocket? = null
    @Volatile private var activeCodeVerifier: String? = null
    @Volatile private var activeRedirectUri: String? = null
    @Volatile private var activeState: String? = null

    init {
        authOutput.delete()
        logoutOutput.delete()
    }

    fun officialCredentialFile(): File {
        val rootfsDir = File(context.filesDir, "runtime/rootfs")
        val altFile = File(context.filesDir, ".gemini/antigravity-cli/antigravity-oauth-token")
        val rootfsFile = File(rootfsDir, "root/.gemini/antigravity-cli/antigravity-oauth-token")
        return if (rootfsFile.parentFile?.exists() == true) rootfsFile else altFile
    }

    fun hasOfficialCredential(): Boolean {
        val appTokenFile = File(context.filesDir, ".gemini/antigravity-cli/antigravity-oauth-token")
        val rootfsTokenFile = File(context.filesDir, "runtime/rootfs/root/.gemini/antigravity-cli/antigravity-oauth-token")
        val hostToken = File("/root/.gemini/antigravity-cli/antigravity-oauth-token")
        return (appTokenFile.isFile && appTokenFile.length() > 0) ||
               (rootfsTokenFile.isFile && rootfsTokenFile.length() > 0) ||
               (hostToken.isFile && hostToken.length() > 0)
    }

    fun getAgyBinary(): File {
        val inFiles = File(context.filesDir, "bin/agy")
        if (inFiles.exists()) return inFiles
        val inRootfs = File(context.filesDir, "runtime/rootfs/root/.local/bin/agy")
        if (inRootfs.exists()) return inRootfs
        val hostAgy = File("/root/.local/bin/agy")
        if (hostAgy.exists()) return hostAgy
        return inFiles
    }

    private fun isRealAgyBinary(file: File): Boolean {
        if (!file.exists() || file.length() < 1_000_000L) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                read == 4 && header[0] == 0x7F.toByte() &&
                        header[1] == 'E'.code.toByte() &&
                        header[2] == 'L'.code.toByte() &&
                        header[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    suspend fun beginLogin() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true || mutableState.value.status == AntigravityAuthStatus.STARTING) return@withContext

        mutableState.value = AntigravityAuthState(
            AntigravityAuthStatus.STARTING,
            message = "Starting Google sign-in..."
        )
        codeSubmitted = false
        authOutput.delete()

        val agyBin = getAgyBinary()
        if (isRealAgyBinary(agyBin)) {
            val ptySuccess = runCatching { tryPtySpawnFlow(agyBin) }.getOrDefault(false)
            if (ptySuccess) return@withContext
            Log.i(TAG, "Native PTY spawn closed early; activating direct official Google OAuth 2.0 PKCE engine")
        }

        // Direct official Google OAuth 2.0 PKCE engine
        startDirectOAuthPkce()
    }

    private fun generateCodeVerifier(): String {
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        return Base64.encodeToString(randomBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateRandomState(): String {
        val randomBytes = ByteArray(16)
        SecureRandom().nextBytes(randomBytes)
        return Base64.encodeToString(randomBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun startLoopbackServer(): Int {
        stopLoopbackServer()
        val server = runCatching { ServerSocket(4000, 1, InetAddress.getByName("127.0.0.1")) }
            .getOrElse { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }
        loopbackServer = server
        val port = server.localPort

        authScope.launch {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    handleLoopbackConnection(socket)
                }
            } catch (_: Exception) {}
        }

        return port
    }

    private fun stopLoopbackServer() {
        runCatching { loopbackServer?.close() }
        loopbackServer = null
    }

    private fun handleLoopbackConnection(socket: Socket) {
        authScope.launch {
            try {
                socket.use { s ->
                    s.soTimeout = 5000
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val requestLine = reader.readLine() ?: return@launch
                    val uriString = requestLine.split(" ").getOrNull(1) ?: ""
                    val code = extractQueryParam(uriString, "code")
                    val error = extractQueryParam(uriString, "error")

                    val html = if (code != null) {
                        """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1.0"><title>Sign-In Successful</title></head><body style="font-family:-apple-system,BlinkMacSystemFont,Roboto,sans-serif;background:#0f172a;color:#f8fafc;text-align:center;padding:50px;">
                        <h2 style="color:#38bdf8;">Google Sign-In Successful!</h2>
                        <p>Your Antigravity CLI session is authorized.</p>
                        <p style="color:#94a3b8;">You may close this browser tab and return to Local Bots.</p>
                        </body></html>"""
                    } else {
                        """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1.0"><title>Sign-In Status</title></head><body style="font-family:-apple-system,BlinkMacSystemFont,Roboto,sans-serif;background:#0f172a;color:#f8fafc;text-align:center;padding:50px;">
                        <h2 style="color:#f43f5e;">Authorization Notice</h2>
                        <p>${error ?: "Please return to Local Bots to complete setup."}</p>
                        </body></html>"""
                    }

                    val responseBytes = html.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${responseBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    s.getOutputStream().write(header.toByteArray(Charsets.UTF_8))
                    s.getOutputStream().write(responseBytes)
                    s.getOutputStream().flush()

                    if (code != null) {
                        activeRedirectUri?.let { redirectUri ->
                            exchangeCodeForTokens(code, redirectUri)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun extractQueryParam(uriString: String, key: String): String? {
        val query = uriString.substringAfter("?", "")
        if (query.isEmpty()) return null
        return query.split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it.getOrNull(0) == key }
            ?.getOrNull(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    }

    private fun startDirectOAuthPkce() {
        val verifier = generateCodeVerifier()
        activeCodeVerifier = verifier
        val challenge = generateCodeChallenge(verifier)
        val state = generateRandomState()
        activeState = state

        val port = startLoopbackServer()
        val redirectUri = "http://localhost:$port"
        activeRedirectUri = redirectUri

        val encodedScope = java.net.URLEncoder.encode(OAUTH_SCOPES, "UTF-8")
        val authUrl = "$GOOGLE_AUTH_ENDPOINT?" +
                "client_id=$OFFICIAL_CLIENT_ID&" +
                "redirect_uri=$redirectUri&" +
                "response_type=code&" +
                "scope=$encodedScope&" +
                "code_challenge=$challenge&" +
                "code_challenge_method=S256&" +
                "state=$state&" +
                "access_type=offline&" +
                "prompt=consent"

        mutableState.value = AntigravityAuthState(
            status = AntigravityAuthStatus.AWAITING_CODE,
            authorizationUrl = authUrl,
            message = "Google Sign-In ready. Tap 'Open URL' to sign in, or paste the code below."
        )
    }

    suspend fun exchangeCodeForTokens(code: String, redirectUri: String) = withContext(Dispatchers.IO) {
        val verifier = activeCodeVerifier ?: error("Missing PKCE code verifier")
        mutableState.value = mutableState.value.copy(
            status = AntigravityAuthStatus.COMPLETING,
            message = "Exchanging authorization token with Google..."
        )

        val formBody = FormBody.Builder()
            .add("client_id", OFFICIAL_CLIENT_ID)
            .add("code", code.trim())
            .add("code_verifier", verifier)
            .add("redirect_uri", redirectUri)
            .add("grant_type", "authorization_code")
            .build()

        val request = Request.Builder()
            .url(GOOGLE_TOKEN_ENDPOINT)
            .post(formBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errMsg = runCatching { JSONObject(responseBody).optString("error_description") }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "Google token exchange failed (${response.code})"
                    mutableState.value = AntigravityAuthState(
                        status = AntigravityAuthStatus.ERROR,
                        message = errMsg
                    )
                    stopLoopbackServer()
                    return@withContext
                }

                val tokenJson = JSONObject(responseBody)
                val accessToken = tokenJson.optString("access_token")
                val refreshToken = tokenJson.optString("refresh_token")
                val expiresIn = tokenJson.optLong("expires_in", 3599L)
                val idToken = tokenJson.optString("id_token")

                var email = extractEmailFromIdToken(idToken)
                if (email == null && accessToken.isNotBlank()) {
                    email = fetchEmailFromUserInfo(accessToken)
                }
                val finalEmail = email ?: "active.developer@google.com"

                saveOfficialCredential(accessToken, refreshToken, expiresIn, finalEmail)
                stopLoopbackServer()

                onSignedInChanged?.invoke(true, finalEmail, accessToken)
                mutableState.value = AntigravityAuthState(
                    status = AntigravityAuthStatus.SIGNED_IN,
                    accountEmail = finalEmail,
                    message = "Connected as $finalEmail"
                )
            }
        } catch (e: Exception) {
            mutableState.value = AntigravityAuthState(
                status = AntigravityAuthStatus.ERROR,
                message = e.message ?: "Network error during Google token exchange"
            )
            stopLoopbackServer()
        }
    }

    private fun extractEmailFromIdToken(idToken: String): String? {
        if (idToken.isBlank()) return null
        return runCatching {
            val parts = idToken.split(".")
            if (parts.size >= 2) {
                val payloadJson = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
                val json = JSONObject(payloadJson)
                json.optString("email").takeIf { it.isNotBlank() }
            } else null
        }.getOrNull()
    }

    private fun fetchEmailFromUserInfo(accessToken: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url(GOOGLE_USERINFO_ENDPOINT)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
            httpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string().orEmpty()
                    JSONObject(body).optString("email").takeIf { it.isNotBlank() }
                } else null
            }
        }.getOrNull()
    }

    private fun saveOfficialCredential(accessToken: String, refreshToken: String, expiresIn: Long, email: String) {
        val expiryTime = System.currentTimeMillis() + (expiresIn * 1000L)
        val tokenObj = JSONObject().apply {
            put("access_token", accessToken)
            put("token_type", "Bearer")
            if (refreshToken.isNotBlank()) put("refresh_token", refreshToken)
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            put("expiry", sdf.format(Date(expiryTime)))
            put("expires_in", expiresIn)
        }
        val rootObj = JSONObject().apply {
            put("token", tokenObj)
            put("auth_method", "consumer")
            put("id_token_email", email)
        }
        val content = rootObj.toString(2)

        val appTokenFile = File(context.filesDir, ".gemini/antigravity-cli/antigravity-oauth-token")
        appTokenFile.parentFile?.mkdirs()
        appTokenFile.writeText(content)

        val rootfsTokenFile = File(context.filesDir, "runtime/rootfs/root/.gemini/antigravity-cli/antigravity-oauth-token")
        if (rootfsTokenFile.parentFile?.parentFile?.exists() == true) {
            rootfsTokenFile.parentFile?.mkdirs()
            rootfsTokenFile.writeText(content)
        }

        val authConfigFile = File(context.filesDir, ".gemini/antigravity-cli/auth.json")
        authConfigFile.parentFile?.mkdirs()
        val authJson = JSONObject().apply {
            put("session_type", "antigravity_cli_oauth")
            put("email", email)
            put("token", accessToken)
            if (refreshToken.isNotBlank()) put("refresh_token", refreshToken)
            put("authenticated", true)
            put("created_at", System.currentTimeMillis())
        }
        authConfigFile.writeText(authJson.toString(2))
    }

    fun submitCode(code: String) {
        val clean = code.trim()
        require(clean.isNotBlank()) { "Paste the authorization code from Google" }

        // If a real PTY process is active, forward the code to stdin
        val running = process
        if (running?.isAlive == true) {
            running.outputStream.write((clean + "\r\n").toByteArray())
            running.outputStream.flush()
            codeSubmitted = true
            mutableState.value = mutableState.value.copy(
                status = AntigravityAuthStatus.COMPLETING,
                message = "Completing Google sign-in..."
            )
            return
        }

        // Direct PKCE exchange
        val parsedCode = if (clean.contains("code=")) {
            clean.substringAfter("code=").substringBefore("&").substringBefore(" ")
        } else {
            clean
        }

        val redirectUri = activeRedirectUri ?: "http://localhost:4000"
        authScope.launch {
            try {
                exchangeCodeForTokens(parsedCode, redirectUri)
            } catch (e: Exception) {
                mutableState.value = AntigravityAuthState(
                    status = AntigravityAuthStatus.ERROR,
                    message = e.message ?: "Sign-in exchange failed"
                )
            }
        }
    }

    private suspend fun tryPtySpawnFlow(agyBin: File): Boolean = withContext(Dispatchers.IO) {
        val workspace = File(context.filesDir, "workspaces/antigravity-auth").apply { mkdirs() }
        val ptyEnv = mapOf(
            "SSH_CONNECTION" to "127.0.0.1 1 127.0.0.1 1",
            "TERM" to "xterm-256color",
            "NO_COLOR" to "1",
            "HOME" to "/root",
            "LANG" to "C.UTF-8"
        )

        val running = runCatching {
            NativeSpawnProcess.start(
                argv = listOf(agyBin.absolutePath),
                environment = ptyEnv,
                cwd = workspace.absolutePath,
                outputFile = authOutput,
                pseudoTerminal = true,
                ptyRows = 40,
                ptyColumns = 120
            )
        }.getOrNull() ?: return@withContext false

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

                if (handshakeReplies < 5 &&
                    output.length - lastHandshakeReplyLength > 200 &&
                    output.substring(lastHandshakeReplyLength).contains("\u001B[?u")
                ) {
                    running.outputStream.write(TERMINAL_HANDSHAKE_REPLY.toByteArray())
                    running.outputStream.flush()
                    handshakeReplies++
                    lastHandshakeReplyLength = output.length
                }

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

                val url = extractGoogleOAuthUrl(clean)
                if (url != null && mutableState.value.authorizationUrl == null) {
                    mutableState.value = AntigravityAuthState(
                        AntigravityAuthStatus.AWAITING_CODE,
                        authorizationUrl = url,
                        message = "Finish signing in with Google, then paste the one-time code."
                    )
                }

                if (isSignedInScreen(clean)) {
                    val email = extractSignedInEmail(clean) ?: "user@google.com"
                    onSignedInChanged?.invoke(true, email, null)
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
                    return@withContext true
                }

                if (!colorScreenCompleted && clean.contains("Choose your color scheme", true)) {
                    running.outputStream.write("\r\n".toByteArray())
                    running.outputStream.flush()
                    colorScreenCompleted = true
                }
                if (!renderingScreenCompleted &&
                    clean.contains("no flickering (altscreen)", true) &&
                    clean.contains("Native Terminal experience (inline)", true)
                ) {
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
                    return@withContext false
                }
            }

            if (!codeSubmitted && mutableState.value.status == AntigravityAuthStatus.STARTING) {
                // If PTY exited before producing URL, return false to trigger PKCE fallback
                return@withContext false
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            if (running.isAlive) running.destroy()
            runCatching { running.outputStream.close() }
            running.outputFile.delete()
            process = null
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        stopLoopbackServer()
        process?.destroy()
        process = null
        val previousEmail = mutableState.value.accountEmail
        mutableState.value = AntigravityAuthState(
            status = AntigravityAuthStatus.STARTING,
            message = "Signing out of Antigravity...",
            accountEmail = previousEmail
        )
        try {
            val credential = officialCredentialFile()
            if (credential.exists()) credential.delete()
            val alt = File(context.filesDir, ".gemini/antigravity-cli/antigravity-oauth-token")
            if (alt.exists()) alt.delete()
            val authConfig = File(context.filesDir, ".gemini/antigravity-cli/auth.json")
            if (authConfig.exists()) authConfig.delete()

            onSignedInChanged?.invoke(false, null, null)
            mutableState.value = AntigravityAuthState(AntigravityAuthStatus.SIGNED_OUT, message = "Signed out")
        } catch (error: Throwable) {
            mutableState.value = AntigravityAuthState(
                status = AntigravityAuthStatus.SIGNED_IN,
                message = error.message?.take(240) ?: "Could not log out of Antigravity",
                accountEmail = previousEmail
            )
            throw error
        } finally {
            logoutOutput.delete()
        }
    }

    fun cancel() {
        stopLoopbackServer()
        process?.destroy()
        process = null
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
private val AUTH_ANSI = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")
private fun sanitizeTerminalOutput(text: String): String = text
    .replace(AUTH_ANSI, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }
